package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// Flyway creates the real PostgreSQL schema; Hibernate only validates the mapping.
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, RunService.class})
class RunPersistenceTest {

    @Autowired
    private RunService runService;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // PostgreSQL stores microseconds, so a time read back is the time Hibernate set, cut to microseconds.
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** Saves a run, then drops it from the persistence context so the next read comes from PostgreSQL. */
    private Run savedAndReloaded(String requirement) {
        Run run = runRepository.saveAndFlush(new Run(requirement));
        entityManager.clear();
        return runRepository.findById(run.getId()).orElseThrow();
    }

    /** Changes a loaded run the only way available today: there is no real way to change a run yet. */
    private void change(Run run, String requirement) {
        ReflectionTestUtils.setField(run, "requirement", requirement);
    }

    @Test
    @DisplayName("AC-RUN-1.3, 1.4 a run with a requirement longer than 255 characters is saved and read back unchanged")
    void persistsAndReloadsRunWithGeneratedIdAndLongRequirement() {
        String requirement = "Build a task tracker. ".repeat(20);
        Run run = new Run(requirement);

        runRepository.saveAndFlush(run);
        assertThat(run.getId()).isNotNull();

        // Clear JPA's in-memory cache so find must read the row from PostgreSQL.
        entityManager.clear();
        Run reloaded = runRepository.findById(run.getId()).orElseThrow();

        assertThat(reloaded).isNotNull().isNotSameAs(run);
        assertThat(reloaded.getId()).isEqualTo(run.getId());
        assertThat(reloaded.getRequirement()).isEqualTo(requirement);
    }

    @Test
    @DisplayName("AC-RUN-6.1 a new run has status CREATED")
    void newRunHasStatusCreated() {
        Run reloaded = savedAndReloaded("Build a task tracker");

        assertThat(reloaded.getStatus()).isEqualTo(RunStatus.CREATED);
    }

    @Test
    @DisplayName("AC-RUN-6.2 createdAt is set once, when the run is first saved, and never changes afterwards")
    void createdAtIsSetOnceWhenFirstSaved() {
        Instant before = now();
        Run run = savedAndReloaded("Build a task tracker");
        Instant after = now();
        assertThat(run.getCreatedAt()).isBetween(before, after);

        Instant createdAt = run.getCreatedAt();
        change(run, "Build a better task tracker");
        runRepository.saveAndFlush(run);
        entityManager.clear();

        Run changed = runRepository.findById(run.getId()).orElseThrow();
        assertThat(changed.getVersion()).isEqualTo(1L); // the change really was written
        assertThat(changed.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("AC-RUN-6.3 updatedAt is set on first save, never earlier than createdAt, and set to the current time on each change")
    void updatedAtIsSetOnFirstSaveThenMovesOnChange() {
        Instant beforeSave = now();
        Run run = savedAndReloaded("Build a task tracker");
        Instant afterSave = now();
        assertThat(run.getUpdatedAt()).isBetween(beforeSave, afterSave).isAfterOrEqualTo(run.getCreatedAt());

        Instant beforeChange = now();
        change(run, "Build a better task tracker");
        runRepository.saveAndFlush(run);
        Instant afterChange = now();
        entityManager.clear();

        assertThat(runRepository.findById(run.getId()).orElseThrow().getUpdatedAt())
                .isBetween(beforeChange, afterChange);
    }

    @Test
    @DisplayName("AC-RUN-6.4 version is 0 on first save and increases by 1 on each change")
    void versionStartsAtZeroAndGrowsByOnePerChange() {
        Run run = savedAndReloaded("Build a task tracker");
        assertThat(run.getVersion()).isZero();

        change(run, "first change");
        runRepository.saveAndFlush(run);
        change(run, "second change");
        runRepository.saveAndFlush(run);
        entityManager.clear();

        assertThat(runRepository.findById(run.getId()).orElseThrow().getVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("AC-RUN-6.5 a change based on an outdated version is rejected and the stored run is unchanged")
    void staleChangeIsRejectedAndStoredRunIsUnchanged() {
        Run first = savedAndReloaded("original");
        entityManager.clear();
        // A second copy of the same row, loaded before the first copy is changed.
        Run stale = runRepository.findById(first.getId()).orElseThrow();
        entityManager.clear();

        change(first, "first writer");
        runRepository.saveAndFlush(first);
        entityManager.clear();

        change(stale, "stale writer");
        assertThatThrownBy(() -> runRepository.saveAndFlush(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);

        String stored = jdbcTemplate.queryForObject("select requirement from runs where id = ?", String.class,
                first.getId());
        assertThat(stored).isEqualTo("first writer");
    }

    @Test
    @DisplayName("AC-RUN-7.2 the service reads a stored run back by id")
    void serviceReadsStoredRun() {
        Run saved = runRepository.saveAndFlush(new Run("Build a task tracker"));
        entityManager.clear();

        Run read = runService.getRun(saved.getId());

        assertThat(read.getId()).isEqualTo(saved.getId());
        assertThat(read.getRequirement()).isEqualTo("Build a task tracker");
    }

    @Test
    @DisplayName("AC-RUN-7.4 the service fails with 404 for an id that does not exist")
    void serviceFailsWithNotFoundForUnknownId() {
        assertThatThrownBy(() -> runService.getRun(UUID.randomUUID()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("A claimed run is committed as RUNNING and cannot be claimed again")
    void claimNextRunPersistsStatus() {
        Run created = runService.createRun("Build a task tracker");
        try {
            Run claimed = runService.claimNextRun().orElseThrow();
            assertThat(claimed.getId()).isEqualTo(created.getId());
            assertThat(claimed.getRequirement()).isEqualTo(created.getRequirement());
            assertThat(claimed.getStatus()).isEqualTo(RunStatus.RUNNING);
            assertThat(runService.getRun(created.getId()).getStatus()).isEqualTo(RunStatus.RUNNING);
            assertThat(runService.claimNextRun()).isEmpty();
        } finally {
            runRepository.deleteById(created.getId());
        }
    }
}
