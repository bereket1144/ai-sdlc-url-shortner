package io.github.bereket1144.agentcontroller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
// Start through main(), so this test also checks the application's real startup entry point.
@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class AgentControllerApplicationTests {

    @Test
    void contextLoadsThroughMain() {
    }

}
