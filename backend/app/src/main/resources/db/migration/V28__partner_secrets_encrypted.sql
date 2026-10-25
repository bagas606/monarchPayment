-- Partner-side signing secrets are now ciphertext, not the secret itself.
--
-- PRD Section 22.3 names api_client.secret_hash and says the secret is "never stored", but Section
-- 23.2's HMAC scheme needs the server to reproduce the client's MAC, which a one-way hash cannot do;
-- until now the column therefore held the raw secret (flagged as a go-live blocker). partner.webhook_secret
-- (V16, Section 23.8) had the same property. Both columns are renamed to say what they now hold and
-- carry the SecretVault format `v1.<keyId>.<base64(iv||ciphertext+tag)>`, bound to the row they belong to.
--
-- Values written before this migration are plaintext. They are WIPED, not converted: SQL has no access to
-- the key, and leaving them in place would keep the exposure this change exists to remove. A wiped
-- credential fails closed (401 for the client, no outbound webhook with an error logged) until an operator
-- provisions it through PUT /admin/api-clients/{clientId}/secret or PUT /admin/partners/{code}/webhook-secret.
ALTER TABLE api_client RENAME COLUMN secret_hash TO secret_ciphertext;
ALTER TABLE api_client ALTER COLUMN secret_ciphertext TYPE TEXT;
ALTER TABLE api_client ALTER COLUMN secret_ciphertext DROP NOT NULL;
UPDATE api_client SET secret_ciphertext = NULL;

ALTER TABLE partner RENAME COLUMN webhook_secret TO webhook_secret_ciphertext;
ALTER TABLE partner ALTER COLUMN webhook_secret_ciphertext TYPE TEXT;
UPDATE partner SET webhook_secret_ciphertext = NULL;

-- Provisioning is a credential operation, so it gets its own permission (SUPER_ADMIN only), granted
-- explicitly per V19's standing instruction.
INSERT INTO permission (code) VALUES ('partner_secret:manage');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM permission p, role r
WHERE r.code = 'SUPER_ADMIN' AND p.code = 'partner_secret:manage';
