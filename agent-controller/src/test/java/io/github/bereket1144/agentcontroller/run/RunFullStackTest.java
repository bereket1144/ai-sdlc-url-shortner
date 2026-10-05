package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;

// The whole stack with a real PostgreSQL: a run is created over HTTP and read back over HTTP.
// Not @Transactional on purpose: the POST must really commit so that the GET reads the row from the database.
// The rows this class creates are deleted after each test.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RunFullStackTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RunRepository runRepository;

    private UUID createdId;

    @AfterEach
    void deleteCreatedRun() {
        if (createdId != null) {
            runRepository.deleteById(createdId);
        }
    }

    @Test
    @DisplayName("AC-RUN-1.2, 7.1, 7.2, AC-FLOW-1.6, AC-AGT-50.3, 50.4 a run created over HTTP from a body with only a requirement ignores a client id, is stored as GREENFIELD with no base run, is located at /v1/runs/<id> and reads back with its kind and state")
    void createdRunIsLocatedAndReadBack() throws Exception {
        UUID supplied = UUID.randomUUID();
        Instant before = Instant.now().minusSeconds(5);

        MvcResult created = mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + supplied + "\",\"requirement\":\"Build a task tracker\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        createdId = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));

        assertThat(createdId).isNotEqualTo(supplied);
        String location = created.getResponse().getHeader("Location");
        assertThat(location).isEqualTo("/v1/runs/" + createdId);

        String json = mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdId.toString()))
                .andExpect(jsonPath("$.requirement").value("Build a task tracker"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.kind").value("GREENFIELD"))
                .andExpect(jsonPath("$.baseRunId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(runRepository.findById(createdId).orElseThrow().getKind()).isEqualTo(RunKind.GREENFIELD);
        Instant createdAt = Instant.parse(JsonPath.read(json, "$.createdAt"));
        Instant updatedAt = Instant.parse(JsonPath.read(json, "$.updatedAt"));
        assertThat(createdAt).isAfter(before).isBeforeOrEqualTo(updatedAt);
        assertThat(updatedAt).isBeforeOrEqualTo(Instant.now().plusSeconds(5));
    }

    @Test
    @DisplayName("AC-RUN-7.4 reading an id that does not exist returns 404 problem+json with the service's own detail")
    void unknownIdReturnsProblem404() throws Exception {
        mvc.perform(get("/v1/runs/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value(ApiExceptionHandler.TYPE_PREFIX + 404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Run not found."));
    }
}
