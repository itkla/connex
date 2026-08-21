# Connected Capture

Connected capture is a read-only, polling-based intake path for a user's primary Google or Microsoft calendar and Inbox/Sent mail. It is an internal preview capability until the release gates in [#868](https://github.com/itkla/connex/issues/868) authorize broader use.

How the underlying account connection is authorized — the Connex-managed OAuth identity, the custom/bring-your-own alternative, the local-helper pairing flow, its threat model, and the vendor-disconnection guarantee — is documented separately in [CONNECTED_ACCOUNTS_MANAGED_OAUTH.md](CONNECTED_ACCOUNTS_MANAGED_OAUTH.md).

## Authorization gates

Connection code, provider ingestion, and public OAuth distribution are separate decisions.

The binary contains both provider adapters, but capture remains unavailable unless all applicable settings are enabled:

- the existing connected-account provider setting;
- `CONNEX_CONNECTED_CAPTURE_SCHEDULING_ENABLED=true`;
- `CONNEX_CONNECTED_CAPTURE_GOOGLE_ENABLED=true` or `CONNEX_CONNECTED_CAPTURE_MICROSOFT_ENABLED=true`.

Every capture setting defaults to `false`. If the scheduling gate is off, the scheduler bean is not created, the capture capability is not advertised, and the frontend does not render the capture surface. A provider-specific flag disables that provider at both the API and worker policy boundaries. Credential revocation and explicit current-workspace erasure run independently of ingestion flags so turning a flag off cannot preserve access or prevent a user from erasing retained content.

Google development use is limited to allow-listed test users of the unverified OAuth application. Microsoft development use is limited to the configured development tenant and accounts. Neither provider may process partner or public data until the current internal-ingestion and public-distribution gates authorize it. Google restricted-scope verification/CASA and Microsoft publisher verification remain owned by #868.

## Captured envelope

The supported envelope is deliberately narrow:

- primary calendar events, expanded into individual occurrences by the provider;
- Inbox and Sent messages;
- 90 days of backfill by default, with an enforced 180-day maximum;
- bounded pages, hard HTTP deadlines, delta/history cursors, leases, backoff, and intervention states;
- metadata-only by default;
- body content only when both the workspace administrator and the connected user explicitly permit it;
- exact current person-email matching through the canonical matching contract;
- review-required or policy-permitted automatic admission to canonical activities.

The following are not captured: attachments, raw MIME, remote images, arbitrary calendars or folders, full-mailbox search, outbound mail, calendar mutations, fuzzy identity links, webhooks, call media, or transcription. The evidence surface lists these material exclusions. Provider content is never used as an AI dependency; deterministic matching, storage, and activity projection work with AI disabled.

## Policy and privacy

A workspace administrator sets a restrictive ceiling. Each connected user chooses a policy within that ceiling. Disabling either layer pauses durable work immediately. Re-enabling a workspace queues only previously paused states whose user policy is enabled.

Policies cover:

- calendar, Inbox, and Sent streams;
- backfill duration;
- metadata-only versus body admission;
- manual, review-before-capture, and policy-permitted automatic admission;
- mandatory private-event exclusion;
- internal-only interaction exclusion;
- excluded participant domains;
- user-owned exact email exclusions;
- provider conversation/source exclusions.

Private or newly excluded source content withdraws any earlier activity projection on replay. Archived, suspended, or provision-ceased people are excluded by the canonical identity query and rechecked under the capture transaction before projection. Zero or multiple exact matches are held. Review actions use optimistic versions, revalidate current exact identities, and are workspace- and current-user-scoped. Contact creation continues through the canonical duplicate-preflight flow; possible duplicates hand off to the existing record rather than creating a second merge model.

## Provider behavior

Google Calendar persists each `syncToken`, lists metadata without descriptions, and retrieves each authorized description separately so one oversized body cannot block the stream; an oversized metadata page retries with one event. Gmail uses message IDs plus `historyId`; its bounded list overlaps the history anchor by one provider-request interval so mail arriving at the handoff is replayed idempotently rather than skipped. Metadata checks enforce both ends of that round before any body request. Incremental changes beyond the round do not block later history pages: the prior stable anchor is retained and the range is replayed idempotently on the next scheduled round until every change is eligible. An invalid history cursor returns to the bounded historical window. Label removal from Inbox or Sent and provider deletion are tombstones. Microsoft Calendar uses a bounded `calendarView` scan so metadata-only policy can exclude event bodies at the provider request. Microsoft mail resolves the exact Inbox or Sent folder identity, establishes an ID-only delta anchor, and enumerates the complete configured time window through the ordinary message-list API up to the completed anchor boundary before consuming subsequent changes. The deliberate overlap at that boundary is idempotent and prevents mail arriving during bootstrap from being lost. This avoids Graph's 5,000-item filtered-delta bootstrap cap without reading an old mailbox outside the authorized window. Microsoft requests immutable Outlook IDs, UTC projections, and text-only bodies, follows only HTTPS Graph continuations bound to the exact active folder, and rejects cursors for any other host, path, or folder. Calendar cancellation, Graph removals, and mail removals are tombstones.

