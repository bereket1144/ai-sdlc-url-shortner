package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// RUN-4: every error has the same RFC 9457 structure and the content type application/problem+json.
@WebMvcTest(RunController.class)
@ExtendWith(OutputCaptureExtension.class)
class RunErrorFormatTest {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String SECRET = "s3cr3t-db-password-42";

    @MockitoBean
    private RunService runService;

    @Autowired
    private MockMvc mvc;

    private static void expectProblem(ResultActions result, int status, String title) throws Exception {
        result.andExpect(status().is(status))
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
    @DisplayName("AC-RUN-4.1, 4.6 a bad field returns 400 problem+json that names the field requirement")
    void badFieldNamesTheField(String body) throws Exception {
        ResultActions result = mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON).content(body));

        expectProblem(result, 400, "Bad Request");
        result.andExpect(jsonPath("$.detail").value("Request validation failed."))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("requirement"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be blank"));
        verifyNoInteractions(runService);
    }

    @Test
    @DisplayName("AC-RUN-4.2, 4.6 malformed JSON returns 400 problem+json")
    void malformedJson() throws Exception {
        expectProblem(mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON).content("{")),
                400, "Bad Request");
        verifyNoInteractions(runService);
    }

    @Test
    @DisplayName("AC-RUN-4.2, 4.6 a missing body returns 400 problem+json")
    void missingBody() throws Exception {
        expectProblem(mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON)), 400, "Bad Request");
        verifyNoInteractions(runService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/runs", "/v2/runs", "/nothing/here"})
    @DisplayName("AC-RUN-4.3, 4.6 an unknown path returns 404 problem+json")
    void unknownPath(String path) throws Exception {
        expectProblem(mvc.perform(get(path)), 404, "Not Found");
    }

    @Test
    @DisplayName("AC-RUN-4.4, 4.6 an unsupported content type returns 415 problem+json")
    void unsupportedContentType() throws Exception {
        expectProblem(mvc.perform(post("/v1/runs").contentType(MediaType.TEXT_PLAIN).content("hello")),
                415, "Unsupported Media Type");
        verifyNoInteractions(runService);
    }

    @Test
    @DisplayName("AC-RUN-4.5, 4.6 an unexpected failure returns 500 problem+json with a generic detail and logs the real error")
    void unexpectedFailureIsGenericAndLogged(CapturedOutput output) throws Exception {
        when(runService.createRun(anyString())).thenThrow(new IllegalStateException("db password is " + SECRET));

        ResultActions result = mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirement\":\"Build a task tracker\"}"));

        expectProblem(result, 500, "Internal Server Error");
        result.andExpect(jsonPath("$.detail").value(ApiExceptionHandler.INTERNAL_ERROR_DETAIL));
        String body = result.andReturn().getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain(SECRET)
                .doesNotContain("IllegalStateException")
                .doesNotContain("java.lang")
                .doesNotContain("stackTrace")
                .doesNotContainPattern("\\bat [a-z]+\\.");

        // The real exception is not swallowed: it is logged server-side at ERROR with its message.
        assertThat(output.getOut() + output.getErr())
                .contains("ERROR")
                .contains("event=request_failed status=500")
                .contains("IllegalStateException")
                .contains(SECRET);
    }
}
