# Privileged MFA policy and recovery

Connex requires a passkey for every account that currently holds administrative authority. The
policy is evaluated from current control-plane rows on every authenticated request; it is not
cached in the session. Promotion therefore takes effect on the next request, and demotion removes
the additional confinement on the next request.

## Privileged accounts

The policy treats these accounts as privileged:

- organization `owner` and `admin` members;
- active built-in workspace `owner` and `admin` members; and
- active custom-role members granted any of `API_CREDENTIAL_MANAGE`, `AUDIT_READ`,
  `CAMPAIGN_MANAGE`, `CAMPAIGN_SEND`,
  `CONSENT_MANAGE`, `CUSTOM_FIELD_MANAGE`, `DOCUMENT_MANAGE`, `MEMBER_MANAGE`, `PIPELINE_MANAGE`,
  `PRODUCT_MANAGE`, `ROLE_MANAGE`, `RULE_MANAGE`, `SEQUENCE_MANAGE`, `SHARE_MANAGE`, `TAG_MANAGE`,
  `TEAM_MANAGE`, or `WORKSPACE_SETTINGS`.

`SEQUENCE_MANAGE` is privileged because it authorizes message-template authorship for future
outbound sales activity; holding it in any workspace makes the account privileged account-wide.
`SEQUENCE_VIEW` is read-only and does not by itself make an account privileged.

`TEAM_MANAGE` is privileged because it authorizes manager assignment and membership changes across
the workspace's teams.

`API_CREDENTIAL_MANAGE` is privileged because it can mint a durable bearer credential. Public API
authentication independently applies the same passkey-enrollment policy before admitting a PAT,
so an unenrolled privileged creator cannot bypass browser confinement with an existing token.