Every source identity is hashed from provider, stream, and stable source ID. The tenant database uniqueness constraints are authoritative. A replay with an identical admitted payload is a no-op; an update reconciles the existing participants and canonical projections; a tombstone or new exclusion withdraws projections. Provider backfill writes directly through the inert activity projection path and does not emit per-item notifications or automation fan-out.

Access tokens refresh before expiry under a generation-bound lease. Refresh-token rotation is persisted, and a stale worker cannot overwrite a reconnect generation. Connections migrated without an immutable provider-account identity are fail-closed and require the explicit all-workspace retained-data reset before reauthorization. Pause, resume, reconnect, and disconnect each fence older tenant fan-out with a new generation or owner-bound lease. Review admission locks and revalidates that same connection generation. Long pages renew the owner-bound lease between bounded provider calls and fail if ownership has changed. Provider HTTP calls occur outside database transactions. Requests use fixed provider hosts, refuse redirects, bound response bytes and duration, classify authorization/cursor/rate-limit failures, and never log tokens or captured content. Body-enabled capture first obtains bounded metadata, applies private, sensitivity, domain, person, conversation, internal-only, and time-window exclusions, and only then retrieves each authorized text body separately; an oversized authorized body is omitted without blocking the metadata stream.

## Health and recovery

The Connections screen exposes per-stream progress, freshness, last success, partial/stale state, and stable error codes. A user can pause/resume the connection, queue a retry, reauthorize, review held participants, approve a fully resolved interaction, or purge the current workspace's provider content.

Retryable failures use bounded exponential backoff and provider `Retry-After` when present. Invalid cursors reset to the bounded backfill window. Repeated or non-retryable failures enter an intervention state instead of spinning. Scheduler sweeps rotate through workspaces and process one bounded stream page per claim.

Support diagnostics are limited to provider, stream, status, cursor presence, timestamps, counts, and stable error codes. Tokens, provider source IDs, participant identities, subjects, bodies, and raw provider responses must never appear in logs or support telemetry. Operators may inspect status and error codes; access to captured content remains governed by ordinary workspace permissions.

## Deletion, export, and incidents

Ordinary disconnect requires recent authentication, fences older capture work with a new credential generation, best-effort revokes the provider grant, destroys the local credential, and retains existing captured data. The control row moves through the internal `revoking` state into a credential-free `disconnected` tombstone; client APIs hide the tombstone and map the transient state to the existing disconnecting presentation. The tombstone retains the provider's immutable account id while clearing display email and scopes, so only the same mailbox can reattach to that retained capture namespace. Legacy tombstones without an immutable provider id cannot reattach until the same explicit reset is complete. Reconnecting the same account advances its credential generation by exactly one, so a stale worker can never regain authority. A user who wants to connect a different mailbox must instead confirm the separate all-workspace retained-data reset; that durable cleanup erases every provider capture catalog before deleting the tombstone.

Browser and managed-native authorization attempts bind the expected absence or connection id and credential generation before provider authorization begins, then re-check it before and after code exchange under the user-to-connection lock order. A disconnect or other credential change therefore supersedes every stale callback. If Google issued a token during the remaining exchange race, Connex best-effort revokes that fresh token before discarding it. Microsoft exposes no token-revocation endpoint, so the local generation check still prevents Connex from storing or using the returned credential, but the user may need to remove the application grant in Microsoft account settings if they want the provider-side consent itself withdrawn.

Explicit current-workspace erasure is a separate operation. It requires recent authentication and a locked active membership, but not an active provider connection, an ingestion feature flag, or `ACTIVITY_DELETE`. It idempotently removes the user's provider-owned activities, source envelopes, participants, decisions, policy, and cursor state only in the active workspace. Provider capture evidence used in a data-subject disclosure therefore survives ordinary disconnect. Once capture is no longer running, its history window does not act as an independent deletion schedule: retained evidence remains until this explicit erasure, the separately confirmed all-workspace reset, membership removal, account deletion, or tenant teardown.

Account deletion preserves the legacy all-catalog lifecycle: `disconnecting` prevents new claims, an owner-leased durable cursor advances through bounded workspace purge pages, and `purge_failed` resumes after the last committed workspace. Provider revocation and generation-checked credential/row deletion happen only after that erasure finishes.

Membership removal erases that user's captured content in the affected workspace. Account deletion iterates the active tenant catalogs directly and erases the user's captured content across every workspace for both providers even when a connection row is already absent. Tenant export and teardown include every capture table through the tenant lifecycle registry; teardown residual verification must be clean before control roots are removed. Metadata-only audit records may state that a policy, review action, erasure, or disconnect occurred, but retained audit data contains no token, source content, participant identity, subject, or body.

For an incident involving provider credentials or captured content:

1. disable the global scheduling flag and the affected provider flag;
2. revoke the affected provider application or user grants;
3. preserve metadata-only audit and status evidence without copying captured content into tickets or logs;
4. run the documented purge or tenant teardown path;
5. verify zero provider residuals before restoring service;
6. treat renewed ingestion as a new authorization decision.

Public incident communications, legal notification, external assessment, and provider-verification actions are release-governance responsibilities tracked by #868 and the release runbooks.
