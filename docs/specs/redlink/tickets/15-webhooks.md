# 15 — Webhooks

Status: ready-for-human
Source spec: ../SPEC.md §8 (Webhooks), §14.5, §14.8

## Scope

Other systems get notified by an HTTP POST when something happens, instead of polling or holding
an SSE stream open.

### API (any authenticated user; every webhook belongs to its creator)
- `POST /api/v1/webhooks` `{url, events[]}` → `201`, `Location`, body with the **secret, shown
  only this once** (`Cache-Control: no-store`). Max 10 per user → `422 webhook-limit`.
- `GET /api/v1/webhooks`, `GET /api/v1/webhooks/{id}` (never the secret),
  `DELETE /api/v1/webhooks/{id}` → `204`.
- `GET /api/v1/webhooks/{id}/deliveries?page=&size=`: delivery log (status, attempts, last HTTP
  status / error).
- `POST /api/v1/webhooks/{id}/ping` → `202`: sends a test `ping` delivery.
- Someone else's webhook → `404` (its existence is not revealed). Ids are random UUIDs.

### Events
`command.completed`, `command.failed` (also refused by the rover or rejected on the uplink),
`rover.safe_mode` (on entering it), `rover.battery_low` (on falling below 20 %), `ping`.
Body: `{eventId, event, occurredAt, data}`, `data` = the command or rover as the API shows it.

### Delivery (same pattern as the link, SPEC §4)
- Outbox: deliveries are inserted in the **same transaction** as the change, one per subscribed
  active webhook (fan-out in one `INSERT ... SELECT`).
- Dispatcher: claim with lease (`FOR UPDATE SKIP LOCKED`), POST outside any transaction,
  record. 2xx → `DELIVERED`; `410 Gone` → `FAILED` and the webhook is deactivated; anything else
  → retry 2 s, 4 s … ≤ 5 min, at most 8 attempts, then `FAILED`.
- Headers: `X-RedLink-Signature: t=…,v1=…` (HMAC-SHA256 of `t.body` with the webhook's
  secret, the same scheme as the link), `X-RedLink-Event`, `X-RedLink-Delivery` (dedupe id),
  `User-Agent: RedLink-Webhooks/1`.

### SSRF protection (`WebhookUrlPolicy`)
- `https` only (http allowed only with `allow-http`, dev); no user-info; host required.
- The host is resolved and **every** address must be public: no loopback, private (10/8,
  172.16/12, 192.168/16), link-local (169.254/16: cloud metadata), CGNAT 100.64/10, 0.0.0.0/8,
  multicast, IPv6 unique-local/link-local, IPv4-mapped forms of those. Unresolvable → refused.
- Checked at registration **and again before every delivery** (DNS can change after registration).
- No redirects, connect 3 s / read 5 s timeouts, response body ignored.
- `allow-private-hosts` exists only for local development and tests.

### Data
- Flyway `V8`: `webhooks` (uuid, owner, url, secret, events text[], active), `webhook_deliveries`.

## Out of scope

Encrypting webhook secrets at rest (noted in the write-up as a hardening step), purging old deliveries.

## Acceptance criteria

- Register / list / get / delete with owner isolation; secret only in the 201.
- Invalid URL, http, private or metadata addresses, unknown events → 400 with reasons.
- A completed command produces exactly one signed delivery to each subscribed webhook; a
  receiver verifies the signature with its secret.
- 500 → retried with backoff; after the last attempt FAILED; 410 deactivates the webhook.
- Unsubscribed events and deleted webhooks get nothing; ping works.

## Checklist

- [x] Ticket detailed
- [x] V8 migration, entity, repository, properties
- [x] `WebhookUrlPolicy` + unit tests
- [x] Service + controller + problem types
- [x] Notifier (fan-out) + hooks in telemetry / uplink
- [x] Claims, sender, dispatcher, scheduler
- [x] Integration tests with a stub receiver
- [x] SPEC updated
- [x] `./mvnw verify -Plocal-db` green: 174 unit + 130 integration tests
- [x] Live check: a separate receiver program got `ping` and a real `command.completed` (drill #5 executing on the rover), both with VALID signatures; delivery log DELIVERED

## Notes for later tickets

- Secrets are stored in plain text (needed to sign). Hardening for the write-up: encrypt them at
  rest with a key from the environment, or store a key id and keep keys in a vault.
- DNS rebinding: the URL is re-checked before each delivery, but the HTTP client resolves the
  name again for the connection. The JVM caches successful lookups (30 s by default), which makes
  the window tiny; pinning the connection to the checked address would close it fully.
- Deliveries are never purged; add a retention job like the event purge if needed.
- Frontend: a "Webhooks" page (register, see the secret once, delivery log, ping) would be a
  small addition on top of the generated types.
