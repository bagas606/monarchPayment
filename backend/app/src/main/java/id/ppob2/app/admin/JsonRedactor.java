package id.ppob2.app.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Masks secret-looking values in JSON that is shown to an admin.
 *
 * <p>{@code audit_log.before_state/after_state} are free-form JSON written by many modules. Nothing
 * stored there today is a credential, but the column's contract is "whatever the caller found useful",
 * so the read side must not depend on every future writer being careful. Matching is by key name, at
 * any depth, case-insensitively. It is a backstop, not a licence to write secrets into the audit log.
 */
public final class JsonRedactor {

    static final String MASK = "***";
    private static final Pattern SECRET_KEY =
            Pattern.compile("(?i).*(secret|password|passwd|hash|token|signature|private_?key|api_?key|otp|mfa_?code).*");

    private final ObjectMapper mapper;

    public JsonRedactor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** @return the redacted tree, {@code null} for SQL NULL, or a text node holding the input if it is not valid JSON. */
    public JsonNode redact(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode tree = mapper.readTree(json);
            mask(tree);
            return tree;
        } catch (Exception e) {
            // Never echo unparseable content: it cannot be inspected for secrets.
            return mapper.getNodeFactory().textNode("[unparseable]");
        }
    }

    private void mask(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (SECRET_KEY.matcher(field.getKey()).matches() && !field.getValue().isNull()) {
                    field.setValue(mapper.getNodeFactory().textNode(MASK));
                } else {
                    mask(field.getValue());
                }
            }
        } else if (node.isArray()) {
            node.forEach(this::mask);
        }
    }
}
