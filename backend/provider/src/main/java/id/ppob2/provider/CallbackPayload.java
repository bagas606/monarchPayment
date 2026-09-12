package id.ppob2.provider;

/** Section 27.1's optional {@code handleCallback(CallbackPayload payload)} — provider-dependent,
 * not every provider pushes callbacks. Unused this slice (no inbound provider webhook endpoint
 * exists), kept for interface fidelity only. */
public record CallbackPayload(String rawBody) {
}
