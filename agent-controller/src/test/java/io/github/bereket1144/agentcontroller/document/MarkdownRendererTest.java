package io.github.bereket1144.agentcontroller.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MarkdownRendererTest {

    @Test
    @DisplayName("AC-AGT-21.1 any JSON value becomes Markdown: headings for fields, paragraphs, bullets, nested bullets, and code only for diagram sources")
    void rendersAnyJsonValueAsMarkdown() {
        String json = "{\"summary\":\"A line.\\nSecond line.\",\"count\":3,\"flag\":true,\"nothing\":null,"
                + "\"items\":[\"one\",\"two\\nmore\"],"
                + "\"stories\":[{\"id\":\"S-1\",\"criteria\":[\"a\",\"b\"],\"owner\":{\"name\":\"n\"}}],"
                + "\"diagrams\":[\"graph TD\\n  A-->B\"]}";

        String markdown = MarkdownRenderer.render("Title", JsonMapper.shared().readTree(json));

        assertThat(markdown).isEqualTo("""
                # Title

                ## summary

                A line.
                Second line.

                ## count

                3

                ## flag

                true

                ## nothing

                null

                ## items

                - one
                - two
                  more

                ## stories

                - **1**
                  - **id**: S-1
                  - **criteria**
                    - a
                    - b
                  - **owner**
                    - **name**: n

                ## diagrams

                ```mermaid
                graph TD
                  A-->B
                ```

                """);
        // A value that is not an object has no fields, so it follows the title directly.
        assertThat(MarkdownRenderer.render("List", JsonMapper.shared().readTree("[\"x\"]")))
                .isEqualTo("# List\n\n- x\n\n");
    }
}
