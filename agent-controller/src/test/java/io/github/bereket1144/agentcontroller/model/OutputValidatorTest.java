package io.github.bereket1144.agentcontroller.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class OutputValidatorTest {

    private final OutputValidator validator;

    OutputValidatorTest() throws IOException {
        validator = new OutputValidator(new RoleCatalog(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/agent-roles/*.properties")), List.of(new RequirementsStoryIdsCheck()));
    }

    @Test
    @DisplayName("AC-AGT-5.1 a matching object is accepted and undeclared fields are ignored")
    void acceptsDeclaredFieldsOnly() {
        OutputValidator.Validation result = validator.validate("requirements", """
                {"stories":[],"openQuestions":[],"assumptions":[],"modelComment":"ignore me"}
                """);

        assertThat(result.valid()).isTrue();
        assertThat(result.messages()).isEmpty();
        assertThat(result.value().propertyNames()).containsExactlyInAnyOrder(
                "stories", "openQuestions", "assumptions");
    }

    @Test
    @DisplayName("AC-AGT-5.1 missing, blank, and wrongly typed required fields are rejected together")
    void reportsAllFieldErrors() {
        OutputValidator.Validation result = validator.validate("development", """
                {"changes":"wrong","rationale":"  ","affectedCriteria":[],"errorHandling":null}
                """);

        assertThat(result.valid()).isFalse();
        assertThat(result.value()).isNull();
        assertThat(result.messages()).containsExactly(
                "Wrong type for field changes: expected ARRAY",
                "Blank field: rationale",
                "Blank field: errorHandling",
                "Missing required field: verificationNotes");
    }

    @Test
    @DisplayName("AC-AGT-5.1 optional fields may be absent but must have their declared type when present")
    void checksOptionalFieldWhenPresent() {
        String answer = """
                {"proposedTests":[],"criterionMapping":{},"observedEvidence":[],"coverageGaps":[]%s}
                """;

        assertThat(validator.validate("qa", answer.formatted("")).valid()).isTrue();
        OutputValidator.Validation wrong = validator.validate("qa", answer.formatted(",\"failureDetails\":7"));
        assertThat(wrong.messages()).containsExactly("Wrong type for field failureDetails: expected ARRAY");
    }

    @Test
    @DisplayName("AC-AGT-5.3 a requirements-specific check rejects repeated story ids")
    void rejectsRepeatedStoryIds() {
        OutputValidator.Validation result = validator.validate("requirements", """
                {"stories":[{"id":"S-1"},{"id":"S-1"}],"openQuestions":[],"assumptions":[]}
                """);

        assertThat(result.valid()).isFalse();
        assertThat(result.value()).isNull();
        assertThat(result.messages()).containsExactly("Duplicate story id: S-1");
    }

    @Test
    @DisplayName("AC-AGT-5.2 extracts one JSON object through a code fence and surrounding prose")
    void extractsWrappedObject() {
        OutputValidator.Validation result = validator.validate("requirements", """
                Here is the result (draft {not JSON} removed):
                ```json
                {"stories":[{"title":"nested { braces } and \\"quotes\\""}],"openQuestions":[],"assumptions":[]}
                ```
                Ready for review.
                """);

        assertThat(result.valid()).isTrue();
        assertThat(result.value().get("stories").get(0).get("title").asString())
                .isEqualTo("nested { braces } and \"quotes\"");
    }

    @ParameterizedTest
    @CsvSource({
            "'No structured answer',Answer contains no JSON object",
            "'{} {}',Answer contains more than one JSON object",
            "'{\"stories\": [}',Malformed JSON object",
            "'{\"stories\": []',Incomplete JSON object"
    })
    @DisplayName("AC-AGT-5.2 answers with no single complete JSON object are rejected")
    void rejectsMissingMultipleOrMalformedObjects(String answer, String message) {
        OutputValidator.Validation result = validator.validate("requirements", answer);

        assertThat(result.valid()).isFalse();
        assertThat(result.value()).isNull();
        assertThat(result.messages()).contains(message);
    }

    private static final String ALL_TYPES = "{\"title\":\"x\",\"count\":1,\"flag\":true,\"details\":{},\"items\":[]}";

    @Test
    @DisplayName("AC-AGT-5.1 a field of every declared type is accepted when it has that type")
    void acceptsEveryDeclaredType() throws IOException {
        OutputValidator.Validation result = typedValidator().validate("qa", ALL_TYPES);

        assertThat(result.valid()).isTrue();
        assertThat(result.value().propertyNames()).containsExactlyInAnyOrder(
                "title", "count", "flag", "details", "items");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {"title":7,"count":1,"flag":true,"details":{},"items":[]} | title | STRING
            {"title":"x","count":"seven","flag":true,"details":{},"items":[]} | count | NUMBER
            {"title":"x","count":1,"flag":"yes","details":{},"items":[]} | flag | BOOLEAN
            {"title":"x","count":1,"flag":true,"details":[],"items":[]} | details | OBJECT
            {"title":"x","count":1,"flag":true,"details":{},"items":{}} | items | ARRAY
            """)
    @DisplayName("AC-AGT-5.1 a field with the wrong JSON type is rejected for every declared type")
    void rejectsTheWrongTypeForEveryDeclaredType(String answer, String field, String type) throws IOException {
        OutputValidator.Validation result = typedValidator().validate("qa", answer);

        assertThat(result.valid()).isFalse();
        assertThat(result.messages()).containsExactly("Wrong type for field " + field + ": expected " + type);
    }

    /** The real role files declare no number or boolean field, so this swaps in a QA role that does. */
    private static OutputValidator typedValidator() throws IOException {
        Resource[] real = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/agent-roles/*.properties");
        Resource[] typed = Arrays.stream(real)
                .map(resource -> "qa.properties".equals(resource.getFilename()) ? withTypedFields(resource) : resource)
                .toArray(Resource[]::new);
        return new OutputValidator(new RoleCatalog(typed), List.of());
    }

    private static Resource withTypedFields(Resource qa) {
        try (var input = qa.getInputStream()) {
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8).replaceFirst(
                    "(?m)^output\\.fields=.*$", Matcher.quoteReplacement("output.fields=title:STRING:required,"
                            + "count:NUMBER:required,flag:BOOLEAN:required,details:OBJECT:required,items:ARRAY:required"));
            return new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)) {
                @Override
                public String getFilename() {
                    return "qa.properties";
                }
            };
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
