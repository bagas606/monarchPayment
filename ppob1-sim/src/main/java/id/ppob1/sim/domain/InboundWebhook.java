package id.ppob1.sim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One received Section 23.8 callback. Maps to {@code ppob1_inbound_webhook}; append-only. */
@Entity
@Table(name = "ppob1_inbound_webhook")
public class InboundWebhook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", length = 64)
    private String eventType;

    @Column(name = "ppob2_order_id", length = 64)
    private String ppob2OrderId;

    @Column(name = "state", length = 32)
    private String state;

    @Column(name = "nonce", length = 128)
    private String nonce;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, length = 32)
    private WebhookVerdict verdict;

    /** The exact bytes received. Kept because it is the only thing that can settle a signature
     * dispute after the fact: the signature covers this string, so any re-serialization of a
     * parsed object would produce a different hash and prove nothing. */
    @Column(name = "raw_body", nullable = false)
    private String rawBody;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected InboundWebhook() {
    }

    public InboundWebhook(String eventType, String ppob2OrderId, String state, String nonce,
                           boolean signatureValid, WebhookVerdict verdict, String rawBody) {
        this.eventType = eventType;
        this.ppob2OrderId = ppob2OrderId;
        this.state = state;
        this.nonce = nonce;
        this.signatureValid = signatureValid;
        this.verdict = verdict;
        this.rawBody = rawBody;
        this.receivedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPpob2OrderId() {
        return ppob2OrderId;
    }

    public String getState() {
        return state;
    }

    public String getNonce() {
        return nonce;
    }

    public boolean isSignatureValid() {
        return signatureValid;
    }

    public WebhookVerdict getVerdict() {
        return verdict;
    }

    public String getRawBody() {
        return rawBody;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
