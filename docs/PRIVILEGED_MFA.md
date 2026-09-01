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

## First enrollment on an account that administers others

A password alone does not enroll the first passkey for an account that currently holds privilege in
a scope containing another principal. That account's password is exactly what the policy exists to
contain: without this refusal, a stolen password would enroll an attacker-held authenticator and,
through the step-up that enrollment grants, reach role changes, invitations, provider secrets, and
exports. The refusal answers `403` with `PRIVILEGED_PASSKEY_BOOTSTRAP_FORBIDDEN` and is recorded as
`auth.passkey.bootstrap.denied`. It is evaluated after the password itself has been verified, so it
never reveals which accounts are privileged to a caller who does not already hold the password.

An account that administers nobody but itself still enrolls with its password, and is recorded as
`auth.passkey.bootstrap.authorized` with a `sole_principal` grant. This carve-out is what keeps
first enrollment self-service: a self-serve registration provisions its own organization, workspace,
and owner membership in the same transaction, so such an account is privileged from the instant it
exists and holds no other credential. The carve-out cannot be manufactured, because the refusal is
evaluated across every scope the account holds privilege in — acquiring a further sole-member
workspace can only lose the exclusion, never earn it.

Three routes therefore exist for an account that does administer others and has no passkey:

1. an administrator removes that authority for the duration of enrollment, and restores it after;
2. an operator authorizes the recovery ceremony below, which grants the ceremony session the right
   to enroll a replacement; or
3. the deployment has not yet turned enforcement on, in which case nothing is refused.

An account that is the sole `owner` of a workspace that has other members has no first route,
because the last owner cannot be demoted. Its path is the operator ceremony.

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

## Break-glass recovery

Connex has no cross-account administrative MFA reset endpoint. An organization or workspace
administrator cannot remove another account's passkeys, which avoids allowing authority in one
tenant to reset a credential used in another tenant. The only recovery path is an authenticated
user recovering their own account with both:

1. the account's current password, or a freshly established same-account federated session for a
   passwordless account; and
2. a random, out-of-band token issued by an operator and configured only as a SHA-256 digest.

The ceremony removes whatever credentials exist, which may legitimately be none: it is an
authorization ceremony as much as a deletion, and an account that administers others but has never
enrolled uses it to obtain the right to enroll a first passkey. The grant it writes is bound to both
the account and the ceremony session, so a concurrent session for the same account cannot ride it,
and it is cleared once a replacement passkey is enrolled.

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
