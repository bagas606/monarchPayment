# Capturing a real Ayolinx sandbox callback

Goal: obtain evidence to close README go-live blocker 9 (inbound `amount` format) and to prove
`X-SIGNATURE` verification against a genuine signature. Nothing here touches production.

## What `webhook_event` can and cannot give you

| Need | In `webhook_event`? |
|---|---|
| Field values, incl. the `amount.value` string | Yes. `payload` is `jsonb`, and string values are kept exactly. |
| Exact raw body bytes | **No.** `jsonb` drops whitespace and reorders keys. |
| `X-SIGNATURE`, `X-TIMESTAMP` headers | **No.** Headers are not stored. Only a *rejected* callback logs those two (`log.warn`). |

So the table is enough for the `amount` format but **not** for signature verification. Signature
needs the raw bytes and headers, captured outside the database (step 3).

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

## 3. Capture raw body and headers for the signature check

Pick one:

- **Reverse proxy log (no code change).** Temporarily log the request for
  `/internal/webhooks/ayolinx` at the proxy in front of the app, with method, the exact path,
  the `X-SIGNATURE` and `X-TIMESTAMP` headers and the raw body. Remove it afterwards.
- **A rejected callback.** While the public key is unset or wrong, the app logs
  `Ayolinx callback signature verification failed. X-SIGNATURE=... X-TIMESTAMP=...` and stores
  the body. That gives headers but still not the exact raw bytes.
- **Ayolinx dashboard / support.** If the sandbox portal shows the delivered request, copy it
  from there.

Keep the body exactly as received, with no pretty-printing.

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
