package io.github.bereket1144.agentcontroller.run;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

// RUN-7: HTTP behaviour of reading a run back, with the service mocked.
@WebMvcTest(RunController.class)
class RunReadTest {

    @MockitoBean
    private RunService runService;

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("AC-RUN-7.1 the 201 response has a Location header with the path of the new run")
    void createdResponseHasLocationHeader() throws Exception {
        UUID id = UUID.randomUUID();
        Run saved = mock(Run.class);
        when(saved.getId()).thenReturn(id);
        when(runService.createRun("Build a task tracker")).thenReturn(saved);

        mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"Build a task tracker\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/v1/runs/" + id));
    }

    @Test
    @DisplayName("AC-RUN-7.2 reading an existing run returns 200 with id, requirement, status, createdAt, updatedAt and version")
    void existingRunIsReturnedWithAllFields() throws Exception {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-02T03:04:05Z");
        Instant updatedAt = Instant.parse("2026-01-02T03:04:06Z");
        Run run = mock(Run.class);
        when(run.getId()).thenReturn(id);
        when(run.getRequirement()).thenReturn("Build a task tracker");
        when(run.getStatus()).thenReturn(RunStatus.CREATED);
        when(run.getCreatedAt()).thenReturn(createdAt);
        when(run.getUpdatedAt()).thenReturn(updatedAt);
        when(run.getVersion()).thenReturn(3L);
        when(runService.getRun(id)).thenReturn(run);

        mvc.perform(get("/v1/runs/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.requirement").value("Build a task tracker"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-02T03:04:05Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-01-02T03:04:06Z"))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    @DisplayName("AC-RUN-7.4 reading an id that does not exist returns 404 problem+json")
    void unknownIdReturnsProblem404() throws Exception {
        UUID id = UUID.randomUUID();
        when(runService.getRun(id)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found."));

        mvc.perform(get("/v1/runs/" + id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value(ApiExceptionHandler.TYPE_PREFIX + 404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Run not found."));
    }

    @Test
    @DisplayName("AC-RUN-7.5 reading an id that is not a UUID returns 400 problem+json and does not call the service")
    void nonUuidIdReturnsProblem400() throws Exception {
        mvc.perform(get("/v1/runs/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value(ApiExceptionHandler.TYPE_PREFIX + 400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").isString());

        verifyNoInteractions(runService);
    }
}
