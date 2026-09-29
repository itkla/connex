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
ceremony, and log out. Every other API request is refused by the backend with
`PRIVILEGED_MFA_ENROLLMENT_REQUIRED` until enrollment completes.

Password, OIDC, social login, and SAML establish an ordinary session but never a recent-MFA stamp.
An OIDC or SAML assertion may serve as the existing-account proof for first enrollment because the
session is newly established and bound to the same account. It does not satisfy a high-risk
operation. Only a verified WebAuthn assertion, a passkey login, or the just-completed passkey
registration ceremony creates the recent-MFA stamp.

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
  alone, and can be re-promoted afterwards. This needs no restart. Note that `isPrivilegedAccount`
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
- **Passkey but no fresh step-up:** the request fails with `403 RECENT_AUTHENTICATION_REQUIRED`.
  The web client runs the passkey step-up and retries automatically.

Both refusals are audited as `auth.email_change.refused`, with the reason
`privileged_mfa_enrollment_required` or `recent_authentication_required`. Unprivileged accounts
change their email with the current password alone, as before.

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
