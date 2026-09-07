# Commercial-document signature delivery

Connex supports a provider-neutral commercial-document delivery envelope and one built-in provider,
`in_app`. The built-in flow sends an immutable generated document to external recipients over opaque
one-time links and records a signer acceptance or decline. It is not a qualified-signature service, a
general signature-evidence network, or an adapter to DocuSign, CloudSign, or another external vendor.

## Operator and workspace gates

Delivery is off by default. An operator must set `CONNEX_SIGNATURE_ENABLED=true`, the deployment edition
must expose the `document_signature` capability, and the actor must hold `DOCUMENT_SEND`. Admin and owner
built-in roles receive that permission; custom roles receive it only when explicitly granted. A disabled
gate returns an explicit unavailable response and never pretends that a send succeeded. Deployment setup
is described in [DEPLOYMENT.md](DEPLOYMENT.md).

The recipient-facing `/document-acceptance` frontend page renders the frozen document in the
document's own locale and lets signers accept with a typed name or decline with a reason. It does not
write an anonymous visitor's locale cookie. Viewers see the document without decision controls. A
successful decision produces an in-session receipt; reloading a completed or declined link returns the
same unavailable state as any other inactive link.

Delivery remains off by default. There is no sender-side frontend for send, resend, void, or artifact
download yet, so an operator who enables the flag can initiate the flow only through the authenticated
API. Before enabling any environment, verify a usable mail transport and the Cloudflare no-log Skip rule
for the `/api/document-acceptance` prefix. Configure `connex.security.trusted-proxies` so source
throttling resolves the recipient address instead of collapsing all recipients behind the Next.js
server. The published bundle trusts the `caddy` and `frontend` service names through Docker DNS.
Every acceptance request — the exchange, the preview, `viewed`, `accept` and `decline` — is issued by
the recipient's browser and reaches the backend through Caddy like any other `/api/*` call; the page
itself fetches nothing server-side. Caddy replaces the browser-supplied forwarding header with one
validated client address, so the trusted-proxy configuration is the only source-address boundary and
each browser request consumes one backend admission.
Whether preview staging should enable the flag is an operator decision; the checked-in deployment
examples remain disabled.

Send and resend require a caller-retained UUID `Idempotency-Key`. Connex claims the key in the workspace
and binds it to the complete operation fingerprint and actor. Retrying the same request with the same key
returns the first delivery result without minting another token or producing another event, audit, or email.
A reused key with a different fingerprint fails closed; a different send key is still refused while the
immutable document has a live envelope.

## Envelope and token model

One `document_delivery` row binds one provider envelope to one immutable `deal_document` version. A
generated active key permits at most one `sent` or `viewed` envelope for that version. Each frozen
recipient carries their captured name, email, role, order, provider identifier, and decision state.
Delivery SQL deliberately does not join `person`; an optional `person_id` is only an association hint,
and the captured recipient identity remains stable if the CRM record changes.

The `in_app` provider mints a separate 256-bit random bearer token for each recipient. The external form
is `w{workspaceId}-{64 lowercase hex}`. The workspace prefix is only a catalog-routing hint; authorization
comes exclusively from the random secret. Connex stores only SHA-256 of the complete token, scopes lookup
to the routed workspace, and revalidates the hash with a constant-time comparison. Resend replaces the
stored hash. Void, expiry, decline, and supersede invalidate every outstanding token. Public requests are
bounded independently per token hash and per hashed, trusted source address.
Malformed input is canonicalized to a fixed `w-1-…` admission sentinel. Its reserved negative
workspace identifier is excluded by the public token grammar and Connex's positive workspace-ID
contract, so no legitimately issued bearer can equal the sentinel.

The emailed bearer link points to the frontend route `/document-acceptance#token={token}`. Browsers never
send a fragment to any server, so the bearer reaches Connex exactly once: in the JSON body of
`POST /api/document-acceptance/exchange`, which the recipient page issues after stripping the fragment
from the address bar. That exchange answers `303` with a token-free `Location: /document-acceptance` and
sets `connex_document_acceptance_flow`, an `HttpOnly`, `SameSite=Strict` grant cookie scoped to
`Path=/api/document-acceptance` and bound to the browser binding cookie plus the server session lineage.
The grant lives 60 minutes, is renewed by re-opening the emailed link in the same browser, and is still
bounded by the recipient token's own expiry and terminal state.

The grant's owner is that binding cookie combined with a lineage held only in the servlet session,
and `server.servlet.session.timeout` is 30 minutes — half the grant. A signer who reads a long
contract without clicking anything would lose the session, and the next decision would be refused
even though the grant is still live. The open recipient page therefore re-reads
`GET /api/document-acceptance` every 10 minutes while a preview is on screen, which refreshes the
session and nothing else: the read records no view, does not extend the grant, does not weaken the
owner binding, and stops when the page is closed. If that read ever comes back unavailable, the page
switches to the unavailable state, because the grant really is gone.