An unenrolled privileged account may read its own account and workspace-membership snapshot, read
the public capability posture, obtain a CSRF token, list and enroll passkeys, use the recovery
ceremony, [leave a workspace or organization](#organization-tenancy-and-safe-exits), and log out. Every other API request is refused by the backend with
`PRIVILEGED_MFA_ENROLLMENT_REQUIRED` until enrollment completes. By default, with
[attestation enforced](#standing-and-attestation-enforcement), an account that holds passkeys
but none approved in every organization where it is privileged is confined the same way, with
`PRIVILEGED_MFA_ATTESTATION_REQUIRED`, until a grantor's approval code covers one of them.

Password, OIDC, social login, and SAML establish an ordinary session but never a recent-MFA stamp.
An OIDC or SAML assertion may serve as the existing-account proof for first enrollment because the
session is newly established and bound to the same account. It does not satisfy a high-risk
operation. Only a verified WebAuthn assertion, a passkey login, or the just-completed passkey
registration ceremony creates the recent-MFA stamp.

### Confinement recheck during locked workspace authorization

The servlet filters' decision precedes a mutation's transaction. When enforcement is enabled,
`WorkspaceService` also reasserts confinement when locked authorization completes for the
authenticated HTTP actor: all four `lockAndRequirePermissions*` variants,
`lockedMemberPermissionsAndMemberships` (including its wrappers), `lockedMemberAuthorization`,
`isLockedBuiltInAdministrator` (including its assigned-role variant for report deletion),
approval membership authorization, workspace identity authorization,
invite-grant authorization, and role-mutation authorization. This is additional confinement; the
existing permission and tenant checks still authorize the operation.

Privilege here comes from the exact locked active membership: built-in owner/admin without a
custom-role overlay, or a locked custom role granting a permission from the closed privileged list
above. The actor is also privileged here as an owner/admin of this workspace's active organization,
read by a distinct cache-flushed, nonlocking statement on the same connection. This matches the
standing predicate's organization branch; `org_member` admits exactly `owner` and `admin` roles.
The administrator helpers retain local overlay permissions shared for confinement before returning
false for built-in administrator qualification. An ordinary overlay remains admissible under its
existing permissions. An empty permission requirement remains membership-only for custom-role
members: it does not resolve or lock their role authority, so the recheck cannot infer privilege
from those rows.
It does not authorize privileged actions. Target users,
assignees and recipients are never confined in place of the request actor. Background/system work
and attributed automation retain their existing behavior. With enforcement disabled the recheck
and its credential locks are skipped.

The recheck asks whether the actor owns a passkey and, when attestation is enforced, whether an
owned passkey carries `BREAK_GLASS` assurance or covers **this workspace's organization**. The
filters retain their account-wide rule that one credential must cover every privileged
organization. With attestation enforcement off, the recheck neither reads nor locks coverage.
The recheck uses current shared credential/coverage reads on the mutation's own
connection, with explicit MyBatis cache flushing; it neither suspends the transaction nor borrows
a second pooled connection. Account and workspace authorization roots precede credential/coverage
locks, matching registration and deletion's role-before-credential order. The guard adds no
organization lock in either the parent phase or after authorization: retaining that parent before
aggregate rows can cycle against a queued organization writer and an aggregate holder's trailing
audit. Ordinary workspace actors use the nonlocking organization lookup; actors ordinary in both
scopes perform no credential read. Browser credential/coverage
contention uses NOWAIT and the existing retryable workspace-conflict response, avoiding a wait
cycle against assertion updates and their trailing audits. See the exact
[locking contract](backend/LOCKING.md#servlet-filter-confinement-recheck-g1g5).

Browser refusals are the same 403 code/message bodies as the filter:
`PRIVILEGED_MFA_ENROLLMENT_REQUIRED` / “A passkey must be enrolled before this privileged account
can continue”, or `PRIVILEGED_MFA_ATTESTATION_REQUIRED` / “A passkey must be approved before this
privileged account can continue”. Public-API advice and filter propagation preserve the PAT 401
`error` envelope, lower-case code, message and `request_id`. No production PAT write route is
introduced; the production registry still denies unregistered writes.

The browser filter records its normalized enrollment, safe-exit and grant-cookie-recipient
exemption decision in server-owned request context, which the recheck consumes without matching
paths again. Existing method rules, including HEAD behavior, remain unchanged. PAT requests have
no browser enrollment exemption.

Under-lock refusals share `DenialAuditRateLimiter` admission with filter refusals and retain
account-scoped `auth.mfa.policy.denied` evidence. The actor, null workspace/org scope, reason,
resolved client address and sanitized metadata are captured when the refusal is registered.
`AuditService` appends only after the outer mutation transaction completes, whether it commits or
rolls back; it never appends independently while that transaction holds the account root. Failed
capture or delivery releases admission. Refusal is unconditional regardless of audit suppression
or failure. Deferred delivery is best-effort: a crash or append failure can lose the row, and its
timestamp is the append time. Completion does not read request/thread context lazily.
Completion is synchronous, so an already-running writer can delay the response until that writer
commits or the database lock timeout fires. The mutation has already rolled back and released its
locks; this wait cannot cycle back through the refusing transaction. Append failure cannot change
the refusal, and no background delivery queue is introduced.

This closes promotion in the authorizing workspace that commits before its locked authorization
is acquired. It is not an account-wide serialization barrier. Residuals are:

- Privilege granted in another workspace, especially a permission addition to another custom
  role, is outside this workspace's retained authority. Even a remote promotion committed after
  the filter check and before this local check is not inferred from unrelated local authority.
  Existing account locks serialize remote built-in membership changes, but do not make the local
  predicate account-wide.
- Organization grants take the grantee account shared and need not wait for this transaction.
  The nonlocking organization check uses the transaction's isolation. At REPEATABLE READ, grants
  committed before the transaction's consistent snapshot was established are visible to the recheck;
  a grant committed after that snapshot and before the guard runs is not seen. A grant after the
  guard also remains outside this fence. READ COMMITTED sees grants committed before the statement.
  Self-service workspace founding takes the creator account shared and is not fenced here.
- Direct-lock ordinary record writes, such as `PersonService.replaceTags`, do not use these
  authorization helpers and receive no new recheck. They require their existing ordinary record
  authority; a just-promoted, not-yet-enrolled account can complete in-flight ordinary writes
  that passed the filter before the promotion committed.
- Mutations that never use locked authorization are also outside this fence. For example,
  `TagService.create`, `update` and `delete` retain their preliminary `TAG_MANAGE` checks and
  filter-only confinement. Those are administrative writes, not the ordinary direct-lock writes
  above; this change does not close their existing filter-to-write window. No domain-service
  inventory or migration to the helpers is part of this change.
- Reads receive no new locked recheck. Existing filter confinement, public identity-read snapshot
  semantics and export step-up controls remain in force.

There is no universal numeric or millisecond bound: several requests or transactions can already
be in flight, and a bulk request can contain several writes. The residual consists of the
already-admitted operations whose last applicable check preceded the promotion; their existing
workspace authorization still governs their writes. Subsequent fresh filter checks confine the
account.

## High-risk operations

The existing service boundaries require recent WebAuthn verification for role and membership
changes, SSO and allowed-domain configuration, secret/provider/mail/connector configuration,
tenant deletion and lifecycle export, provider disconnection and captured-data purge, support and
privacy exports, passkey changes, and personal API credential issuance. The policy filter also
covers every remaining CSV/data-export
surface: ordinary CRM exports, workspace and organization audit exports, campaign audience
exports, and report/snapshot exports. A password re-prompt never satisfies these gates. The recent
verification window defaults to ten minutes; an absent, zero, negative, or malformed duration
fails closed.

### Auditing the filter's own refusals

`PrivilegedMfaEnforcementFilter` audits its two refusals, `auth.mfa.policy.denied` (reason
`enrollment_required`, or `attestation_required` for an account awaiting passkey approval) for a
confined account and `auth.mfa.step_up.required` (reason
`step_up_required`) for an export without recent verification, **at most once per hour for each user,
action, reason and client address**, and for at most eight addresses per user, action and reason.
Keying on the reason means an account that moves from awaiting enrollment to awaiting approval
records the new reason at once. Every request is
still refused; only the audit row is admitted. A `GET` can trigger either refusal. The `SameSite=Lax`
session cookie rides a cross-site top-level navigation, and where SAML sets it to `SameSite=None`, any
cross-site subresource load (an image, a no-cors fetch) carries it silently. Without the bound another
site could append rows attributed to the victim at will
([#1850](https://github.com/itkla/connex/issues/1850)). Keying on the client address keeps a stolen
session visible through the requests its own browser makes from elsewhere, and a row that fails to
write is retried by the next refusal rather than suppressing the hour.

The denial row's context keeps the reason in `error` and, when the request supplies them, records
`Sec-Fetch-Site`, `Sec-Fetch-Mode` and `Sec-Fetch-Dest` as `secFetchSite`, `secFetchMode` and
`secFetchDest`. These fields are **advisory**: readers and alert rules can discount browser-initiated
cross-site rows, but non-browser clients can forge every value. Fetch metadata never skips a row,
changes audit admission or changes the rate-limit key. Values pass through audit text sanitization,
have control characters replaced with spaces and surrounding whitespace stripped, and are capped
at 64 characters each. Absent headers produce no fields. Neither does a value the request
firewall refuses when it is read, such as a C1 control character arriving as an obs-text byte:
that field is dropped and the row is still written. Server-rendered reads that forward only the
session cookie consequently have no fetch-metadata fields
([#1936](https://github.com/itkla/connex/issues/1936)).

The denial-audit writer also omits a `User-Agent` value rejected by the request firewall, retaining
the row with a null user agent. Other audit writers keep their existing strict header handling.

Anyone reading or alerting on this trail should expect:

- **one row per window, not one per request**, for a user who keeps hitting the same wall from the
  same address;
- **a bound per backend replica**: N replicas can record up to N rows per window and key, and a
  restart re-arms every window;
- **a bounded key set**: the limiter tracks at most 4,096 user-and-action keys. Above that, the
  least recently admitted keys are evicted and their addresses may record again early, so under
  sustained saturation the per-key bound does not hold; and
- **addresses as `ClientIpResolver` resolves them.** Behind a reverse proxy that is not listed in
  `CONNEX_SECURITY_TRUSTED_PROXIES`, every request resolves to the proxy, the key narrows to user and
  action, and a second address inside the hour is not recorded separately. Requests the frontend
  server makes for a user while rendering a page forward only the session cookie, so all of a user's
  server-rendered refusals share the frontend service's single address, whichever session made them.
  Once a refusal is decided, the two filters use `ClientIpResolver.resolveForDenialAudit`: a
  forwarding-header read rejected by the firewall falls back to the direct peer for the denial
  limiter key and audit row. The audit writer receives that resolved address without reading the
  forwarding header again, and the filter writes the refusal response without continuing the chain.
  Existing resolver methods still propagate firewall rejection, preserving request admission and
  authentication throttling. The denial-only method must never be used for those decisions.

The window defaults to one hour and is set by `connex.security.denial-audit-window-seconds`.

The public API's `ApiCredentialAuthenticationFilter` also records `auth.mfa.policy.denied` for
`enrollment_required` and `attestation_required`, with a summary beginning `Public API`. It shares
the browser filter's limiter and trusted-proxy client-address resolver, including admission keys,
window and capacity bounds; the channel does not open another window. After its authentication
read transaction ends, the filter clears the credential's tenant binding and appends independently
at account scope in the control catalog (null workspace and organization), attributing the row to
the denied account as actor and user entity. No denial evidence is written to the tenant catalog.
An append failure is logged and releases its admission for the next refusal, exactly as in the
browser filter. The public response remains `401 privileged_mfa_enrollment_required` or
`401 privileged_mfa_attestation_required` in the existing public error envelope, whether the audit
is written, suppressed or fails.

The public API resolves the address for its pre-authentication throttle before deciding MFA
confinement. A firewall-rejected forwarding header at that earlier boundary is still refused with
400; only a request that has already reached a confinement decision uses the denial-only fallback.

Refusals at service boundaries (reason `service_boundary`) are not admitted through this bound. They
sit behind state-changing routes that CSRF protection shields from cross-site triggering, or behind
export paths this filter refuses first.

### Standing export channels and the surfaces outside the filter's path list

The filter's path list only covers requests that answer with the exported bytes. Three export
surfaces sit outside it. All three are controlled; none relies on the filter. #1763 records the
decision for each, with the reason, so a later reader does not mistake an intentional design for an
oversight.

**Scheduled report delivery is controlled when the schedule is written, not when it sends.**
`ReportDeliveryScheduler` emails a **summary** on a scheduler thread — the report name, the period,
up to two headline totals, one narrative claim truncated to 240 characters, and an authenticated
deep link to the snapshot. `MailMessage` carries no attachment field, so no CSV leaves this way; the
CSV exists only behind the interactive, step-up-gated export route, and the link still demands a
session. There is no session on the scheduler thread to step up, so gating the send is
unimplementable, and gating it would strand every schedule that was already approved.

What a redirected schedule therefore leaks is aggregate figures, one narrative sentence, the
report's existence and its cadence — not row-level data — plus a standing channel to an address the
report's owner never chose, and a transfer of run-as to whoever last wrote the schedule. The schedule is the thing that decides where tenant data goes, so
`ScheduleService.create` and `ScheduleService.update` require a fresh WebAuthn step-up from a
privileged account before opening or redirecting the channel. `update` replaces the whole schedule,
so recipients, cadence, hour, timezone, and the enabled flag are all covered by that one gate.
Delivery itself — claiming a due occurrence, resolving run-as, re-deriving recipients, freezing the
snapshot — is deliberately untouched, so an existing schedule keeps delivering unattended.

The gate is scoped to privileged accounts, and it is independent of
`CONNEX_PRIVILEGED_MFA_ENFORCED`, like the other high-risk service boundaries above and unlike the
staged-rollout export filter. An ordinary member reaches nobody new by scheduling: a schedule may
only name active workspace members who already hold the report's own read permissions, re-checked at
each send. Admitting a *new* member is already step-up gated upstream
(`InviteService.createInvite`, `InviteLinkService`), so that is not the channel this closes. What it
closes is redirecting a standing channel to an **existing** member who holds the report's read
permissions but whom its owner never chose, and the silent transfer of run-as that comes with it. Refusals are audited as
`auth.mfa.step_up.required` with the `service_boundary` reason.

**Deleting a schedule is gated too**, by owner decision on 2026-09-29. It closes a channel rather
than opening one, which was the original reason to leave it open — but deleting is a strict superset
of setting `enabled=false`, so an ungated delete made the gate on that flag worth nothing, and
`delete` additionally hard deletes the report's retained scheduled snapshots. A one-factor session
would otherwise silence a report and destroy its snapshot history in a single request. Refusals are
audited the same way, and an ordinary member deleting their own report's schedule is unaffected.

**Reading a schedule is not gated.** `ScheduleService.get` needs only `REPORT_READ`, so a
one-factor session can see an existing schedule's recipients, run-as identity and cadence. That is
consistent with the report's own read permission and is recorded here so it is not mistaken for an
oversight.

**Support bundle download is gated at the service boundary.** `SupportBundleService.generate`
requires organization administration and then `requireRecentAuthentication`, so
`POST /api/orgs/{orgId}/support-bundle` already needs a fresh WebAuthn assertion. It is absent from
the filter's path list because the service gate is the control, not because the surface is exempt.

**The tenant workspace ZIP export is gated at grant issue, not at grant redemption.**
`TenantExportService.prepare` and `TenantExportGrantService` both require organization
administration plus `requireRecentAuthentication` before a download grant exists. Redemption then
authorizes on the single-use, session-bound grant token rather than on a second step-up: the grant
is the narrower authority, it is bound to the session that requested it, and requiring a fresh
assertion again on a streaming download would only re-prove what issuing the grant already proved.

## Operator configuration

`CONNEX_PRIVILEGED_MFA_ENFORCED` defaults to `true`. Only the case-insensitive value `false`
disables the added confinement and export filter; an absent, blank, or unparseable value is
enforced. Set `CONNEX_PRIVILEGED_MFA_CHANGE_ACTOR` to the operator or approved change identifier
whenever explicitly changing the flag. Startup refuses `false` when that actor is absent or still
the `configuration-default` placeholder. Every backend start writes the effective enforcement and
first-passkey confirmation values and the actor to the integrity-chained system audit log as
`auth.mfa.policy.configured`. The public
`GET /api/capabilities` response exposes the effective `privilegedMfaEnforced` value.

Disabling this flag is a staged-rollout exception, not an MFA recovery mechanism. It does not make
password proof satisfy the existing high-risk service gates.

`CONNEX_PRIVILEGED_MFA_ATTESTATION_ENFORCED` defaults to `true` and applies
[attestation enforcement](#standing-and-attestation-enforcement). A staged-rollout or policy-rollback
exception sets it to `false` with `CONNEX_PRIVILEGED_MFA_CHANGE_ACTOR` naming the operator or approved
change. Startup refuses that opt-out when the actor is absent, blank or still `configuration-default`,
and refuses any value other than `true` or `false` (case-insensitive). Reads fail closed before
validation, so a mistyped value cannot silently leave it off. The
`auth.mfa.policy.configured` event records it as `configuredAttestationEnforcedValue` and
`attestationEnforced`.

### Unenrolled privileged inventory

Every backend start also counts the accounts that are privileged under the definition above and
hold no passkey. These are the accounts confinement holds at enrollment. The same
`auth.mfa.policy.configured` event records two counts:

- `unenrolledPrivilegedCount` — every such account.
- `unenrolledWithoutSelfServiceCount` — the accounts among them that cannot enroll on their own.
  These are password-backed accounts that need the
  [emailed first-passkey confirmation](#first-passkey-enrollment-confirmation) but cannot receive
  it: the account has no email address, or this instance cannot deliver the confirmation. The count
  is zero when the confirmation is disabled, because the password alone then suffices. Passwordless
  accounts are never counted here, because they enroll after a fresh federated sign-in.

The inventory is taken once the backend is ready, after bootstrap owner provisioning
(`CONNEX_BOOTSTRAP_ENABLED`) has run. On a fresh install the founding owner is privileged and has no
passkey yet, so it is counted on the boot that creates it. The event is recorded at the same point.
Configuration is still validated earlier in startup, and a failure to record the event still fails
startup.

When the first count is above zero, the backend also logs one `WARN` line that names at most 50
account ids, lowest id first. The line carries no email addresses or credential material. The
inventory is read-only rollout guidance. A non-empty population never blocks startup. If the
inventory query fails, the event records `unenrolledPrivilegedInventory: unavailable` instead of the
counts, and startup continues.

To drain the list before it becomes support tickets, have each account enroll a passkey. Accounts
that can self-serve enroll from their security settings. Accounts counted as unable to self-serve
need one of the routes in [If the confirmation cannot be completed](#if-the-confirmation-cannot-be-completed):
restore mail delivery, remove their privilege, or use [break-glass recovery](#break-glass-recovery).

## First-passkey enrollment confirmation

Confinement makes enrollment the only door an unenrolled privileged account can walk through, so
the proof required to enroll is the proof protecting every privilege that account holds. A current
password alone is not enough: an attacker who steals it could enroll their own passkey and receive
the step-up stamp. Enrolling a **first** passkey on a password-backed account that currently holds
privilege therefore also requires a single-use confirmation emailed to the account's own address.

The bearer is 256-bit, hashed with SHA-256 at rest, single use, expires in 30 minutes by default,
and travels in the link **fragment**, so it never reaches the server in a URL or a `Referer`
header. Redemption is bound to the account **and** to the stable `SPRING_SESSION.PRIMARY_ID` of the
session that requested it. That binding is the point: without it, an attacker holding the password
could request a confirmation and have the legitimate owner's click authorize the attacker's waiting
session. The owner must therefore open the link in the same browser they started enrolling in.

The requirement is evaluated at both ceremony phases and again under the account lock inside
`WebAuthnService.finishRegistration`, so an account promoted between issuing options and verifying
the attestation cannot complete an unconfirmed privileged enrollment.

It is independent of `CONNEX_PRIVILEGED_MFA_ENFORCED`. That flag governs confinement, but a first
enrollment stamps the session as stepped-up either way.

Passwordless accounts are excluded. They prove bootstrap with a freshly established, same-account
federated session rather than a replayable secret, so a stolen password does not reach them.

### Operator configuration

```dotenv
CONNEX_PRIVILEGED_MFA_BOOTSTRAP_CONFIRMATION_ENABLED=true
CONNEX_PRIVILEGED_MFA_BOOTSTRAP_CONFIRMATION_EMAIL_ENABLED=true
CONNEX_PRIVILEGED_MFA_BOOTSTRAP_CONFIRMATION_BASE_URL=https://app.example.com
```

Disabling first-passkey confirmation requires `CONNEX_PRIVILEGED_MFA_CHANGE_ACTOR` to identify the
accountable operator or approved change. An absent, blank, or `configuration-default` actor refuses
policy initialization, including when the main MFA enforcement switch remains enabled. The strict
startup audit records the effective setting as `bootstrapConfirmationEnabled`; failure to write
that event fails startup. An attributed disable permits password-only first-passkey enrollment and
its automatic step-up, so it is an explicit policy exception rather than an account-recovery route.

The confirmation is fail-closed and requires a working instance sender (`connex.mail.*`). **An
instance that leaves mail unconfigured cannot complete the emailed confirmation**, so the request
endpoint refuses with `MAIL_TRANSPORT_UNAVAILABLE` rather than silently promising an email that will
never arrive. That is not a dead end: operator-authorized break-glass recovery enrolls a privileged,
password-backed account without email, including one that has never enrolled, and the routes below
say when to reach for it. Configure a sender anyway — break-glass costs an out-of-band operator
token and a support round trip, so it is an incident path, not an onboarding one. The `dev` profile
disables the requirement and attributes it to `local-development`, because local development has no
SMTP. An explicitly supplied change actor overrides that local default; never enable `dev` in
production.

### If the confirmation cannot be completed

- **Link expired, consumed, or opened in the wrong browser** — request another from the security
  settings page and open it in the enrolling browser. Requests are throttled to five per fifteen
  minutes per account.
- **Mail transport down or misconfigured** — repair the transport and retry. No restart is needed
  when an already-configured transport simply recovers.
- **Mailbox unreachable, or mail never configured** — another organization or workspace
  administrator removes every privileged role the stuck account holds. Policy is read from current
  rows on every request, so the account stops being confined immediately, enrolls with its password
  alone, and can be re-promoted afterwards. Re-promotion with an uncovered passkey requires the
  confinement acknowledgement (`409`); administrative access resumes only after a code from a
  covered grantor approves the same passkey in every privileged organization. Use
  [break-glass recovery](#break-glass-recovery) when there is no eligible grantor. This needs no
  restart. Note that `isPrivilegedAccount`
  is an OR across organization membership, built-in workspace admin/owner roles, and custom roles
  carrying administrative permissions — every source must be removed.
- **Sole organization or workspace owner, with mail unusable** — the last owner cannot be demoted,
  so no other administrator can clear the confinement. **Break-glass recovery is the route here.**
  The ceremony accepts an account with nothing to remove: it advances the session epoch and writes
  the durable epoch-restamp grant, and that grant authorizes the replacement enrollment without
  email. The operator supplies the recovery token out of band, which is the second factor. Restoring
  mail delivery and setting `CONNEX_PRIVILEGED_MFA_BOOTSTRAP_CONFIRMATION_ENABLED=false` remain
  available, but neither is required, and both are configuration changes needing a restart where
  break-glass is not.

A session that has just completed operator-authorized break-glass recovery is treated as already
confirmed, through the durable epoch-restamp grant that names that session and survives until the
replacement credential commits. The recovery token is itself an out-of-band operator factor, so
replacement enrollment after recovery is never blocked on email. This holds whether or not the
account had a credential to recover: removing nothing is a legitimate outcome of the ceremony, so it
also bootstraps an account that has never enrolled.

### Email change on a privileged account

The confirmation is delivered to the account's own email address, so the control is only as strong
as that address. `POST /api/users/me/email-change` therefore requires more than the current
password when the account is privileged. The request is refused unless the account holds a passkey
and the session carries a fresh WebAuthn step-up:

- **No passkey:** the request fails with `400 PASSKEY_ENROLLMENT_REQUIRED`.
- **Passkeys awaiting approval,** under the default attestation enforcement: the request fails with
  `403 PRIVILEGED_MFA_ATTESTATION_REQUIRED`.
- **Passkey but no fresh step-up:** the request fails with `403 RECENT_AUTHENTICATION_REQUIRED`.
  The web client runs the passkey step-up and retries automatically.

Each refusal is audited as `auth.email_change.refused`, with the reason
`privileged_mfa_enrollment_required`, `privileged_mfa_attestation_required` or
`recent_authentication_required`. Because privilege is account-wide, these rows use explicit null
`workspace_id` and `org_id` and land on the `system` chain (`chain_scope_id = 0`), regardless of
workspace selection by header, cookie, remembered workspace or first membership (#1997). Readers
and alerting must look for these refusals on the `system` chain. Existing tenant-scoped rows remain
in their original chains; the append-only log is not backfilled. Pre-lock refusals are appended
immediately; refusals first observed under the account lock are appended after the request's
transaction completes. Unprivileged accounts change their email with the current password alone,
as before.

The gate is independent of `CONNEX_PRIVILEGED_MFA_ENFORCED`. With enforcement on, confinement
already refuses the endpoint for an unenrolled privileged account. With enforcement off, this gate
is what stops an attacker who holds a stolen password from re-pointing the address. Without it, the
attacker could receive the enrollment confirmation and enroll their own passkey. Only a WebAuthn
ceremony writes the step-up stamp, so a password, OIDC, SAML, or social sign-in never satisfies it.
Privilege is read again under the account lock, after the account's assigned custom roles are
locked, so a promotion that commits while the request waits is also refused.

**Lockout path.** A privileged account that has never enrolled cannot change its email until it
holds a passkey. This applies even when `CONNEX_PRIVILEGED_MFA_ENFORCED=false`. The account first
enrolls through the emailed confirmation sent to its current address. When that address is
unreachable, the account uses [break-glass recovery](#break-glass-recovery) instead, or another
administrator removes its privilege, as described in
[If the confirmation cannot be completed](#if-the-confirmation-cannot-be-completed). Once the
account holds a passkey, or no longer holds privilege, it can change its email.

**Remaining residual.** This gate covers accounts that are privileged when the change is requested.
It does not close three routes, which stay open under #1506 (Part B) and #1534:

- privilege can still be granted to an account that has never enrolled;
- a passkey enrolled before a promotion still counts after it, even if a stolen password enrolled
  that passkey while the account was unprivileged; and
- an email-change link requested while the account was unprivileged can still be confirmed after a
  promotion. Confirmation re-checks the session epoch and address uniqueness but not privilege, so
  a stolen password can request the link to an address it controls, and the change applies if the
  account is promoted within the link's lifetime (`CONNEX_EMAIL_CHANGE_TOKEN_EXPIRY_MINUTES`, 30
  minutes by default).

## Break-glass recovery

Connex has no cross-account administrative MFA reset endpoint. An organization or workspace
administrator cannot remove another account's passkeys, which avoids allowing authority in one
tenant to reset a credential used in another tenant. The only recovery path is an authenticated
user recovering their own account with both:

1. the account's current password, or a freshly established same-account federated session for a
   passwordless account; and
2. a random, out-of-band token that an operator issues for that one account and configures only
   as a SHA-256 digest.

### Issuing a recovery token

A token is bound to one account and works exactly once.

1. **One token per account.** Look up the recovering account's numeric user id, then generate a
   fresh random token for it. Never reuse a token across accounts or incidents. The backend holds
   one recovery digest at a time, so recovering two accounts takes two issue-and-restart cycles.
2. **Compute the subject-bound digest.** The digest covers a purpose prefix, the user id, and the
   token. A plain SHA-256 of the token alone is refused.

   ```bash
   USER_ID=1234
   TOKEN="$(openssl rand -hex 32)"
   printf 'connex-privileged-mfa-recovery:v1:%s:%s' "$USER_ID" "$TOKEN" | sha256sum | cut -d ' ' -f 1
   ```

3. **Configure and restart.** Set all three values from the digest above and restart the backend:

   ```dotenv
   CONNEX_PRIVILEGED_MFA_RECOVERY_TOKEN_SHA256=REPLACE_WITH_64_HEX_SHA256
   CONNEX_PRIVILEGED_MFA_RECOVERY_EXPIRES_AT=2026-08-13T12:30:00Z
   CONNEX_PRIVILEGED_MFA_RECOVERY_ACTOR=incident-1234/operator-name
   ```

4. **Hand the raw token to the account holder out of band.** It is accepted only for the account
   whose id is in the digest.
5. **Clean up.** After the ceremony completes, clear the three variables and the shell's `USER_ID`
   and `TOKEN` variables, then restart the backend.

The expiry must be in the future and no more than one hour from backend startup. The actor must be
at most 255 characters, the size of the operator column in the redemption ledger described below,
so the ledger records the same actor as the audit event. An incomplete, malformed, expired, or
longer-lived configuration fails startup, and so does a longer actor. At runtime the recovery
request is rejected after expiry. The raw token is submitted to
`POST /api/auth/webauthn/recover`; it is never configured, persisted, logged, audited, or included
in an error. Token comparison uses the configured digest and constant-time comparison.

These cases are all refused with the same message and remove nothing:

- a wrong or blank token;
- an expired token;
- a token issued for a different account; and
- a token that has already been redeemed.

The first successful ceremony records the token in the control-plane
`privileged_mfa_recovery_redemption` ledger. The ledger stores a SHA-256 of the configured digest,
never the token or the digest itself. From then on the token is spent, including after a restart and
on every other backend replica. The ledger row is written in the recovery transaction, so a ceremony
that fails part-way leaves the token usable for a retry. To recover the same account again, issue a
new token.

Successful recovery locks the account and all credential rows, removes all passkeys, clears the
session's recent-MFA stamp, and writes `auth.mfa.recovery.used` with the recovering user, operator,
and credential count in the same transaction. An audit write failure rolls the removal and the
redemption back. A failed account or operator proof writes a sanitized `auth.mfa.recovery.denied`
event after the recovery transaction rolls back. Its reason is `token_already_redeemed` when the
password (or fresh federated session) and the token were both valid for the account but the token
was already spent, and `proof_rejected` for every other refusal. Only someone holding both factors
can produce a `token_already_redeemed` event, so investigate one unless the account holder simply
retried a completed ceremony. The client response is the same in both cases. A privileged user is immediately confined to
enrollment after recovery. Normal passkey removal requires recent WebAuthn proof and refuses removal
of a privileged account's last credential.

## Passkey provenance (#1534)

A passkey enrolled while an account held no privilege must not, on its own, satisfy this policy after
a later promotion: an attacker holding only the password of an ordinary account could enroll one and
wait. The remedy is grantor-mediated attestation per organization, delivered in slices. Provenance
is recorded for every passkey, and [standing](#standing-and-attestation-enforcement) reads it.
This release enforces attestation by default, so standing confines accounts whose passkeys still
await approval.
[Grantor vouching](#attestation-codes) applies whatever that switch from the release carrying V225.

### What a passkey can carry

**Account-wide assurance** sits on `webauthn_credential` and comes only from operator-trusted
sources:
- `BREAK_GLASS` is recorded when the operator recovery session itself enrolls the replacement
  passkey. The enrolling session's `SPRING_SESSION.PRIMARY_ID` and epoch must match the restamp grant
  that recovery issued, so another session of the same account cannot claim it by enrolling first.

**Per-organization coverage** sits in `privileged_credential_attestation`:
- **`FOUNDER`** covers the passkeys of an organization's founding owner.
  - Founding covers the owner's existing passkeys.
  - A passkey enrolled later is covered while `org_member.founder` is set and the account still owns
    the organization.
  - A role change that another account makes clears the flag. That is not revocation: existing
    rows remain.
- **`INHERITED`** applies when a passkey is enrolled after a fresh step-up. The new passkey copies the
  coverage and assurance of the passkey that signed the step-up, provided that passkey still belongs
  to the account. The step-up stamp names its passkey in a binding tied to the stamp's account and
  time, so a stamp refreshed without that binding yields no inheritance.
- **`GRANTOR`** is written when the account redeems an attestation code with that passkey (below).
- **`GRANDFATHERED`** records the per-organization trust assigned by V225 to passkeys of accounts
  privileged in that active organization at cutover.

**Rules:**
- A direct source (`FOUNDER`, `GRANTOR`, `GRANDFATHERED`) is never replaced by `INHERITED`.
  A `GRANTOR` redemption replaces an `INHERITED` row and leaves an existing direct row exactly as it was.
- An organization already being torn down receives no new coverage.
- Coverage is deleted with its passkey or its organization.

### Attestation codes

A grantor issues a one-time code, and the member redeems it with a passkey assertion. The passkey
that signs the redemption gains `GRANTOR` coverage in the code's organization.

**Who may issue or revoke a code:**
- **Through a workspace**
  (`POST`/`DELETE /api/workspaces/{id}/members/{userId}/mfa-attestations`): someone who could grant
  the member's *current* role there.
  - A built-in role needs `MEMBER_MANAGE`, and a custom role `ROLE_MANAGE`.
  - Each is held under the grant ceiling: the grantor must hold every permission the role confers.
  - Only an owner may attest an owner.
- **As an organization owner**
  (`POST`/`DELETE /api/orgs/{orgId}/members/{userId}/mfa-attestations`), for a member holding an org
  role.

Both need a fresh step-up, and nobody can issue a code to themselves. The authority is the member's
*local* role: a delegate who could grant that role there can attest the member, and the coverage
then counts wherever the member is privileged in that organization.

**Grantors vouch org-locally.** From the release carrying V225, this applies whatever
`CONNEX_PRIVILEGED_MFA_ATTESTATION_ENFORCED` says. Issuing or revoking checks that the grantor's
step-up is fresh on entry. Under the authority locks it then requires the stamp's passkey to be the
grantor's own and covered in the code's organization (or `BREAK_GLASS`): vouching rests on that
organization alone. Redemption refuses `grantor_unattested` once the grantor holds no such passkey
there. V225 grandfathers every passkey of every account privileged at cutover in each active
organization where it is privileged, so legitimate cutover grantors can vouch from the first request.
A new organization's founder also receives coverage. Requiring vouching during the dark period
prevents later promotions from creating permanent `GRANTOR` coverage through unapproved passkeys.
Until the web approval flow ships, an uncovered grantor receives today's
`403 RECENT_AUTHENTICATION_REQUIRED` at issuance or revocation.

**The code:**
- It is 80 random bits, shown to the grantor once as four groups of Crockford base32. It is never
  stored, logged or audited, only a SHA-256 digest bound to the grantee, so it can't match anyone
  else.
- It expires after 24 hours.
- A new code for the same member and organization supersedes the open one.
- Revoking removes the open code. It never removes coverage already redeemed.

**Redemption** (`POST /api/auth/webauthn/attestation/options`, then
`POST /api/auth/webauthn/attestation` with `{code, credential}`) runs in separate committed steps:
1. The passkey assertion is verified on its own, advancing the passkey's counter.
2. A redemption transaction takes the grant's authority locks and re-checks, under them, that:
   - the grantor still holds the authority;
   - the member is still a member;
   - the grantor still holds a covered passkey in the organization or one carrying `BREAK_GLASS`;
   - the session's epoch is current;
   - the passkey is still the member's.

   It then claims the grant and records the coverage.
3. On success, the session's step-up is stamped with the attested passkey.

**Refusals:**
- Every reason the code can't be used answers one `403 MFA_ATTESTATION_REFUSED`: wrong, expired,
  revoked, used, exhausted, authority lost, member gone, or a grantor
  that no longer holds a passkey covered in the code's organization.
- After the refused redemption has rolled back, one refusal is counted against each of the member's
  redeemable codes. A code that reaches five refusals can't be redeemed.
- The denial is audited at account scope with its reason.
- Not part of that contract:
  - a failed assertion, which fails as the ceremony's own error and counts nothing;
  - a crash between the rollback and the count, which is an accounting gap.
- Responses are uniform; timing is not made uniform.

**Confinement:** the two redemption routes are reachable by a confined account. Issuing and revoking
are not.

**History** lives in the strict `auth.mfa.attestation.issued`, `.revoked`, `.redeemed` and `.denied`
audits. A grant row goes with its organization, workspace, grantor or grantee, and coverage it
produced keeps a null `grant_id`.

### Standing and attestation enforcement

Every account has one **standing**, read in a single statement:

| Standing | Meaning |
| --- | --- |
| `UNPRIVILEGED` | The account holds no privilege. |
| `SATISFIED` | The account is privileged and holds a sufficient passkey. |
| `ENROLLMENT_REQUIRED` | The account is privileged and holds no passkey. |
| `ATTESTATION_REQUIRED` | The account is privileged and holds passkeys, but none is sufficient. |

A passkey is **sufficient** when it carries account-wide `BREAK_GLASS` assurance, or when it alone
is covered in **every** organization where the account is privileged. Grandfathering is
per-organization coverage, so `GRANDFATHERED` assurance, which nothing writes, does not count.
Coverage split across passkeys does not count: one covering organization A and another covering B
leave the account `ATTESTATION_REQUIRED`. An organization or workspace being torn down confers no
privilege, so it needs no coverage either.

**During an attributed staged-rollout or policy-rollback exception** with
`CONNEX_PRIVILEGED_MFA_ATTESTATION_ENFORCED=false`, `ATTESTATION_REQUIRED` is enforced as `SATISFIED`
everywhere. A privileged account with any passkey is not confined.
Grantor vouching still applies to approval issuance, revocation and redemption, as described above.

**With the default `true`**, an `ATTESTATION_REQUIRED` account is refused wherever an unenrolled one is:
- the browser API confines it with `403 PRIVILEGED_MFA_ATTESTATION_REQUIRED`, audited as
  `auth.mfa.policy.denied` with reason `attestation_required`, behind the same allowlist (including
  the two approval-code redemption routes);
- the public API refuses its tokens with `401 privileged_mfa_attestation_required`;
- an email change is refused, as above;
- removing the account's last sufficient passkey is refused, even while other passkeys remain,
  because the removal would confine the account. The refusal is audited as
  `auth.passkey.delete_denied` with reason `last_sufficient_credential`.

**Proofs must come from a sufficient passkey, too.** Under the default enforcement, a privileged account's
step-up counts only when the session's stamp is bound to one of its own **sufficient** passkeys:
- **Where it applies:** every recent-authentication gate (`requireRecentAuthentication`) and the
  export gate. Otherwise they answer `403 RECENT_AUTHENTICATION_REQUIRED`.
- **What doesn't count:** a stamp from passkey login or from redeeming an approval code counts only
  if that passkey is sufficient, and a stamp that names no passkey never does.
- **Step-up options** (`/api/auth/webauthn/step-up/options`) offer a privileged account only its
  sufficient passkeys. With none, it gets `403 PRIVILEGED_MFA_ATTESTATION_REQUIRED` rather than an
  empty list.
- **Approval-code options** (`/api/auth/webauthn/attestation/options`) offer every passkey the
  account owns, since an unapproved passkey is what a code approves.
- **Empty lists, always:** both refuse an empty list whatever the switch, because recovery can remove
  every passkey between the existence check and the list being built, and an empty list would
  admit any discoverable credential.
- **Adding a passkey** to an account that already holds one is re-authorized under the account
  locks. The step-up behind the ceremony must still be fresh, signed by a passkey the account still
  owns and, for a privileged account, sufficient. An account awaiting approval therefore cannot add
  passkeys. The first passkey is the exception, and operator recovery leads back to it.

`GET /api/auth/webauthn/register/requirements` stays reachable while confined. It reports the
account's enforced `standing`, and `approvalGaps`: for each of the account's own passkeys without
`BREAK_GLASS` assurance, every organization where the account is privileged and that passkey is not yet
approved (`credentialId` in the base64url form the passkey list uses, `orgId`, `orgName`). A client
re-reads it after each ceremony, and releases confinement only on `SATISFIED` or `UNPRIVILEGED`.

The same self-only response always includes `privilegedMemberships` (#1534 slice 4b-1),
independent of both enforcement switches. An unprivileged account receives `[]`. Each entry is
`{orgId, orgName, orgRole, workspaces:[{workspaceId, workspaceName}]}`. `orgRole` is explicitly
`null` without an organization membership. Entries and workspaces are ordered by id. Sources are
exactly the shared standing privilege branches: active organizations, active workspaces and active
workspace memberships; built-in owner/admin only when `role_id IS NULL`; custom roles with the
management permission list above; and organization authority from `org_member`. Permission joins
are deduplicated, so each workspace appears once. Only the signed-in account's memberships are
returned, including custom-role workspace exits and org-only privilege with no workspace exits.

The startup event's inventory also records `attestationRequiredCount`: the privileged accounts
holding passkeys of which none is sufficient. These are the accounts that enforcement confines.
Beside it, `grandfatheredCoverageCount` counts the per-organization coverage rows whose source is
`GRANDFATHERED`, recording how far the cutover reached. Both are counted whether or not enforcement
is on, so an attributed staged rollout can drain and freshly recheck the population before enabling.

A workspace-only owner who is not an organization member and held no passkey at cutover gains no
founder coverage by enrolling later. If no covered co-owner can approve that passkey through the
workspace route, the approval path is [operator break-glass recovery](#break-glass-recovery): its
replacement passkey carries `BREAK_GLASS` assurance and satisfies standing in every organization.

**This release switches enforcement on by default.** The cutover below, ownership offers and the
web client's approval flow have shipped. Every upgraded control catalog needs a fresh
[preflight and drain](UPGRADING.md#privileged-mfa-attestation-switch-on-1534) immediately before
enabling; an old startup without a warning does not establish current readiness.

### Member approval badges

`GET /api/workspaces/{id}/members` and `GET /api/orgs/{orgId}/members` optionally include
`passkeyApprovalNeeded: boolean` on each row. It is true only if that account is privileged in
**this organization** and none of its own passkeys has coverage here or `BREAK_GLASS` assurance.
The set reads reuse `privilegedInOrganizationAmong` and `coveredInOrganizationAmong`; coverage
uses the same per-org attestation and break-glass facts as standing's sufficiency predicate.
Privilege or coverage elsewhere never decides this value. This does not expose account-wide
standing: split coverage can satisfy each local badge while no single passkey satisfies standing.
Eligible non-privileged rows carry `false`.

The field is omitted (`Boolean` null with `NON_NULL`) unless the caller has approval issuance
authority for that row. The caller needs current authority in the active scope, no deletion
reservation, and an owned passkey approved in this organization. Workspace issuance uses the
shared role-authority predicate: `MEMBER_MANAGE` for built-in targets, `ROLE_MANAGE` for custom
roles, the target's full permission set under the grant ceiling, and owner authority for owner
targets. Organization issuance requires org ownership. Self, pending and deletion-reserved targets
are ineligible. A caller without issuance authority sees no field on any row. These reads take no
locks, append no audit and perform no writes. They do not require a fresh step-up; issuing the code
still requires a fresh assertion from a passkey approved here and revalidates authority under locks.

Badges shipped before switch-on and now support ongoing approval and recovery under the default
enforcement, as well as draining gaps during an attributed opt-out. Each nonempty authorized list uses two set queries for privilege and coverage, plus a
constant number of authority reads. Workspace custom-role roots and permissions are read with one
join, including roles with no permissions; the query count does not grow with members or roles.

### Organization tenancy and safe exits

Confinement is account-wide, so two owner decisions keep it from becoming a lever one organization
holds over an account.

**Existing tenancy.**
- A new organization membership (owner or admin) is granted only to an account that is already an
  **active member of an active workspace in that organization**. Anyone else first accepts a
  workspace invite.
- This applies to every path that creates one: `POST /api/orgs/{orgId}/members` (by email) and
  `PUT /api/orgs/{orgId}/members/{userId}`.
- Every add that fails before the account is known to be a tenant answers with the same
  `400` "Only active members of a workspace in this organization can be added to it". That covers an
  unknown email or id, an outsider, a pending member and a member only of a workspace being torn
  down, so the endpoints don't reveal which accounts exist. The role is validated before anyone is
  looked up, and one organization-scoped read refuses an unknown account and an outsider alike
  before any lock is taken, so the cost of the answer doesn't tell them apart either.
- Re-roling or removing an existing organization member is unaffected.
- Founding an organization and the dev seeder write the founder's membership directly, and are the
  only exceptions.

**Safe exits.** A confined account can always give up the membership that confines it:
- `POST /api/workspaces/{id}/leave` leaves a workspace, as before.
- `POST /api/orgs/{orgId}/leave` leaves an organization. It is self-only and needs no step-up,
  since leaving only gives up authority and a confined account cannot produce a sufficient
  step-up. The last owner is refused.

Both routes stay reachable while confined, whatever the standing. Both exits are audited strictly
(`workspace.member.leave`, `org.member.leave`): an exit that cannot be recorded does not happen.
After either exit the client re-reads standing, since other memberships may still confer
privilege.

Departure refusals retain their HTTP status and existing `message`, and include a stable `code`
for client localization:

| Route | Refusal | HTTP | Code |
|---|---|---|---|
| Workspace leave | Last active owner | 400 | `WORKSPACE_LAST_OWNER` |
| Workspace leave | Last owner able to restore authority | 400 | `WORKSPACE_RECOVERY_OWNER_REQUIRED` |
| Workspace leave | Sole named document approver on an active policy or pending approval | 409 | `SOLE_NAMED_DOCUMENT_APPROVER` |
| Organization leave | Not a member (including the preliminary route check) | 404 | `ORG_NOT_MEMBER` |
| Organization leave | Sole organization owner | 400 | `ORG_LAST_OWNER` |

The owner and document-approver domain exceptions are shared with existing removal, role-edit or
account-erasure guards where those same conditions apply. Their codes describe the invariant,
rather than claiming that every refusal came from a leave request. Their status, message, locking
and success behavior are unchanged. Other standard boundary failures retain their existing codes
(e.g. `RESOURCE_NOT_FOUND`, `FORBIDDEN`). A departure `409` never requests a confinement
acknowledgement; clients match the acknowledgement code specifically.

### Acknowledging confinement in role edits

Once enforcement is on, a role edit can make an account privileged in an organization that has
approved none of its passkeys. That account is confined from its next request. These edits refuse
to do that silently:
- `PATCH /api/workspaces/{id}/members/{userId}`, with a built-in role or a custom `roleId`;
- `PUT /api/workspaces/{workspaceId}/roles/{roleId}`, which counts every assignee of the role;
- `POST /api/orgs/{orgId}/members` and `PUT /api/orgs/{orgId}/members/{userId}`.

**What is counted.** The accounts the edit makes **newly privileged in the organization** (privileged
there after the write but not before) that hold no passkey approved there and none with
`BREAK_GLASS` assurance. Such an account will be confined, or stays confined, until this
organization approves one of its passkeys.
- Pending members never count: they hold no privilege until they accept.
- A built-in role clears any custom overlay. Moving an owner whose overlay grants nothing privileged
  to built-in admin makes them privileged, so even an apparent demotion can count.

**Only this organization's facts decide.** Standing is account-wide, but the count is not. Counting
accounts that would become *confined* would tell this organization's administrators whether
another organization already confines a member. The count uses only privilege and approvals in
this organization, which its administrators can see. So it is not a prediction of standing:
- a member another organization already confines counts exactly like one it does not;
- a member with a passkey approved here does not count, even when their approvals are split
  across passkeys and the edit leaves them confined.

**The refusal.** The edit answers `409` and is rolled back whole: role, permissions, membership and
success audit.

```json
{
  "code": "PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED",
  "message": "This change leaves members without a passkey approved in this organization; they will be confined until one is approved",
  "affectedCount": 1
}
```

Sending the same edit again with `"acknowledgeConfinement": true` applies it. The check runs after
the write and before the success audit, and reads current state: the three workspace edits run at
`READ COMMITTED` for it (see [LOCKING.md](backend/LOCKING.md)). It is advisory. Confinement itself
is decided per request from standing, so an approval revoked after the edit can still confine an
account the edit did not count.

While the switch is `false`, nothing is read or counted, no edit answers `409`, and
`acknowledgeConfinement` is accepted and ignored.

### Rollback and the cutover

Provenance is additive evidence, not a complete history. A backend that predates provenance (slice 1)
records none of it. It:
- clears a recovery grant without recording break-glass assurance;
- refreshes step-up stamps without naming their passkey;
- changes org roles without clearing the founder flag.

**The cutover (V224, V225).** The release carrying these migrations applies them in a coordinated
restart, before the new backend serves (see [UPGRADING.md](UPGRADING.md)). It trusts exactly the
passkeys of the accounts that are privileged when it runs:
- V224 admits `GRANDFATHERED` as a coverage source.
- V225, in one transaction:
  1. recomputes every founder flag from the current owner rows, so existing organization owners are
     founders and nobody else is;
  2. deletes every coverage row whose passkey's account is not privileged in that organization at
     the cutover, whatever its source;
  3. covers every passkey of every account privileged at the cutover, in each organization where it
     is, with `GRANDFATHERED` where no row exists, keeping existing provenance;
  4. revokes every approval code still open.

From the same release, issuing, revoking and redeeming require the grantor's passkey to vouch in the
code's organization whatever the switch, so every coverage row written after the cutover traces back
to it. Account-wide `BREAK_GLASS` assurance comes from operator recovery, not a grantor, and is kept.

**Rollback floor.** After V225, no backend older than that release may serve. An older one skips
grantor vouching while the switch is off, so it could write coverage that nothing would remove, and
the oldest ones change roles without clearing the founder flag. If one did serve, follow the
reconciliation in [UPGRADING.md](UPGRADING.md) before reopening approvals.

**Switching on** happens in this release: attestation enforcement defaults to `true`, and disabling
it requires an accountable change actor. There is no schema change or second revocation. Immediately
before enabling, run the fresh preflight for every upgraded control catalog and drain
`attestationRequiredCount`, following [UPGRADING.md](UPGRADING.md#privileged-mfa-attestation-switch-on-1534).
One passkey must be approved in every organization where the account is privileged.

## Ownership consent (#1534 slice 4o)

Existing-scope owner promotions create offers; acceptance grants ownership. The HTTP, MFA, locking, control-plane, notice and cleanup contracts are in [Ownership offers](backend/OWNERSHIP_OFFERS.md). Founding, admin changes and last-owner checks retain their existing behavior. The self-offer card is authoritative on every catalog layout; ordinary inbox delivery retains the existing cross-catalog limitation.
