package io.github.bereket1144.agentcontroller.run;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;

// SWAG-1 on the full application: the document is produced by the real library from the real controller and records.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ApiDocumentTest {

    private static final String DOCS = "/v3/api-docs";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("AC-SWAG-1.1 GET /v3/api-docs returns 200 JSON with an OpenAPI version and a title")
    void documentDeclaresVersionAndTitle() throws Exception {
        mvc.perform(get(DOCS))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.openapi").value(startsWith("3.")))
                .andExpect(jsonPath("$.info.title").isNotEmpty());
    }

    @Test
    @DisplayName("AC-SWAG-1.2 the document lists POST /v1/runs and GET /v1/runs/{id} and no other path")
    void documentListsOnlyTheRunEndpoints() throws Exception {
        mvc.perform(get(DOCS))
                .andExpect(jsonPath("$.paths.keys()", containsInAnyOrder("/v1/runs", "/v1/runs/{id}")))
                .andExpect(jsonPath("$.paths['/v1/runs'].keys()", containsInAnyOrder("post")))
                .andExpect(jsonPath("$.paths['/v1/runs/{id}'].keys()", containsInAnyOrder("get")));
    }

    @Test
    @DisplayName("AC-SWAG-1.3 the create request body is an object with a required requirement string of at most 20,000")
    void createRequestBodyIsDescribed() throws Exception {
        mvc.perform(get(DOCS))
                .andExpect(jsonPath("$.paths['/v1/runs'].post.requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/RunRequest"))
                .andExpect(jsonPath("$.components.schemas.RunRequest.type").value("object"))
                .andExpect(jsonPath("$.components.schemas.RunRequest.required", hasItem("requirement")))
                .andExpect(jsonPath("$.components.schemas.RunRequest.properties.requirement.type").value("string"))
                .andExpect(jsonPath("$.components.schemas.RunRequest.properties.requirement.maxLength")
                        .value(RunRequest.MAX_REQUIREMENT_CHARACTERS));
    }

    @Test
    @DisplayName("AC-SWAG-1.4 the success bodies list their fields, and status is limited to CREATED")
    void successBodiesAreDescribed() throws Exception {
        mvc.perform(get(DOCS))
                .andExpect(jsonPath("$.paths['/v1/runs'].post.responses['201'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/RunCreatedResponse"))
                .andExpect(jsonPath("$.components.schemas.RunCreatedResponse.properties.keys()",
                        containsInAnyOrder("id")))
                .andExpect(jsonPath("$.paths['/v1/runs/{id}'].get.responses['200'].content['*/*'].schema.$ref")
                        .value("#/components/schemas/RunResponse"))
                .andExpect(jsonPath("$.components.schemas.RunResponse.properties.keys()", containsInAnyOrder(
                        "id", "requirement", "status", "createdAt", "updatedAt", "version")))
                .andExpect(jsonPath("$.components.schemas.RunResponse.properties.status.enum",
                        containsInAnyOrder("CREATED")));
    }
}
