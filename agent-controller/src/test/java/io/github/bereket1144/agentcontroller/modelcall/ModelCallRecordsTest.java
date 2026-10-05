package io.github.bereket1144.agentcontroller.modelcall;

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
import io.github.bereket1144.agentcontroller.agent.RoleCatalog.InputKind;
import io.github.bereket1144.agentcontroller.model.ModelClient.FailureReason;
import io.github.bereket1144.agentcontroller.model.ModelClient.FinishReason;
import io.github.bereket1144.agentcontroller.model.ModelClient.ModelResult;
import io.github.bereket1144.agentcontroller.model.ModelProfile;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.ReductionStep;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.Attempt;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.AttemptKind;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.Outcome;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.StoredAttempt;
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

// Flyway creates the real PostgreSQL schema; Hibernate only validates the mapping.
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, ModelCallRecords.class, RoleCatalog.class})
class ModelCallRecordsTest {

    private static final ModelProfile PROFILE = new ModelProfile("local", "qwen-test",
            URI.create("http://localhost:11434"), 32768, 4096, Duration.ofSeconds(300), 1, 2, 0.0, null);

    @Autowired
    private ModelCallRecords records;

    @Autowired
    private ModelCallRepository repository;

    @Autowired
    private RoleCatalog roles;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("AC-AGT-7.1 an attempt is stored with every field and read back unchanged")
    void storesEveryFieldOfAnAttempt() {
        UUID runId = newRun();
        List<ReductionStep> reductions = List.of(new ReductionStep(2, InputKind.DESIGN_ARTIFACT, "3"),
                new ReductionStep(1, InputKind.PLAN_ARTIFACT, "1"));
        ModelResult result = new ModelResult("the answer", FinishReason.COMPLETE, 120L, 45L, Duration.ofMillis(2500));

        records.record(new Attempt(runId, "requirements", PROFILE, AttemptKind.REPAIR, "system text", "user text",
                result, Outcome.SUCCESS, 100, 40L, reductions, Duration.ofMillis(2500)));
        entityManager.clear(); // so that the read below comes from PostgreSQL, not from the persistence context

        List<StoredAttempt> stored = records.forRun(runId);
        assertThat(stored).hasSize(1);
        long id = stored.get(0).id();
        assertThat(id).isPositive();
        assertThat(stored.get(0)).isEqualTo(new StoredAttempt(id, runId, "requirements",
                roles.get("requirements").version(), "local", "qwen-test", AttemptKind.REPAIR,
                InputFingerprint.of("local", "system text", "user text"), "system text", "user text",
                "the answer", FinishReason.COMPLETE, 100, 40L, 120L, 45L, "2:DESIGN_ARTIFACT:3;1:PLAN_ARTIFACT:1",
                2500, Outcome.SUCCESS));
    }

    @Test
    @DisplayName("AC-AGT-7.2 failed, invalid and cut-off attempts are stored with their outcome")
    void storesFailedInvalidAndCutOffAttempts() {
        UUID runId = newRun();
        records.record(attempt(runId, AttemptKind.FIRST, null, Outcome.TIMED_OUT));
        records.record(attempt(runId, AttemptKind.REPAIR, answer("not json", FinishReason.COMPLETE),
                Outcome.INVALID_ANSWER));
        records.record(attempt(runId, AttemptKind.REPAIR, answer("{\"partial", FinishReason.ANSWER_LIMIT),
                Outcome.ANSWER_CUT_OFF));
        entityManager.clear();

        assertThat(records.forRun(runId))
                .extracting(StoredAttempt::outcome, StoredAttempt::answerText, StoredAttempt::finishReason,
                        StoredAttempt::reportedPromptTokens)
                .containsExactly(
                        tuple(Outcome.TIMED_OUT, null, null, null),
                        tuple(Outcome.INVALID_ANSWER, "not json", FinishReason.COMPLETE, 120L),
                        tuple(Outcome.ANSWER_CUT_OFF, "{\"partial", FinishReason.ANSWER_LIMIT, 120L));
    }

    @Test
    @DisplayName("AC-AGT-7.2 every typed model failure can be recorded as an outcome of the same name")
    void everyModelFailureHasAnOutcome() {
        assertThat(Arrays.stream(Outcome.values()).map(Enum::name))
                .containsAll(Arrays.stream(FailureReason.values()).map(Enum::name).toList());
    }

