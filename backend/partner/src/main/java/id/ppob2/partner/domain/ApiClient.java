package id.ppob2.partner.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps to the `api_client` table, PRD Section 22.3. `secretHash` never holds plaintext. */
@Entity
@Table(name = "api_client")
public class ApiClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false, unique = true, length = 64)
    private String clientId;

    @Column(name = "partner_id", nullable = false)
    private Long partnerId;

    @Column(name = "secret_hash", nullable = false, length = 255)
    private String secretHash;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "rate_limit_per_min", nullable = false)
    private int rateLimitPerMin;

    @Column(name = "allowed_ip_cidr")
    private String allowedIpCidr;

    protected ApiClient() {
    }

    public ApiClient(String clientId, Long partnerId, String secretHash, String status, int rateLimitPerMin) {
        this.clientId = clientId;
        this.partnerId = partnerId;
        this.secretHash = secretHash;
        this.status = status;
        this.rateLimitPerMin = rateLimitPerMin;
    }

    public Long getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public Long getPartnerId() {
        return partnerId;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public String getStatus() {
        return status;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public int getRateLimitPerMin() {
        return rateLimitPerMin;
    }
}
