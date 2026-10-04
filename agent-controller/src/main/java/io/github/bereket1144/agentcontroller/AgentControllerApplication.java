package io.github.bereket1144.agentcontroller;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AgentControllerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentControllerApplication.class, args);
    }

}
