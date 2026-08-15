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

The complete bearer is embedded in `/api/document-acceptance/{token}` and therefore appears in the request
path. Cloudflare must apply the same no-log Skip rule, compatibility exception, and generic-rate exclusion
used for other path credentials to `/api/document-acceptance/*`. The application stores only the hash,
never writes the token or raw path to application/audit logs, uses a uniform unavailable response, and
applies the per-token and trusted-source admission above. These controls compensate for the path shape;
edge events or exported raw paths must never be treated as secret-free evidence.

## What a recorded view means

Opening the emailed link is a `GET`, and email security scanners, link prefetchers and URL-rewriting
proxies all issue one. `GET /api/document-acceptance/{token}` therefore records nothing at all: it
returns the frozen document and stamps no evidence.

The view is recorded by `POST /api/document-acceptance/{token}/viewed`, which the rendered recipient
page calls. Automated fetchers do not execute that page, so they cannot forge
`first_viewed_at` or a `viewed` event into the completion certificate. The call is idempotent — only
the first one stamps the timestamp and appends the event.

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
