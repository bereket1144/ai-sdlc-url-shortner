package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.ResultActions;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;

// RUN-4 on the full application: the real controller advice, the real routing and the real PostgreSQL schema.
// RunErrorFormatTest proves the same format on a slice with a mocked service; this class proves that nothing in
// the full application (resource handling, error dispatch, validation setup) changes the format, and that a
// refused request leaves the runs table unchanged.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RunErrorFormatFullContextTest {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String VALID_BODY = "{\"requirement\":\"Build a task tracker\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RunRepository runRepository;

    private static ResultActions expectProblem(ResultActions result, int status, String title) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ApiExceptionHandler.TYPE_PREFIX + status))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.detail").isString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"requirement\":null}",
            "{\"requirement\":\"\"}",
            "{\"requirement\":\"   \"}"
    })
    @DisplayName("AC-RUN-4.1, 4.6 full app: a bad field returns 400 problem+json naming requirement and saves no run")
    void badFieldNamesTheFieldAndSavesNothing(String body) throws Exception {
        long before = runRepository.count();

        expectProblem(mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON).content(body)),
                400, "Bad Request")
                .andExpect(jsonPath("$.detail").value("Request validation failed."))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("requirement"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be blank"));

        assertThat(runRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-RUN-4.2, 4.6 full app: malformed JSON returns 400 problem+json and saves no run")
    void malformedJsonSavesNothing() throws Exception {
        long before = runRepository.count();

        expectProblem(mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON).content("{")),
                400, "Bad Request");

        assertThat(runRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-RUN-4.2, 4.6 full app: a missing body returns 400 problem+json and saves no run")
    void missingBodySavesNothing() throws Exception {
        long before = runRepository.count();

        expectProblem(mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON)),
                400, "Bad Request");

        assertThat(runRepository.count()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/runs", "/v2/runs", "/nothing/here"})
    @DisplayName("AC-RUN-4.3, 4.6 full app: a GET to an unknown path returns 404 problem+json")
    void unknownPathGetReturnsProblem(String path) throws Exception {
        expectProblem(mvc.perform(get(path)), 404, "Not Found");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/runs", "/v2/runs", "/nothing/here"})
    @DisplayName("AC-RUN-4.3, 4.6 full app: a valid create request to an unknown path returns 404 problem+json and saves no run")
    void unknownPathPostReturnsProblemAndSavesNothing(String path) throws Exception {
        long before = runRepository.count();

        expectProblem(mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)),
                404, "Not Found");

        assertThat(runRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-RUN-4.4, 4.6 full app: an unsupported content type returns 415 problem+json and saves no run")
    void unsupportedContentTypeSavesNothing() throws Exception {
        long before = runRepository.count();

        expectProblem(mvc.perform(post("/v1/runs").contentType(MediaType.TEXT_PLAIN).content(VALID_BODY)),
                415, "Unsupported Media Type");

        assertThat(runRepository.count()).isEqualTo(before);
    }
}
