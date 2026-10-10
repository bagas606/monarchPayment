package id.ppob1.sim.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * One answer from PPOB2 — or the explicit absence of one.
 *
 * <p>The three-way distinction is the whole reason this is a type rather than a status code: a
 * {@code 2xx}, a {@code 4xx} and <em>no answer at all</em> demand different handling, and the
 * common mistake is collapsing the third into the second. A {@code 422} means PPOB2 definitely did
 * not create an order; a timeout means PPOB1 has no idea, and treating it as a refusal is how a
 * reseller ends up with no record of an order its customer has already paid for.
 *
 * @param httpStatus HTTP status, or {@code 0} when no answer was received
 * @param body raw response body, or {@code null}
 * @param transportError why no answer arrived; {@code null} whenever one did
 */
public record Ppob2Response(int httpStatus, String body, String transportError) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static Ppob2Response noAnswer(String transportError) {
        return new Ppob2Response(0, null, transportError);
    }

    /** PPOB2 answered, whatever it said. */
    public boolean answered() {
        return transportError == null;
    }

    public boolean isSuccess() {
        return answered() && httpStatus >= 200 && httpStatus < 300;
    }

    /** Parsed body, or {@code null} if there is none or it is not JSON. Never throws: a response
     * body that does not parse is a thing to record about PPOB2, not a crash in PPOB1. */
    public JsonNode json() {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    public String text(String field) {
        JsonNode node = json();
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        return node.get(field).asText();
    }

    /** {@code error_code} from PPOB2's standard Section 50.1 error envelope, falling back to the
     * status code so a caller always has something to record — an error with no code at all is
     * still an error PPOB1 must be able to name. */
    public String errorCode() {
        String code = text("error_code");
        return code != null ? code : "HTTP_" + httpStatus;
    }

    public String errorMessage() {
        String message = text("message");
        return message != null ? message : body;
    }
}
