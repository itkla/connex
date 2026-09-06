# Connex Public API

The public API is an explicitly enabled, versioned, stateless HTTP surface. This initial increment
proves authentication, workspace binding, tenant-catalog routing, live RBAC revocation, and chain
separation; it does not yet expose CRM records or mutations.

## Availability and ingress

The public security chain and v1 controllers are absent unless
`connex.public-api.enabled=true` is configured. The deployment-profile validator inventories this
dormant flag and refuses it in the `silo` profile. The switch defaults to disabled.

Before enabling it, operators must provide a dedicated authenticated TLS ingress for `/api/v1/**`,
apply an upstream request-rate ceiling, preserve the trusted-proxy client-address contract, keep
the control and tenant catalogs reachable through the configured placement topology, and confirm
that privileged MFA enrollment and recovery are operational. The current body-size ceiling is the
shared `/api/**` ceiling. Enabling the flag without those ingress and recovery prerequisites is not
a supported production posture.

## Versioning

The major version is part of the path: `/api/v1`. A header or query parameter never selects the API
version. Scope names and fields within a major version are additive; a full compatibility and
deprecation policy will ship with the OpenAPI contract slice.

## Authentication

Send the credential in exactly one header:

```http
Authorization: Bearer cnx_pat_<opaque>
```

The `cnx_pat_` prefix is stable for secret scanners. The complete plaintext token is returned only
by `POST /api/api-credentials`; Connex stores its SHA-256 digest and a four-character display suffix.
Read and list responses never return the token or its digest.

Each credential is bound to one workspace, its creating user, and the immutable generation id of
the membership that created it. A routing-only hash lookup selects the catalog before authorization.
Every registered public read then checks the user, deletion reservation, active membership, custom role,
permissions, credential revocation and expiry, and membership generation with non-locking consistent
reads in one read-only `REPEATABLE_READ` transaction that also contains the controller read.
Effective authorization is the intersection of the stored scopes and those snapshot permissions.
An empty intersection leaves the credential valid but without live authority, so the next request
is refused with `403 insufficient_scope`; restoring a mapped permission makes the credential usable
again on the following request without rotation or cache invalidation.
No exclusive authorization lock is retained during controller execution, so reads from one member
or custom role do not serialize.

A revocation committed before the authorization snapshot begins is observed and denied. A revocation
committed after the snapshot begins may overlap and cannot retroactively invalidate that one admitted
read, but the authorization is never cached or used beyond the transaction containing that request;
the next request opens a fresh snapshot and observes the revocation. Successful-use metadata is
updated only after the read transaction closes.

The explicit method/path scope registry also declares each route `READ` or `WRITE`. Registered `GET`
routes, plus framework `HEAD` and `OPTIONS` handling, use the read snapshot above. The transaction
ends when the synchronous servlet chain returns; asynchronous response streaming continues without
the database snapshot. The snapshot can retain ordinary InnoDB shared metadata locks until that
boundary and can briefly delay concurrent Flyway DDL, matching the browser read discipline over a
wider request boundary.

The read transaction commits after synchronous servlet handling returns. If an endpoint or servlet
container flushes a successful response before that commit, a later commit failure cannot replace
the already-committed response. The filter never appends an error document to committed bytes; it
logs the credential id and request id and lets the response end. The current proving endpoint is
non-streaming and rely on normal servlet buffering. Future streaming endpoints must not claim that
their response is coupled atomically to the read-transaction commit.

Every non-`GET` controller mapping must be registered as `WRITE`. Its credential, membership,
permission, revocation, expiry, and generation checks run in a short read-only `REPEATABLE_READ`
transaction that commits before controller dispatch. The controller's service must then open its own
write transaction, acquire the owning aggregate's documented record mutex, and repeat locked
authorization at that recheck point before any mutation. An ordinary write transaction therefore
cannot accidentally join the authentication snapshot or bypass the repository lock order.

Membership removal deletes that generation's credentials, so leaving and later rejoining never
reactivates an old bearer. `X-Workspace-Id`, `connex_workspace`, and path values cannot move a
credential to another workspace or catalog.

Browser sessions never authenticate `/api/v1`, and public credentials never authenticate private
`/api/**` routes. Public requests are stateless, do not create sessions, and do not require CSRF
tokens. Browser CORS requests may send `Authorization`, but credentialed browser CORS mode is off.

Revoking browser sessions (the session-epoch control) does not revoke personal access tokens. Containment for a compromised account is credential revocation, a role change below the credential's mapped permissions, or an account-deletion reservation — each of which blocks the credential on its next request.

## Credential management

Private browser-session endpoints manage credentials in the active workspace:

- `POST /api/api-credentials` issues a credential and returns its plaintext once.
- `GET /api/api-credentials?page=1&size=50` lists secret-free metadata. Pages are one-based and
  `size` is capped at 100 because revoked and expired metadata can remain until later lifecycle
  cleanup.
- `DELETE /api/api-credentials/{id}` revokes a credential without deleting its metadata.

