package io.github.bereket1144.agentcontroller.run;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;

// AC-RUN-4.6: every error response has the content type application/problem+json, whatever the client asks for in
// its Accept header (a browser sends text/html, a tool may send application/xml). Real application, real
// content negotiation.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RunErrorAcceptHeaderFullContextTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/html", "application/xml", "*/*"})
    @DisplayName("AC-RUN-4.6 full app: a bad POST body returns 400 problem+json whatever Accept says")
    void badBodyIsProblemJsonForAnyAccept(String accept) throws Exception {
        mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON).header("Accept", accept).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ApiExceptionHandler.TYPE_PREFIX + 400))
                .andExpect(jsonPath("$.status").value(400));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/html", "application/xml", "*/*"})
    @DisplayName("AC-RUN-4.6 full app: an unknown path returns 404 problem+json whatever Accept says")
    void unknownPathIsProblemJsonForAnyAccept(String accept) throws Exception {
        mvc.perform(get("/nothing/here").header("Accept", accept))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ApiExceptionHandler.TYPE_PREFIX + 404))
                .andExpect(jsonPath("$.status").value(404));
    }
}
