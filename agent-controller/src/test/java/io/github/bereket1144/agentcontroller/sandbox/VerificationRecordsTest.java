package io.github.bereket1144.agentcontroller.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import io.github.bereket1144.agentcontroller.run.Run;
import io.github.bereket1144.agentcontroller.run.RunRepository;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

// Flyway creates the real PostgreSQL schema; Hibernate only validates the mapping.
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, VerificationRecords.class})
class VerificationRecordsTest {

    @Autowired
    private VerificationRecords records;

    @Autowired
    private RunRepository runs;

    @Test
    @DisplayName("AC-AGT-62.9 the result is stored with the commit id and the latest one reads back")
    void storesAResultAndReadsTheLatestBack() {
        UUID runId = runs.save(new Run("requirement")).getId();
        assertThat(records.latest(runId)).isEmpty();

        records.record(runId, new VerificationResult(Outcome.FAILED, 3, 2, 1, List.of("test_a", "test_b"), "report",
                "c1"));
        VerificationResult passing = new VerificationResult(Outcome.PASSED, 3, 3, 0, List.of(), "report", "c2");
        records.record(runId, passing);

        assertThat(records.latest(runId)).contains(passing);
    }
}
