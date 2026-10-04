package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

class RunSchedulerDefaultsTest {

    @Configuration(proxyBeanMethods = false)
    static class SchedulerConfig {

        @Bean
        RunDispatcher runDispatcher() {
            return mock(RunDispatcher.class);
        }

        @Bean
        RunScheduler runScheduler(RunDispatcher runDispatcher) {
            return new RunScheduler(runDispatcher);
        }
    }

    @Test
    @DisplayName("AC-EXEC-4.2 with no run.dispatch.* property set, the scheduler exists and polls every 5000 ms")
    void productionDefaultsPollEveryFiveSeconds() throws NoSuchMethodException {
        new ApplicationContextRunner().withUserConfiguration(SchedulerConfig.class).run(context -> {
            assertThat(context).hasSingleBean(RunScheduler.class);

            String delay = RunScheduler.class.getMethod("poll").getAnnotation(Scheduled.class).fixedDelayString();
            assertThat(context.getEnvironment().resolvePlaceholders(delay)).isEqualTo("5000");
        });
    }
}