All three require `API_CREDENTIAL_MANAGE`. Issuance requires the same recent WebAuthn assertion as
exports and connected-account secret operations, then performs a locked live-permission check
before inserting the credential. At most 20 unexpired, unrevoked credentials may exist for one
membership generation by default; `connex.public-api.max-active-credentials-per-membership`
configures that bound. A later issuance prunes expired and revoked rows for that generation, so
credential rotation cannot grow its credential table without bound. Revocation, membership
removal, fresh-membership cleanup, and account erasure retain the secret-free audit evidence
described below.
Erasing an account deletes every credential row it created **or revoked**, so a credential another
member created is destroyed when the account that revoked it is erased. The independent
`api_credential.revoke` audit record retains the revocation event and its captured actor label, and
an `api_credential.account_erased` record retains the deletion. The `revoked_by_id` foreign key
keeps `ON DELETE SET NULL` only as the rollback backstop for a binary that predates that cleanup.

The issue request body uses private-API camel case:

```json
{
  "name": "Development CLI",
  "scopes": ["crm.read"],
  "expiresAt": "2027-01-01T00:00:00"
}
```

## Scopes

The initial coarse-grained catalog is:

- `crm.read`
- `crm.write`
- `activities.read`
- `activities.write`

Scope strings use `resource.action` and are permanently additive. The coarse catalog is the
reversible initial choice while CLI and ecosystem requirements are unresolved. A future resource
endpoint must require both its scope authority and its existing `Permission`; the scope never grants
an operation that the creator's current RBAC denies.

## Proving endpoints

- `GET /api/v1/me` returns credential id, name, workspace id, organization id, effective scopes, and
  expiry. Effective scopes are the stored scopes still live under the creator's current RBAC; the
  private `GET /api/api-credentials` inventory remains the source for the stored set. This is the
  only public resource in this increment and returns no CRM data.

The registered `GET` proving endpoint also accepts `HEAD` with the same authorization and
response headers and no response body.

Every controller mapping under `/api/v1` has an exact method/path scope rule. Unmatched v1 paths
are denied rather than inheriting a generic authenticated-credential grant.

## Errors

Every failure emitted by the public security chain and v1 controllers uses:

```json
{
  "error": {
    "code": "invalid_token",
    "message": "A valid bearer credential is required",
    "request_id": "3ae62857-d332-4b25-9120-d61b9c33bf5c"
  }
}
```

Public error codes are always lower `snake_case`, including errors adapted from the private browser
chain. Firewall method rejections use `method_not_allowed`; other public firewall rejections use
`bad_request` without exposing the rejection detail. Initial codes are `invalid_token`,
`insufficient_scope`, `invalid_request`, `bad_request`,
`invalid_cors_request`, `request_too_large`,
`not_found`, `method_not_allowed`, `rate_limit_exceeded`,
`privileged_mfa_enrollment_required`, `public_api_unavailable`, and `internal_error`.
Invalid credentials and privileged accounts that require passkey enrollment receive `401`;
authenticated credentials without the required scope, including credentials whose entire stored
scope set is no longer live under current RBAC, receive `403`.
Replacing a partial, uncommitted response with an error envelope preserves the correlation id,
allowed CORS and `Vary` metadata, rate-limit and `Retry-After` headers, and HSTS while discarding
stale representation headers and bytes.

## Rate limiting

Before any token digest lookup, requests must pass fixed-window limits keyed independently by the
resolved client address and by a domain-separated HMAC-SHA-256 of the complete presented token,
truncated to 128 bits. The client-address bucket is always consumed first. The HMAC uses the existing
server-held audit-integrity secret, and only the pseudonymous truncated value enters the bounded
in-memory registry. The displayed last four characters cannot be used here: anyone who sees that
metadata could otherwise exhaust the real credential's bucket with guessed tokens. Requests without
a parseable bearer use only the client-address bucket. Defaults are 1,200 requests per client address
and 600 per token HMAC per 60 seconds. Successfully resolved credentials then have a separate default
limit of 600 requests per credential per 60-second fixed window. Responses after authentication carry:

The client and token registries have independent 100,000-window memory caps. Expired windows are
reclaimed before a capacity refusal. Each client address may allocate at most 60 token-registry keys
per window by default, configured by
`connex.public-api.rate-limit.pre-auth-new-token-keys-per-client`; further distinct tokens from that
address share one per-client fallback bucket with the normal 600-request token limit. This allocation
quota keeps a few abusive sources from displacing another client's token window without changing the
cross-client HMAC identity of a real token. The registry caps remain last-resort memory bounds and
are not standalone security controls; reaching the default token cap requires at least 1,667 distinct
attributable client addresses in one window.

- `X-RateLimit-Limit`
- `X-RateLimit-Remaining`
- `X-RateLimit-Reset` as an epoch second

A refused request returns `429`, the public error envelope, and `Retry-After` in seconds.

Like `LoginRateLimiter`, enforcement is in memory and per JVM replica. Effective capacity therefore
multiplies by the number of replicas, and credential rotation can distribute traffic across at most
the configured active-credential cap per membership. The upstream ingress prerequisite must enforce
the deployment-wide ceiling; deployments must not describe these JVM-local limits as global quotas.

Credential names are display metadata, may not contain a `cnx_pat_...` token pattern, and are never
copied to audit rows. Credential audit events retain only the numeric credential id and last four
characters; plaintext tokens and digests are excluded from every audit column and application log.

## Deferred platform contracts

CRM resources, writes, pagination, idempotency, OpenAPI generation, outbound webhooks, connectors,
OAuth applications, service accounts, org-wide credentials, and frontend settings UI are outside
this increment. Webhook emission must separately decide how workflow-loop suppression and the
rolling-deployment document automation gate apply; this authentication slice makes no choice for it.
