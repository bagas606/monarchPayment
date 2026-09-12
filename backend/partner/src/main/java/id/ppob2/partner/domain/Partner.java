package id.ppob2.partner.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps to the `partner` table, PRD Section 22.2. `channelId` is a plain FK reference (no
 * cross-module entity join) — the `channel` module is not a compile dependency of `partner`,
 * consistent with the allowed-dependency graph in Section 20.2. */
@Entity
@Table(name = "partner")
public class Partner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "channel_id")
    private Long channelId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "contract_ref", length = 128)
    private String contractRef;

    /** Section 23.8's "PPOB1's registered secret" for signing outbound webhooks — not part of
     * Section 22.2's documented schema; see the V16 migration comment for why this is the
     * reversible signing secret itself, same caveat as {@code api_client.secret_hash}. */
    @Column(name = "webhook_secret", length = 255)
    private String webhookSecret;

    protected Partner() {
    }

    public Partner(String code, String name, Long channelId, String status, String contractRef) {
        this.code = code;
        this.name = name;
        this.channelId = channelId;
        this.status = status;
        this.contractRef = contractRef;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Long getChannelId() {
        return channelId;
    }

    public String getStatus() {
        return status;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }
}
