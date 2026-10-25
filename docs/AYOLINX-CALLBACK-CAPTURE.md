# Capturing a real Ayolinx sandbox callback

Goal: obtain evidence to close README go-live blocker 9 (inbound `amount` format) and to prove
`X-SIGNATURE` verification against a genuine signature. Nothing here touches production.

## What `webhook_event` holds

Since V30 each inbound callback row also has `raw_body` (the exact bytes received) and `raw_headers` (JSONB, minus
`Authorization`/`Cookie`), for accepted and rejected callbacks alike. `payload` stays JSONB and cannot reproduce the signed
bytes, so use `raw_body` for signature work. Rows from before V30 have neither. The raw body may contain payer data and
`webhook_event` has no retention job yet.

## 1. Prepare the order (sandbox only)

1. Run the app with `ppob2.payment.gateway=ayolinx` and the sandbox credentials from env/secret
   manager. Never commit them or paste them into chat.
2. Create orders whose billed amounts differ in digit count, e.g. **15000** and **125000**, so a
   thousands separator would show up. Note each order's `payment.amount` and `pg_reference`.
3. Pay each through the sandbox so Ayolinx fires the callback.

## 2. Read the callback fields from the database

```sql
SELECT e.id, e.received_at, e.status, e.dedup_key,
       e.payload -> 'amount'                       AS amount,
       e.payload ->> 'latestTransactionStatus'     AS status_code,
       e.payload ->> 'originalPartnerReferenceNo'  AS pg_reference,
       p.amount                                    AS billed_amount
FROM webhook_event e
LEFT JOIN payment p ON p.pg_reference = e.payload ->> 'originalPartnerReferenceNo'
WHERE e.source = 'AYOLINX' AND e.direction = 'INBOUND'
ORDER BY e.id DESC
LIMIT 10;
```

Check that `amount.value` matches `billed_amount` and note its exact shape (`"15000.00"`,
`"15,000.00"`, ...). `status = 'FAILED'` rows are callbacks that failed signature verification
or parsing; `RECEIVED` rows passed.

Also look at whether the payment became `SUCCESS` or an `AMOUNT_UNVERIFIED` /
`AMOUNT_MISMATCH` anomaly was opened. `AMOUNT_UNVERIFIED` means the format was not parseable.

## 3. Read the raw body and headers

```sql
SELECT id, received_at, status, raw_body, raw_headers
FROM webhook_event
WHERE source = 'AYOLINX' AND direction = 'INBOUND' AND raw_body IS NOT NULL
ORDER BY id DESC LIMIT 5;
```

Copy `raw_body` exactly. `raw_headers` should contain `X-SIGNATURE` and `X-TIMESTAMP` (names are stored as the app saw
them). If a row is missing, the callback never reached the app; check the proxy and the notification URL registered with
Ayolinx.

## 4. What to hand over

- Raw body (exact), `X-SIGNATURE`, `X-TIMESTAMP`.
- The path Ayolinx signs. Note the verifier uses `ppob2.payment.ayolinx.callback-route`
  (default `/v1/qr/qr-mpm-notify`), which is Ayolinx's documented path, **not** necessarily the
  URL of this app's endpoint. If verification fails, this is the first suspect.
- Billed amount for each captured order.
- The sandbox public key (PEM). It is public; never send a private key or client secret.
- Mask personal data (`customerNumber`) but leave `amount`, status and reference fields intact.

Place the files under `backend/payment/src/test/resources/ayolinx/` or paste them in chat.

## 5. What happens next

1. A test verifies the real signature with the sandbox public key.
2. `AyolinxCallbackPayloadTest` pins the observed `amount` format; the "unverified" comments and
   README blocker 9 are updated.
3. The reject `responseCode` values in `AyolinxWebhookController` are checked against Ayolinx's
   expectation, since they are placeholders today.
