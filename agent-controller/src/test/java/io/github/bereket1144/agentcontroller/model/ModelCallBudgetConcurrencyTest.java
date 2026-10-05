package io.github.bereket1144.agentcontroller.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.model.ModelCallBudget.BudgetExhaustedException;
import io.github.bereket1144.agentcontroller.model.ModelCallBudget.Limit;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords;
import io.github.bereket1144.agentcontroller.run.RunRepository;
import io.github.bereket1144.agentcontroller.run.RunService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "agent.budget.max-calls-per-run=1"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, ModelCallBudget.class, ModelCallRecords.class,
        RoleCatalog.class, RunService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ModelCallBudgetConcurrencyTest {

    @Autowired
    private ModelCallBudget budget;

    @Autowired
    private RunService runs;

    @Autowired
    private RunRepository runRepository;

    @Test
    @Timeout(15)
    @DisplayName("AC-AGT-8.2 simultaneous attempts on the same run cannot both reserve its last call")
    void concurrentAttemptsCannotExceedRunLimit() throws Exception {
        UUID runId = runs.createRun("Build a task tracker").getId();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Object> attempt = () -> {
                start.await();
                try {
                    return budget.reserve(runId, "requirements", 10, 20);
                } catch (BudgetExhaustedException failure) {
                    return failure;
                }
            };
            Future<Object> first = pool.submit(attempt);
            Future<Object> second = pool.submit(attempt);
            start.countDown();
            List<Object> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(results.stream().filter(UUID.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(BudgetExhaustedException.class::isInstance)).hasSize(1);
            BudgetExhaustedException refusal = (BudgetExhaustedException) results.stream()
                    .filter(BudgetExhaustedException.class::isInstance).findFirst().orElseThrow();
            assertThat(refusal.limit()).isEqualTo(Limit.RUN_CALLS);

            UUID reservationId = (UUID) results.stream().filter(UUID.class::isInstance).findFirst().orElseThrow();
            budget.recorded(reservationId);
        } finally {
            runRepository.deleteById(runId);
        }
    }
}
