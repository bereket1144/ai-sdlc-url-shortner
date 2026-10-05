package io.github.bereket1144.agentcontroller.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.NewArtifact;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.StoredArtifact;
import io.github.bereket1144.agentcontroller.model.ModelProfile;
import io.github.bereket1144.agentcontroller.run.Run;
import io.github.bereket1144.agentcontroller.run.RunRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Flyway creates the real PostgreSQL schema; Hibernate only validates the mapping.
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, ArtifactStore.class, RoleCatalog.class})
class ArtifactStoreTest {

    private static final ModelProfile PROFILE = new ModelProfile("local", "qwen-test",
            URI.create("http://localhost:11434"), 32768, 4096, Duration.ofSeconds(300), 1, 2, 0.0, null);

    private final JsonMapper mapper = JsonMapper.shared();

    @Autowired
    private ArtifactStore store;

    @Autowired
    private ArtifactRepository repository;

    @Autowired
    private RoleCatalog roles;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("AC-AGT-11.1 an artifact is stored with every field and read back unchanged")
    void storesEveryFieldOfAnArtifact() {
        UUID runId = newRun();
        ArtifactRef consumed = new ArtifactRef("requirements", 2, "a".repeat(64));
        JsonNode content = json("{\"stories\":[{\"id\":\"S-1\"}],\"summary\":\"one story\"}");

        ArtifactRef ref = store.store(new NewArtifact(runId, "design", content, "one story", PROFILE,
                List.of(consumed)));
        entityManager.clear(); // so that the read below comes from PostgreSQL, not from the persistence context

        StoredArtifact stored = store.find(runId, "design", 1).orElseThrow();
        assertThat(stored).isEqualTo(new StoredArtifact(runId, "design", 1, content, "one story", ref.fingerprint(),
                "design", roles.get("design").version(), "local", "qwen-test", List.of(consumed)));
        assertThat(ref).isEqualTo(new ArtifactRef("design", 1, ref.fingerprint()));
        assertThat(ref.fingerprint()).hasSize(64);
    }

    @Test
    @DisplayName("AC-AGT-11.1 an artifact with no consumed versions is stored and read back with none")
    void storesAnArtifactWithNoConsumedVersions() {
        UUID runId = newRun();
        store.store(new NewArtifact(runId, "requirements", json("{\"a\":1}"), "summary", PROFILE, List.of()));
        entityManager.clear();

        assertThat(store.find(runId, "requirements", 1).orElseThrow().consumed()).isEmpty();
    }

