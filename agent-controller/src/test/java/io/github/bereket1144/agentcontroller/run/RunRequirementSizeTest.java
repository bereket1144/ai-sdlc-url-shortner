package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;

// RUN-5 on the full application with the real PostgreSQL schema: the requirement length limit (AC-RUN-5.1, 5.2).
// Transactional: MockMvc runs in the test thread, so rows a test creates roll back and do not leak into other tests.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RunRequirementSizeTest {

    private static final int MAX_CHARACTERS = 20_000;
    private static final String EMOJI = "😀"; // one character (one code point), two UTF-16 units

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RunRepository runRepository;

    private ResultActions create(byte[] body) throws Exception {
        return mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions createWithRequirement(String requirement) throws Exception {
        return create(("{\"requirement\":\"" + requirement + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private void expectTooLongRequirement(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value(ApiExceptionHandler.TYPE_PREFIX + 400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Request validation failed."))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("requirement"));
    }

    @Test
    @DisplayName("AC-RUN-5.1 a requirement of exactly 20,000 characters is accepted with 201 and stored unchanged")
    void requirementAtTheLimitIsAccepted() throws Exception {
        String requirement = "a".repeat(MAX_CHARACTERS);
        long before = runRepository.count();

        String json = createWithRequirement(requirement)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(JsonPath.read(json, "$.id"));
        assertThat(runRepository.count()).isEqualTo(before + 1);
        assertThat(runRepository.findById(id)).get().extracting(Run::getRequirement).isEqualTo(requirement);
    }

    @Test
    @DisplayName("AC-RUN-5.2 a requirement of 20,001 characters returns 400 problem+json naming requirement and saves no run")
    void requirementOverTheLimitIsRefusedAndSavesNothing() throws Exception {
        long before = runRepository.count();

        expectTooLongRequirement(createWithRequirement("a".repeat(MAX_CHARACTERS + 1)));

        assertThat(runRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-RUN-5.1 characters are code points: 19,999 letters and one emoji (20,001 UTF-16 units) is accepted")
    void emojiCountsAsOneCharacterAtTheLimit() throws Exception {
        String requirement = "a".repeat(MAX_CHARACTERS - 1) + EMOJI;
        assertThat(requirement.length()).isEqualTo(MAX_CHARACTERS + 1);
        assertThat(requirement.codePointCount(0, requirement.length())).isEqualTo(MAX_CHARACTERS);
        long before = runRepository.count();

        String json = createWithRequirement(requirement)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(JsonPath.read(json, "$.id"));
        assertThat(runRepository.count()).isEqualTo(before + 1);
        assertThat(runRepository.findById(id)).get().extracting(Run::getRequirement).isEqualTo(requirement);
    }

    @Test
    @DisplayName("AC-RUN-5.2 20,000 letters and one emoji is 20,001 characters and is refused, saving no run")
    void emojiOverTheLimitIsRefused() throws Exception {
        String requirement = "a".repeat(MAX_CHARACTERS) + EMOJI;
        long before = runRepository.count();

        expectTooLongRequirement(createWithRequirement(requirement));

        assertThat(runRepository.count()).isEqualTo(before);
    }
}
