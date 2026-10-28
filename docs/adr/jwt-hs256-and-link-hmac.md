# ADR: Self-issued HS256 JWTs for operators, HMAC-signed packets for the link

Status: accepted (ticket 04)

## Context

Two kinds of callers reach RedLink:
1. **Operators** (browsers, Swagger UI, curl) calling the public API of mission-control.
2. **The other side of the deep-space link**: mission-control and rover-sim calling each other.

Both must be authenticated, with different trust models.

## Decision

**Operators: mission-control issues its own JWTs.**
- `POST /api/v1/auth/token` checks a bcrypt password and returns a 15-minute HS256 JWT with
  `iss`, `aud`, `sub`, `roles`, `iat`, `exp`, `jti`. The same app validates it as an OAuth2
  resource server (signature, algorithm pinned to HS256, issuer, audience, expiry).
- HS256 (one shared key) rather than RS256 because the issuer and the only verifier are the same
  process. With a separate identity provider (Keycloak, Auth0), the API would switch to RS256 and a
  JWK Set URI; nothing outside `SecurityConfig` and `TokenService` would change.
- No refresh tokens: the operator logs in again after 15 minutes. Short-lived tokens limit the
  damage of a leaked token without needing a revocation list.
- Stateless: no server sessions, so CSRF protection is off (browsers never attach bearer tokens
  automatically). Tokens go in the `Authorization` header, never in cookies.

**Link: HMAC-SHA256 over the raw packet body** (`PacketSigner` in the contract).
- Real missions authenticate uplinked commands, so this mirrors reality, and it is the same
  scheme as signed webhooks (Stripe, GitHub), which the write-up can point to.
- The link endpoints (`/dsn/**`, `/flight/**`) have their own security filter chain that accepts
  only signed packets. User JWTs are useless there, and link signatures are useless on the API.
- Every verification failure gets the same 401; the reason is only logged.

**Login throttling** keys on (username, client IP): 5 failures in 15 minutes → 429 + Retry-After.
Keying on the username alone would let anyone lock a real operator out. The counter is in memory,
which is correct for the single instance this project runs; several instances would need a shared
store such as Redis.

## Consequences

- Two secrets per environment (`REDLINK_JWT_SECRET`, `REDLINK_LINK_SECRET`), validated at startup
  (base64, ≥ 32 bytes). The apps refuse to start without them outside the `local` profile.
- Rotating the JWT secret logs everyone out (tokens live 15 minutes, so that is acceptable).
- The link filter (`LinkSignatureFilter`, `CachedBodyRequest`, `LinkProperties`) is duplicated in
  both apps. Sharing it would need a Spring-based shared module, which breaks the rule that the
  apps share only the Spring-free contract. The signing logic itself is shared (`PacketSigner`).