    @Test
    @DisplayName("AC-AGT-11.1 an artifact for an unknown role or a run that does not exist is refused")
    void refusesAnArtifactForAnUnknownRoleOrRun() {
        UUID runId = newRun();

        assertThatThrownBy(() -> store.store(artifact(runId, "no-such-role", "{\"a\":1}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no-such-role");
        assertThatThrownBy(() -> store.store(artifact(UUID.randomUUID(), "design", "{\"a\":1}")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("AC-AGT-11.1 an artifact with a blank summary or missing content is refused")
    void refusesAnArtifactWithoutSummaryOrContent() {
        UUID runId = newRun();

        assertThatThrownBy(() -> new NewArtifact(runId, "design", json("{}"), " ", PROFILE, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("summary");
        assertThatThrownBy(() -> new NewArtifact(runId, "design", json("{}"), null, PROFILE, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("summary");
        assertThatThrownBy(() -> new NewArtifact(runId, "design", null, "summary", PROFILE, List.of()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("content");
    }

    @Test
    @DisplayName("AC-AGT-11.2 producing a kind again creates the next version and the older one stays unchanged")
    void producingAKindAgainCreatesTheNextVersion() {
        UUID runId = newRun();
        ArtifactRef first = store.store(artifact(runId, "design", "{\"v\":\"first\"}"));
        ArtifactRef second = store.store(artifact(runId, "design", "{\"v\":\"second\"}"));
        entityManager.clear();

        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
        assertThat(store.find(runId, "design", 1).orElseThrow().content()).isEqualTo(json("{\"v\":\"first\"}"));
        assertThat(store.find(runId, "design", 1).orElseThrow().fingerprint()).isEqualTo(first.fingerprint());
        assertThat(store.find(runId, "design", 2).orElseThrow().content()).isEqualTo(json("{\"v\":\"second\"}"));
    }

    @Test
    @DisplayName("AC-AGT-11.2 the version counts separately for each run and each kind")
    void theVersionCountsPerRunAndKind() {
        UUID runId = newRun();
        UUID otherRun = newRun();
        store.store(artifact(runId, "design", "{\"a\":1}"));
        store.store(artifact(runId, "design", "{\"a\":2}"));

        assertThat(store.store(artifact(runId, "requirements", "{\"a\":1}")).version()).isEqualTo(1);
        assertThat(store.store(artifact(otherRun, "design", "{\"a\":1}")).version()).isEqualTo(1);
        assertThat(store.store(artifact(runId, "design", "{\"a\":3}")).version()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-AGT-11.2 the store offers store and three reads (find, forRun, latest), and no operation that changes or deletes")
    void theStoreOffersOnlyStoreAndReads() {
        assertThat(Arrays.stream(ArtifactStore.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName))
                .containsExactlyInAnyOrder("store", "find", "forRun", "latest");
    }

    @Test
    @DisplayName("AC-AGT-11.2 a changed entity is not written back to its row")
    void aChangedEntityIsNotWrittenBack() {
        UUID runId = newRun();
        store.store(artifact(runId, "design", "{\"a\":1}"));
        entityManager.clear();
        ArtifactEntity loaded = repository.findByRunIdOrderByIdAsc(runId).get(0);

        ReflectionTestUtils.setField(loaded, "summary", "changed");
        entityManager.flush();

        assertThat(jdbcTemplate.queryForObject("select summary from artifact where run_id = ?", String.class, runId))
                .isEqualTo("summary");
    }

    @Test
    @DisplayName("AC-AGT-11.3 equal content gives an equal fingerprint and changed content gives a different one")
    void equalContentGivesEqualFingerprint() {
        UUID runId = newRun();
        UUID otherRun = newRun();

        ArtifactRef original = store.store(artifact(runId, "design", "{\"a\":1,\"b\":[1,2]}"));
        ArtifactRef sameInOtherRun = store.store(artifact(otherRun, "design", "{\"a\":1,\"b\":[1,2]}"));
        ArtifactRef sameAgain = store.store(artifact(runId, "design", "{\"a\":1,\"b\":[1,2]}"));
        ArtifactRef changed = store.store(artifact(runId, "design", "{\"a\":1,\"b\":[1,3]}"));

        assertThat(sameInOtherRun.fingerprint()).isEqualTo(original.fingerprint());
        assertThat(sameAgain.fingerprint()).isEqualTo(original.fingerprint());
        assertThat(changed.fingerprint()).isNotEqualTo(original.fingerprint());
    }

    @Test
    @DisplayName("AC-AGT-11.4 an unknown run, kind or version is reported as not found")
    void anUnknownArtifactIsNotFound() {
        UUID runId = newRun();
        store.store(artifact(runId, "design", "{\"a\":1}"));

        assertThat(store.find(runId, "design", 1)).isPresent();
        assertThat(store.find(runId, "design", 2)).isEmpty();
        assertThat(store.find(runId, "plan", 1)).isEmpty();
        assertThat(store.find(UUID.randomUUID(), "design", 1)).isEmpty();
    }

    @Test
    @DisplayName("AC-AGT-11.5 a reference carries only the kind, the version and the fingerprint")
    void aReferenceCarriesNoContent() {
        UUID runId = newRun();
        ArtifactRef ref = store.store(artifact(runId, "design", "{\"secret\":\"PRIVATE_CONTENT_MARKER\"}"));

        // What a checkpoint would hold of the artifact is this JSON text.
        JsonNode inState = mapper.readTree(mapper.writeValueAsString(ref));

        assertThat(inState).isEqualTo(json("{\"kind\":\"design\",\"version\":1,\"fingerprint\":\""
                + ref.fingerprint() + "\"}"));
    }

    @Test
    @DisplayName("AC-AGT-11.5 stored content and summary never reach a log")
    void storedTextNeverReachesALog() {
        UUID runId = newRun();
        // The root logger, so that a line from any logger would be caught, not only this service's.
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        ArtifactRef ref;
        try {
            ref = store.store(new NewArtifact(runId, "design", json("{\"a\":\"PRIVATE_CONTENT_MARKER\"}"),
                    "PRIVATE_SUMMARY_MARKER", PROFILE, List.of()));
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("PRIVATE_"));
        assertThat(appender.list).filteredOn(event -> event.getLoggerName().equals(ArtifactStore.class.getName()))
                .singleElement().satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.INFO);
                    assertThat(event.getFormattedMessage()).contains("event=artifact_stored", "runId=" + runId,
                            "role=design", "kind=design", "version=1", "fingerprint=" + ref.fingerprint(),
                            "consumedCount=0");
                });
    }

    @Test
    @DisplayName("AC-AGT-11.6 a run's artifacts are listed in the order stored with what each consumed")
    void listsARunsArtifactsWithLineage() {
        UUID runId = newRun();
        UUID otherRun = newRun();
        UUID quietRun = newRun();
        ArtifactRef requirements = store.store(artifact(runId, "requirements", "{\"a\":1}"));
        store.store(artifact(otherRun, "requirements", "{\"a\":9}"));
        store.store(new NewArtifact(runId, "design", json("{\"a\":2}"), "summary", PROFILE, List.of(requirements)));
        entityManager.clear();

        assertThat(store.forRun(runId)).extracting(StoredArtifact::kind, StoredArtifact::consumed)
                .containsExactly(
                        tuple("requirements", List.of()),
                        tuple("design", List.of(requirements)));
        assertThat(store.forRun(quietRun)).isEmpty();
    }

    /** A stored run for the artifacts to belong to; the table has a foreign key to runs. */
    private UUID newRun() {
        return runRepository.saveAndFlush(new Run("Build a task tracker")).getId();
    }

    private NewArtifact artifact(UUID runId, String role, String contentJson) {
        return new NewArtifact(runId, role, json(contentJson), "summary", PROFILE, List.of());
    }

    private JsonNode json(String text) {
        return mapper.readTree(text);
    }
}
