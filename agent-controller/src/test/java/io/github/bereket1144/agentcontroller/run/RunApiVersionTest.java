package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;

// Full application with the real PostgreSQL schema: proves the version paths against the real routing
// and that a refused or unrouted request leaves the runs table unchanged.
// Transactional: MockMvc runs in the test thread, so rows a test creates roll back and do not leak into other tests.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RunApiVersionTest {

    private static final String VALID_BODY = "{\"requirement\":\"Build a task tracker\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RunRepository runRepository;

    @Test
    @DisplayName("AC-RUN-3.1 POST /v1/runs creates a run: 201, a UUID id, and exactly one new stored row")
    void versionedPathCreatesOneStoredRun() throws Exception {
        long before = runRepository.count();

        String json = mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(JsonPath.read(json, "$.id"));
        assertThat(runRepository.count()).isEqualTo(before + 1);
        assertThat(runRepository.findById(id)).get().extracting(Run::getRequirement)
                .isEqualTo("Build a task tracker");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/runs", "/runs/", "/v0/runs", "/V1/runs"})
    @DisplayName("AC-RUN-3.2 a valid create request to an unversioned path returns 404 and saves no run")
    void unversionedPathReturnsNotFoundAndSavesNothing(String path) throws Exception {
        long before = runRepository.count();

        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound());

        assertThat(runRepository.count()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v2/runs", "/v3/runs", "/v1.1/runs"})
    @DisplayName("AC-RUN-3.3 a valid create request to a version that does not exist returns 404 and saves no run")
    void unknownVersionReturnsNotFoundAndSavesNothing(String path) throws Exception {
        long before = runRepository.count();

        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound());

        assertThat(runRepository.count()).isEqualTo(before);
    }
}
