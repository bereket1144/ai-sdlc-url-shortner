package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.transaction.TestTransaction;

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

    @Test
    void serviceCreatesRunThatCanBeReloadedFromPostgres() {
        Run created = runService.createRun("Build a task tracker");

        assertThat(created.getId()).isNotNull();
        // The test normally rolls back. Commit here so the next read proves the service's write persisted.
        TestTransaction.flagForCommit();
        TestTransaction.end();

        Run reloaded = runRepository.findById(created.getId()).orElseThrow();
        assertThat(reloaded).isNotSameAs(created);
        assertThat(reloaded.getRequirement()).isEqualTo("Build a task tracker");
    }

    @Test
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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void rejectsBlankRequirements(String requirement) {
        assertThatThrownBy(() -> new Run(requirement))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requirement must not be blank");
    }
}
