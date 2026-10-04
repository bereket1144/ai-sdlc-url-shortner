package io.github.bereket1144.agentcontroller.run;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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
        mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingBody() throws Exception {
        mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validRequestCreatesRunAndReturnsItsId() throws Exception {
        UUID runId = UUID.randomUUID();
        Run savedRun = mock(Run.class);
        when(savedRun.getId()).thenReturn(runId);
        when(runService.createRun("Build a task tracker")).thenReturn(savedRun);

        mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"Build a task tracker\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(runId.toString()));

        verify(runService).createRun("Build a task tracker");
    }

    // AC-RUN-1.2: an id sent by the client is not used; the response carries the id the platform generated.
    @Test
    void clientSuppliedIdIsIgnored() throws Exception {
        UUID suppliedId = UUID.randomUUID();
        UUID generatedId = UUID.randomUUID();
        Run savedRun = mock(Run.class);
        when(savedRun.getId()).thenReturn(generatedId);
        when(runService.createRun("Build a task tracker")).thenReturn(savedRun);

        mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + suppliedId + "\",\"requirement\":\"Build a task tracker\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(generatedId.toString()));

        verify(runService).createRun("Build a task tracker");
    }

    // AC-RUN-3.2: the unversioned path no longer exists.
    @Test
    void unversionedPathReturnsNotFoundAndSavesNothing() throws Exception {
        mvc.perform(post("/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"Build a task tracker\"}"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(runService);
    }

    // AC-RUN-3.3: a version that does not exist is not served either.
    @Test
    void unknownVersionReturnsNotFoundAndSavesNothing() throws Exception {
        mvc.perform(post("/v2/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"Build a task tracker\"}"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(runService);
    }
}
