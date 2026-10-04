package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;

// RUN-4.5 on the full application: the database fails underneath the real controller, service and advice.
// The repository is the only mocked bean; it throws what a lost database connection would throw. This needs its
// own class because replacing a bean gives the class its own application context.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class RunUnexpectedErrorFullContextTest {

    private static final String SECRET = "jdbc:postgresql://db-host:5432/prod?password=s3cr3t-42";

    @MockitoBean
    private RunRepository runRepository;

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("AC-RUN-4.5, 4.6 full app: a database failure returns 500 problem+json with a generic detail and logs the real error")
    void databaseFailureIsGenericAndLogged(CapturedOutput output) throws Exception {
        when(runRepository.save(any(Run.class))).thenThrow(new DataAccessResourceFailureException(SECRET));

        ResultActions result = mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirement\":\"Build a task tracker\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value(ApiExceptionHandler.TYPE_PREFIX + 500))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value(ApiExceptionHandler.INTERNAL_ERROR_DETAIL));

        String body = result.andReturn().getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain(SECRET)
                .doesNotContain("password")
                .doesNotContain("DataAccessResourceFailureException")
                .doesNotContain("org.springframework")
                .doesNotContain("stackTrace")
                .doesNotContainPattern("\\bat [a-z]+\\.");

        assertThat(output.getOut() + output.getErr())
                .contains("event=request_failed status=500")
                .contains("DataAccessResourceFailureException")
                .contains(SECRET);
    }
}