    @Test
    @DisplayName("AC-AGT-7.3 the service offers record and forRun, and no operation that changes or deletes a record")
    void theServiceOffersOnlyRecordAndForRun() {
        assertThat(Arrays.stream(ModelCallRecords.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName))
                .containsExactlyInAnyOrder("record", "forRun");
    }

    @Test
    @DisplayName("AC-AGT-7.3 a changed entity is not written back to its row")
    void aChangedEntityIsNotWrittenBack() {
        UUID runId = newRun();
        records.record(attempt(runId, AttemptKind.FIRST, answer("the answer", FinishReason.COMPLETE), Outcome.SUCCESS));
        entityManager.clear();
        ModelCallEntity loaded = repository.findByRunIdOrderByIdAsc(runId).get(0);

        ReflectionTestUtils.setField(loaded, "answerText", "changed");
        entityManager.flush();

        assertThat(jdbcTemplate.queryForObject(
                "select answer_text from model_call where run_id = ?", String.class, runId))
                .isEqualTo("the answer");
    }

    @Test
    @DisplayName("AC-AGT-7.4 a run's records are listed oldest first, and only that run's")
    void listsARunsRecordsOldestFirst() {
        UUID first = newRun();
        UUID second = newRun();
        UUID quiet = newRun();
        records.record(attempt(first, AttemptKind.FIRST, null, Outcome.UNREACHABLE));
        records.record(attempt(second, AttemptKind.FIRST, null, Outcome.TIMED_OUT));
        records.record(attempt(first, AttemptKind.RETRY, null, Outcome.RATE_LIMITED));
        records.record(attempt(first, AttemptKind.FALLBACK, answer("the answer", FinishReason.COMPLETE),
                Outcome.SUCCESS));
        entityManager.clear();

        assertThat(records.forRun(first)).extracting(StoredAttempt::kind)
                .containsExactly(AttemptKind.FIRST, AttemptKind.RETRY, AttemptKind.FALLBACK);
        assertThat(records.forRun(second)).extracting(StoredAttempt::outcome).containsExactly(Outcome.TIMED_OUT);
        assertThat(records.forRun(quiet)).isEmpty();
    }

    @Test
    @DisplayName("AC-AGT-7.1 recording an attempt for a run that does not exist fails")
    void refusesAnAttemptForAnUnknownRun() {
        assertThatThrownBy(() -> records.record(attempt(UUID.randomUUID(), AttemptKind.FIRST, null, Outcome.TIMED_OUT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("AC-AGT-7.6 stored text never reaches a log")
    void storedTextNeverReachesALog() {
        UUID runId = newRun();
        // The root logger, so that a line from any logger would be caught, not only this service's.
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            records.record(new Attempt(runId, "requirements", PROFILE, AttemptKind.REPAIR,
                    "PRIVATE_SYSTEM_MARKER", "PRIVATE_USER_MARKER",
                    answer("PRIVATE_ANSWER_MARKER", FinishReason.COMPLETE), Outcome.SUCCESS, 100, 40L, List.of(),
                    Duration.ofMillis(500)));
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("PRIVATE_"));
        assertThat(appender.list).filteredOn(event -> event.getLoggerName().equals(ModelCallRecords.class.getName()))
                .singleElement().satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.INFO);
                    assertThat(event.getFormattedMessage()).contains("event=model_call_recorded",
                            "runId=" + runId, "role=requirements", "attemptKind=REPAIR", "outcome=SUCCESS");
                });
    }

    /** A stored run for the attempts to belong to; the table has a foreign key to runs. */
    private UUID newRun() {
        return runRepository.saveAndFlush(new Run("Build a task tracker")).getId();
    }

    private static Attempt attempt(UUID runId, AttemptKind kind, ModelResult result, Outcome outcome) {
        return new Attempt(runId, "requirements", PROFILE, kind, "system text", "user text", result, outcome,
                100, null, List.of(), Duration.ofMillis(500));
    }

    private static ModelResult answer(String text, FinishReason finishReason) {
        return new ModelResult(text, finishReason, 120L, 45L, Duration.ofMillis(500));
    }
}
