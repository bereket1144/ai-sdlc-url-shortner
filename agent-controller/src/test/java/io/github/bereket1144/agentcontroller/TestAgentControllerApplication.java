package io.github.bereket1144.agentcontroller;

import org.springframework.boot.SpringApplication;

public class TestAgentControllerApplication {

    public static void main(String[] args) {
        SpringApplication.from(AgentControllerApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
