package io.github.bereket1144.agentcontroller.run;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Load the HTTP layer only: these checks need request parsing and validation, not PostgreSQL.
@WebMvcTest(RunController.class)
class RunControllerTest {

    // The MVC slice excludes services; supply a test double for constructor injection.
    @MockitoBean
    private RunService runService;

    // MockMvc sends requests through Spring MVC without starting a real HTTP server.
    @Autowired
    private MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"requirement\":null}",
            "{\"requirement\":\"\"}",
            "{\"requirement\":\"   \"}"
    })
    void rejectsMissingOrBlankRequirement(String body) throws Exception {
        mvc.perform(post("/runs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(post("/runs").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingBody() throws Exception {
        mvc.perform(post("/runs").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validRequestDoesNotClaimARunWasCreated() throws Exception {
        mvc.perform(post("/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"Build a task tracker\"}"))
                .andExpect(status().isNotImplemented());
    }
}
