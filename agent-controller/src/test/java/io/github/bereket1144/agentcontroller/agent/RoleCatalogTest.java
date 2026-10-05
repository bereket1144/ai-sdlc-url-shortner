package io.github.bereket1144.agentcontroller.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class RoleCatalogTest {

    @Test
    @DisplayName("AC-AGT-1.1, 1.6 startup loads exactly eight role definitions and makes them available by ID")
    void loadsAtStartup() {
        try (var context = new AnnotationConfigApplicationContext(RoleCatalog.class)) {
            RoleCatalog catalog = context.getBean(RoleCatalog.class);

            assertThat(catalog.all()).hasSize(8);
            assertThat(catalog.get("requirements").id()).isEqualTo("requirements");
            assertThat(catalog.get("impactAnalysis").id()).isEqualTo("impactAnalysis");
            assertThat(catalog.get("design").id()).isEqualTo("design");
            assertThat(catalog.get("planning").id()).isEqualTo("planning");
            assertThat(catalog.get("development").id()).isEqualTo("development");
            assertThat(catalog.get("qa").id()).isEqualTo("qa");
            assertThat(catalog.get("review").id()).isEqualTo("review");
            assertThat(catalog.get("documentation").id()).isEqualTo("documentation");
        }
    }

    @Test
    @DisplayName("AC-AGT-1.1 startup rejects a missing role by name")
    void rejectsMissingRole() throws IOException {
        Resource[] resources = Arrays.stream(roleFiles())
                .filter(resource -> !resource.getFilename().equals("qa.properties"))
                .toArray(Resource[]::new);

        assertThatThrownBy(() -> new RoleCatalog(resources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing role: qa");
    }

    @Test
    @DisplayName("AC-AGT-1.1 startup rejects an unknown extra role by name")
    void rejectsUnknownRole() throws IOException {
        Resource[] resources = Arrays.copyOf(roleFiles(), 9);
        resources[8] = namedResource("unknown.properties", "version=1");

        assertThatThrownBy(() -> new RoleCatalog(resources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown role: unknown");
    }

    @Test
    @DisplayName("AC-AGT-1.1 startup rejects a duplicate role by name")
    void rejectsDuplicateRole() throws IOException {
        Resource[] resources = Arrays.copyOf(roleFiles(), 9);
        resources[8] = resources[0];

        assertThatThrownBy(() -> new RoleCatalog(resources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate role:");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requiredFields")
    @DisplayName("AC-AGT-1.2 startup names any missing or blank role part")
    void rejectsBlankPart(String field) throws IOException {
        Resource[] resources = replaceRole(roleFiles(), "requirements", field, "  ");

        assertThatThrownBy(() -> new RoleCatalog(resources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Role requirements")
                .hasMessageContaining(field);
    }

    @Test
    @DisplayName("AC-AGT-1.2 startup names a missing role part")
    void rejectsMissingPart() throws IOException {
        Resource[] resources = replaceRole(roleFiles(), "requirements", "mission", null);

        assertThatThrownBy(() -> new RoleCatalog(resources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Role requirements is missing mission");
    }

    @Test
    @DisplayName("AC-AGT-1.2 a role needs at least three named working standards")
    void rejectsTooFewStandards() throws IOException {
        Resource[] resources = replaceRole(roleFiles(), "requirements", "standards", "one; two");

        assertThatThrownBy(() -> new RoleCatalog(resources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Role requirements needs at least three named standards");
    }

    @Test
    @DisplayName("AC-AGT-1.2 an approval flag must say true or false")
    void rejectsInvalidApprovalFlag() throws IOException {
        Resource[] resources = replaceRole(roleFiles(), "requirements", "output.approvalRequired", "maybe");

        assertThatThrownBy(() -> new RoleCatalog(resources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Role requirements has invalid output.approvalRequired");
    }

    @Test
    @DisplayName("AC-AGT-1.3 two roles cannot have the same identity text")
    void rejectsSharedIdentity() throws IOException {
        String identity = new RoleCatalog(roleFiles()).get("requirements").identity();
        Resource[] resources = replaceRole(roleFiles(), "design", "identity", identity);

        assertThatThrownBy(() -> new RoleCatalog(resources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requirements")
                .hasMessageContaining("design")
                .hasMessageContaining("share the same identity");
    }

    @Test
    @DisplayName("AC-AGT-1.4 role files contain no model, provider, window, product, or domain terms")
    void definitionsRemainModelAndProductNeutral() throws IOException {
        Pattern forbidden = Pattern.compile(
                "shortener|calculator|ollama|qwen|llama|claude|gpt|anthropic|openai|context.window|tokens",
                Pattern.CASE_INSENSITIVE);

        for (Resource resource : roleFiles()) {
            String contents = read(resource);
            assertThat(forbidden.matcher(contents).find()).as(resource.getFilename()).isFalse();
        }
    }

    @Test
    @DisplayName("AC-AGT-1.5 an unknown input kind fails and names its role and kind")
    void rejectsUnknownInputKind() throws IOException {
        Resource[] resources = replaceRole(roleFiles(), "requirements", "inputs", "UNKNOWN_KIND");

        assertThatThrownBy(() -> new RoleCatalog(resources))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Role requirements has unknown input kind: UNKNOWN_KIND");
    }

    @Test
    @DisplayName("AC-AGT-1.6 loaded role definitions cannot be changed")
    void definitionsAreReadOnly() throws IOException {
        RoleCatalog catalog = new RoleCatalog(roleFiles());

        assertThatThrownBy(() -> catalog.all().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.get("requirements").standards().add("another standard"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.get("requirements").inputs().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.get("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown role: unknown");
    }

    private static Stream<String> requiredFields() {
        return Stream.of("identity", "mission", "voice", "standards", "inputs", "output.kind",
                "output.structure", "output.approvalRequired", "boundaries", "uncertainty", "selfCheck", "version");
    }

    private static Resource[] roleFiles() throws IOException {
        return new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/agent-roles/*.properties");
    }

    private static Resource[] replaceRole(Resource[] resources, String roleId, String field, String value)
            throws IOException {
        Resource[] changed = Arrays.copyOf(resources, resources.length);
        for (int index = 0; index < changed.length; index++) {
            if (changed[index].getFilename().equals(roleId + ".properties")) {
                String original = read(changed[index]);
                String replacement = original.replaceFirst("(?m)^" + Pattern.quote(field) + "=.*$",
                        Matcher.quoteReplacement(value == null ? "" : field + "=" + value));
                assertThat(replacement).isNotEqualTo(original);
                changed[index] = namedResource(roleId + ".properties", replacement);
                return changed;
            }
        }
        throw new IllegalArgumentException("Missing role file in test: " + roleId);
    }

    private static Resource namedResource(String filename, String contents) {
        return new ByteArrayResource(contents.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private static String read(Resource resource) throws IOException {
        try (var input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
