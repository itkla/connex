# Privileged MFA policy and recovery

Connex requires a passkey for every account that currently holds administrative authority. The
policy is evaluated from current control-plane rows on every authenticated request; it is not
cached in the session. Promotion therefore takes effect on the next request, and demotion removes
the additional confinement on the next request.

## Privileged accounts

The policy treats these accounts as privileged:

- organization `owner` and `admin` members;
- active built-in workspace `owner` and `admin` members; and
- active custom-role members granted any of `AUDIT_READ`, `CAMPAIGN_MANAGE`, `CAMPAIGN_SEND`,
  `CONSENT_MANAGE`, `CUSTOM_FIELD_MANAGE`, `DOCUMENT_MANAGE`, `MEMBER_MANAGE`, `PIPELINE_MANAGE`,
  `PRODUCT_MANAGE`, `ROLE_MANAGE`, `RULE_MANAGE`, `SHARE_MANAGE`, `TAG_MANAGE`, or
  `WORKSPACE_SETTINGS`.

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
privacy exports, and passkey changes. The policy filter also covers every remaining CSV/data-export
surface: ordinary CRM exports, workspace and organization audit exports, campaign audience
exports, and report/snapshot exports. A password re-prompt never satisfies these gates. The recent
verification window defaults to ten minutes; an absent, zero, negative, or malformed duration
fails closed.

## Operator configuration

`CONNEX_PRIVILEGED_MFA_ENFORCED` defaults to `true`. Only the case-insensitive value `false`
disables the added confinement and export filter; an absent, blank, or unparseable value is
enforced. Set `CONNEX_PRIVILEGED_MFA_CHANGE_ACTOR` to the operator or approved change identifier
whenever explicitly changing the flag. Startup refuses `false` when that actor is absent or still
the `configuration-default` placeholder. Every backend start writes the effective value and actor
to the integrity-chained system audit log as `auth.mfa.policy.configured`. The public
`GET /api/capabilities` response exposes the effective `privilegedMfaEnforced` value.

Disabling this flag is a staged-rollout exception, not an MFA recovery mechanism. It does not make
password proof satisfy the existing high-risk service gates.

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

The confirmation is fail-closed and requires a working instance sender (`connex.mail.*`). **An
instance that leaves mail unconfigured cannot enroll a privileged, password-backed account at
all.** The request endpoint refuses with `MAIL_TRANSPORT_UNAVAILABLE` rather than silently
promising an email that will never arrive. The `dev` profile disables the requirement, because
local development has no SMTP; never enable `dev` in production.

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
- **Sole organization owner, with mail unusable** — the last owner cannot be demoted, so there is
  no in-application route. Restore mail delivery, or use break-glass recovery below. This is the
  one state that still requires operator intervention, and it is the reason mail must be
  configured before enabling the flag on a single-administrator instance.

A session that has just completed operator-authorized break-glass recovery is treated as already
confirmed. The recovery token is itself an out-of-band operator factor, so replacement enrollment
after recovery is never blocked on email.

### Known residual

When `CONNEX_PRIVILEGED_MFA_ENFORCED=false`, confinement is off and `POST /api/users/me/email-change`
is reachable. That endpoint is guarded only by the current password, so an attacker holding the
password could redirect the account address and then redeem the confirmation themselves. Under the
default enforced posture the email-change endpoint is refused for an unenrolled privileged account
and this path is closed. Tracked for the durable fix that makes privilege unobtainable until
enrollment completes.

## Break-glass recovery

Connex has no cross-account administrative MFA reset endpoint. An organization or workspace
administrator cannot remove another account's passkeys, which avoids allowing authority in one
tenant to reset a credential used in another tenant. The only recovery path is an authenticated
user recovering their own account with both:

1. the account's current password, or a freshly established same-account federated session for a
   passwordless account; and
2. a random, out-of-band token issued by an operator and configured only as a SHA-256 digest.

The operator must set all three values and restart the backend:

```dotenv
CONNEX_PRIVILEGED_MFA_RECOVERY_TOKEN_SHA256=REPLACE_WITH_64_HEX_SHA256
CONNEX_PRIVILEGED_MFA_RECOVERY_EXPIRES_AT=2026-08-13T12:30:00Z
CONNEX_PRIVILEGED_MFA_RECOVERY_ACTOR=incident-1234/operator-name
```

The expiry must be in the future and no more than one hour from backend startup. An incomplete,
malformed, expired, or longer-lived configuration fails startup. At runtime the recovery request
is rejected after expiry. The raw token is submitted to `POST /api/auth/webauthn/recover`; it is
never configured, persisted, logged, audited, or included in an error. Token comparison uses the
configured SHA-256 digest and constant-time comparison.

Successful recovery locks the account and all credential rows, removes all passkeys, clears the
session's recent-MFA stamp, and writes `auth.mfa.recovery.used` with the recovering user, operator,
and credential count in the same transaction. An audit write failure rolls the removal back. A
failed account or operator proof writes a sanitized `auth.mfa.recovery.denied` event after the
recovery transaction rolls back. A privileged user is immediately confined to enrollment after
recovery. Clear the three recovery variables and restart after the incident. Normal passkey removal
requires recent WebAuthn proof and refuses removal of a privileged account's last credential.
