package io.github.bereket1144.agentcontroller.document;

import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * Turns a JSON value into Markdown with plain code, so the same value always gives the same text. Each field of the
 * top level object is a section with a heading. Inside a section a text is a paragraph, an array is a list of bullets
 * and an object is a list of bullets that each start with the field name in bold. A list or an object inside a list is
 * a bullet with its own bullets one level deeper, labelled by its field name or its position. The only code fences are
 * around the sources of a section called {@value #DIAGRAMS}. Nothing is escaped and nothing is added that the value
 * does not hold.
 */
final class MarkdownRenderer {

    /** The one field whose texts are diagram sources, so they are written as code in the Mermaid notation. */
    static final String DIAGRAMS = "diagrams";

    private MarkdownRenderer() { }

    /** A document: the title as the top heading, then every field of an object as a section, or the value itself. */
    static String render(String title, JsonNode value) {
        StringBuilder out = new StringBuilder("# ").append(title).append("\n\n");
        if (value.isObject()) {
            for (Map.Entry<String, JsonNode> field : value.properties()) {
                out.append(section(field.getKey(), field.getValue()));
            }
        } else {
            out.append(body(value));
        }
        return out.toString();
    }

    /** One section: the heading, then the value as a paragraph or as bullets. */
    static String section(String heading, JsonNode value) {
        StringBuilder out = new StringBuilder("## ").append(heading).append("\n\n");
        if (heading.equals(DIAGRAMS) && value.isArray()) {
            for (JsonNode source : value) {
                out.append("```mermaid\n").append(text(source)).append("\n```\n\n");
            }
        } else {
            out.append(body(value));
        }
        return out.toString();
    }

    /** A string as it is; a number, a boolean or null as its JSON text. */
    static String text(JsonNode value) {
        return value.isString() ? value.asString() : value.toString();
    }

    private static String body(JsonNode value) {
        if (!value.isContainer()) {
            return text(value) + "\n\n";
        }
        StringBuilder out = new StringBuilder();
        bullets(out, value, 0);
        return out.append("\n").toString();
    }

    private static void bullets(StringBuilder out, JsonNode container, int level) {
        if (container.isArray()) {
            int position = 1;
            for (JsonNode element : container) {
                // A text in an array needs no label; an array or an object in an array is labelled by its position.
                bullet(out, level, element.isContainer() ? String.valueOf(position) : null, element);
                position++;
            }
        } else {
            for (Map.Entry<String, JsonNode> field : container.properties()) {
                bullet(out, level, field.getKey(), field.getValue());
            }
        }
    }

    private static void bullet(StringBuilder out, int level, String label, JsonNode value) {
        String indent = "  ".repeat(level);
        out.append(indent).append("- ");
        if (value.isContainer()) {
            out.append("**").append(label).append("**\n");
            bullets(out, value, level + 1);
            return;
        }
        if (label != null) {
            out.append("**").append(label).append("**: ");
        }
        // The lines after the first stay inside the bullet when they are indented under its text.
        out.append(text(value).replace("\n", "\n" + indent + "  ")).append("\n");
    }
}
