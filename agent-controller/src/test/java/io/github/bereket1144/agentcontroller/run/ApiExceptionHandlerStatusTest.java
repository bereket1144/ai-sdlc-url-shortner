package io.github.bereket1144.agentcontroller.run;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// The catch-all in ApiExceptionHandler must not hide a status that the framework or the code chose on purpose.
// The controller below exists only in this test and throws the two cases: an explicit status and a plain failure.
@WebMvcTest(ApiExceptionHandlerStatusTest.ThrowingController.class)
@Import(ApiExceptionHandlerStatusTest.ThrowingController.class)
class ApiExceptionHandlerStatusTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @RestController
    static class ThrowingController {

        @GetMapping("/test-only/conflict")
        String conflict() {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }

        @GetMapping("/test-only/failure")
        String failure() {
            throw new RuntimeException("boom");
        }
    }

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("AC-RUN-4.6 an explicit ResponseStatusException keeps its status and is served as problem+json")
    void explicitStatusSurvivesTheCatchAll() throws Exception {
        mvc.perform(get("/test-only/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ApiExceptionHandler.TYPE_PREFIX + 409))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("AC-RUN-4.5 a plain RuntimeException still returns the generic 500")
    void plainFailureIsGeneric500() throws Exception {
        mvc.perform(get("/test-only/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ApiExceptionHandler.TYPE_PREFIX + 500))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value(ApiExceptionHandler.INTERNAL_ERROR_DETAIL));
    }
}