Every other endpoint — `GET /api/document-acceptance`, `POST /api/document-acceptance/viewed`,
`/accept` and `/decline` — reads only that cookie. A token in a path or query is ignored, and the legacy
`/api/document-acceptance/{token}` shapes answer the uniform 404. Because the cookie is now the
authority, both prefixes are CSRF-protected: the recipient page sends the CSRF header on every mutation.
The cookie is shared by every tab of one browser, so a later exchange silently replaces the grant an
earlier tab rendered; the preview therefore carries a non-authorizing `flowId` (the digest of the grant)
and `accept`/`decline` must echo it, otherwise the request answers the uniform 404 and no recipient row
changes. The exchange itself is budgeted per source address before its body is read, by the same
per-IP exchange budget every other one-time link shares.
The application still stores only the token hash, never writes the token to application or audit logs,
uses a uniform unavailable response, and applies the per-token and trusted-source admission — at the
exchange for the emailed bearer, and keyed on the grant cookie for every later request.

The frontend HTML response sets `Referrer-Policy: no-referrer` on `/document-acceptance`. Client error
reporting replaces bearer path segments and `#token=` fragments in the pathname, message, and stack
before the report can reach the control-plane diagnostics row.

Cloudflare skip and rate-limit expressions must be updated for the cutover: the HTML routes
`/document-acceptance` and `/unsubscribe` no longer carry a credential, and the API prefixes to exclude
from the generic API rate rule are `/api/document-acceptance` and `/api/delivery/unsubscribe` with no
trailing-slash requirement. The retired `/document-acceptance/{token}` prefix stays in the no-logging
skip rule until every outstanding delivery has been re-sent or invalidated, because an already-emailed
link still carries a redeemable bearer in its path. See `docs/EDGE_DEFENCE.md` and the cutover entry in
`docs/UPGRADING.md`.

### Accepted residual: unavailable-link timing

Unavailable responses deliberately share status, content type, and body bytes, but their server work
is not equal. A malformed token is hashed as the fixed impossible sentinel and performs only the
catalog lookup for its reserved negative workspace. A well-shaped unknown token for an active
workspace continues through recipient discovery. An expired, voided, completed, or declined token
can additionally discover its delivery and acquire the deal, document, delivery, and recipient locks
before it is rejected. Those differences make repeated latency sampling a plausible token-history
oracle even though response content reveals nothing.

The admission filter bounds, but does not eliminate, that oracle before request-body parsing. The
defaults allow at most 60 requests per token hash and 120 requests per trusted source address in one
minute on each replica; every malformed token shares the sentinel token bucket. Closing the residual
requires a measured, fixed-work admission design for every token class, such as bounded decoy
catalog/recipient/delivery records and equivalent lock work or calibrated response padding validated
against production latency distributions. Hash equalization alone is insufficient. This release
accepts the bounded timing difference; its PR-facing security note must reference this section, and a
future security change must re-review the residual when admission storage, lock shape, replica count,
or rate limits change.

## What a recorded view means

Opening the emailed frontend link is a `GET`, and email security scanners, link prefetchers and
URL-rewriting proxies all issue one. The page's `GET /api/document-acceptance` therefore records
nothing at all: it returns the frozen document and stamps no evidence. The exchange that precedes it
records nothing either — an exchange is not a view — although a scanner that executes no JavaScript
never reaches it, because the bearer lives in the fragment.

The view is recorded by `POST /api/document-acceptance/viewed`, which the rendered recipient
page calls with its grant cookie. Automated fetchers do not execute that page, so they cannot forge
`first_viewed_at` or a `viewed` event into the completion certificate. The call is idempotent — only
the first one stamps the timestamp and appends the event.

Inactive, expired, voided, completed, declined, unknown, and wrong-hash links share one HTTP 404 and one
recipient-facing unavailable state. A disabled feature or capability returns HTTP 503 and the page shows
a distinct service-unavailable state; admission throttling returns HTTP 429 and asks the recipient to try
again shortly. The UI never displays the backend response body.

The residual limitation is deliberate and bounded: anyone holding the token can record a view, but
holding the token is already the credential for accepting or declining, so there is no privilege to
escalate. A recorded view attests that something rendered the page with a valid token, not that a
specific human read the document.

## Evidence and artifacts

The append-only `document_delivery_event` ledger records actor, recipient, system, and provider events.
Provider callbacks carry an adapter-authenticated workspace routing handle and use a unique external event
identifier, so replay is idempotent. Every callback must carry its provider-authenticated occurrence time.
Under the locked envelope, a terminal event that occurred before `expires_at` wins even when it arrives
after the scheduler recorded expiry. An event at or after `expires_at` cannot revive the envelope; the
envelope expires at `expires_at` and the later callback remains evidence. Completion time is the maximum
persisted signer decision time, so callback arrival order cannot change it or the certificate bytes.

Completion creates two immutable, tenant-owned managed objects:

- `signed_document` is the byte-exact UTF-8 JSON stored as the frozen `DocumentContent` shown through the
  acceptance flow. Its metadata includes byte length and SHA-256.
- `certificate` is deterministic JSON containing the envelope/provider identifiers, completion time,
  recipient identities and roles, their decisions and timestamps, typed acceptance names, domain-separated
  HMAC-SHA256 request-evidence values, and the signed-document SHA-256. Its exact top-level fields are
  `workspaceId`, `dealId`, `documentId`, `documentVersion`, `documentType`, `approvalRequestId`,
  `approvalOutcome`, `approvalPolicyId`, `provider`, `providerEnvelopeId`, `deliveryId`, `sentAt`,
  `completedAt`, `signedDocumentSha256`, and `recipients`. `approvalOutcome` is the terminal approval result
  or `no_approval_required`; `approvalRequestId` is null only when no approval request existed, and
  `approvalPolicyId` is null when that request had no policy. Each recipient
  entry contains `recipientId`, `name`, `email`, `role`, `decision`, `firstViewedAt`, `decidedAt`,
  `typedName`, `declineReason`, `evidenceIpHash`, and `evidenceAgentHash`. When a policy
  applied, `approvalPolicyId` comes from the immutable request-time snapshot and survives later policy
  deletion. V176 labels already-null historical policy bindings `unknown_legacy` because their exact prior
  identifier cannot be reconstructed; send and certificate creation fail closed for those approvals, and
  support must create and approve a new document version rather than asserting an unknown policy id.

Connex renders no PDF on the server today. This is deliberate: the rejected renderer could not preserve
CJK text correctly. A future external adapter may store a provider-returned signed PDF in the same
artifact table with `content_type = application/pdf`; that does not change the envelope or evidence model.
Artifact download is an authenticated, permission-checked managed-object stream, never a public filesystem
path. Retention, export, and erasure are covered by
[GOVERNANCE_DELETION_AND_RETENTION.md](GOVERNANCE_DELETION_AND_RETENTION.md).

## Provider SPI

An adapter implements `DocumentSignatureProvider` with a stable key, provider-neutral send and void
commands, and authenticated webhook parsing. A send outcome must map every requested recipient exactly
once and return stable provider envelope/recipient identifiers. A recipient outcome may optionally return
a Connex-delivered bearer link; the built-in provider does, while a future vendor-managed delivery may
omit it. It must never log recipient addresses, tokens, document content, or raw provider payloads.

Webhook parsing authenticates the exact headers and body with the provider's scheme before returning a
`ProviderEvent`. That verified result must include the workspace routing handle, provider envelope and
recipient identifiers, a replay-stable external event id, normalized event type, occurrence time, and
bounded metadata detail. Tenant and envelope lookup never trusts an unauthenticated body field. Provider
completion may also return authenticated signed-document bytes; JSON and PDF both enter the same managed
`signed_document` artifact slot and the certificate hashes whichever exact bytes were supplied. Connex
stages those immutable bytes if they arrive before the last signer and keeps an external envelope live if
all recipient decisions arrive before its signed artifact, so callback reordering cannot replace a provider
PDF with Connex JSON. Provider events without an occurrence time fail closed. Provider
recipient identifiers must be unique within an envelope both at the SPI boundary and in the database.
Provider network I/O must be durably orchestrated outside transactions that hold delivery metadata locks; the
built-in provider is safe because it performs no network I/O and registers no webhook. This release fails
closed when an authenticated send, resend, or void names any provider other than `in_app`. Webhook parsing
is available to authenticated adapters now, but outbound execution for a networked adapter first requires a
durable dispatcher that records intent before egress and reconciles outcomes outside those locks.

## Recovery and support boundary

- **Outbound mail outage:** metadata commits before mail dispatch. Restore the configured transport and use
  resend with a new idempotency key, which invalidates the old link and creates a new token. A lost HTTP
  response is different: retry the same send/resend with the same key to replay the original result.
- **Lost callback:** replay the provider's authenticated event with the same external event id. Replays are
  harmless. If the provider cannot replay, support may compare provider state and apply a newly identified,
  authenticated event; direct event-table updates are unsupported.
- **Duplicate or reordered callback:** the external-event key deduplicates replay. Occurrence time, not
  arrival time, decides whether a pre-expiry terminal event overrides scheduler expiry; completion time is
  the maximum signer decision time. At-or-after-expiry events remain evidence without reopening the envelope.
- **Signer mismatch:** void the live envelope, correct recipients in a new delivery, and resend. Connex does
  not rewrite a frozen recipient identity or completed certificate.
- **Expiry or lost recipient link:** a bounded scheduler expires overdue envelopes and restores the document
  to `final`; create a new envelope or resend a still-live recipient. Every unavailable terminal token uses
  the same non-leaking response.
- **Managed-object outage:** completion fails atomically at the metadata boundary; no successful completion
  is reported without both artifact metadata rows. Restore storage and retry the idempotent decision.

Support may inspect envelope ids, statuses, event types, timestamps, artifact hashes and lengths, and job-run
metadata. Support must not request or log bearer tokens, recipient addresses, document content, typed names,
raw IP addresses, raw user agents, or artifact bytes. Legal validity, identity proofing beyond the recorded
typed name and request evidence, and external-provider contractual assurances remain outside the built-in
provider's support boundary.
