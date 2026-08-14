# Deployment editions

Connex ships as **one artifact**. What differs between a shared-SaaS tenant, a Connex-operated
silo, and a customer-run on-prem installation is the **deployment profile** selected at runtime,
not the build. `CONNEX_DEPLOYMENT_PROFILE` (`connex.deployment.profile`) is that selector.

Related: [DEPLOYMENT.md](DEPLOYMENT.md) for the operator runbook, [SECURITY.md](SECURITY.md) for the
posture rationale, [STAGING_DEPLOY.md](STAGING_DEPLOY.md) for the staging instance, and
[BREACHED_PASSWORD_SCREENING.md](BREACHED_PASSWORD_SCREENING.md) for remote and offline password
screening.

## The profiles

| Profile | Who runs it | Database | Intended posture |
|---|---|---|---|
| `saas` | Connex | Pooled, shared | Hardened. Internal-network escape hatches are refused outright. |
| `silo` | Connex, per customer | Dedicated | Hardened, but the operator may reach internal infrastructure. |
| `on-prem` | The customer | Customer-owned | Customer controls the network; Connex operates nothing. |

## Setting the profile is mandatory

A deployed instance **must** declare its edition. If `CONNEX_DEPLOYMENT_PROFILE` is unset, blank,
or not one of the three values, startup **fails loudly**:

```text
CONNEX_DEPLOYMENT_PROFILE must be set to saas, silo, or on-prem outside dev/test/seeder
```

This is deliberate. Posture enforcement that silently does nothing is worse than no enforcement,
because it reads as protection in a review. There is no soft-launch warning mode any more.

**Exempt:** the `dev`, `test`, and `seeder` Spring profiles. The seeder is exempt in the opposite
direction — `SeederStartupConfigurationValidator` *refuses* a set deployment profile, because a
fixture-loading run is not a deployment. Bean validation still rejects an invalid value everywhere.

The deployment templates already set it: [`deploy/silo.env.example`](../deploy/silo.env.example) and
[`deploy/onprem.env.example`](../deploy/onprem.env.example).
[`deploy/eval.env.example`](../deploy/eval.env.example) deliberately does **not** — an evaluation is
not an edition, and the `dev` Spring profile it activates is exactly what makes the value optional.
Leaving it unset keeps that template from reading as a seed for a real deployment, and makes the
backend refuse to start if anyone production-shapes it without choosing an edition. An evaluator who
wants to exercise profile behaviour sets the value themselves.

## What the profile controls

### 1. Settings each profile refuses

`saas` refuses four settings that would let an instance reach private network space. `silo` and
`on-prem` allow them, because reaching internal infrastructure is the point of those editions.
`on-prem` refuses instance-managed mail.

| Setting | `saas` | `silo` | `on-prem` |
|---|---|---|---|
| `connex.bootstrap.enabled` | forbidden | allowed | allowed |
| `connex.sso.allow-private-issuer-hosts` | forbidden | allowed | allowed |
| `connex.ai.allow-internal-endpoints` | forbidden | allowed | allowed |
| `connex.mail.allow-internal-hosts` | forbidden | allowed | allowed |
| `connex.mail.managed` | allowed | allowed | **forbidden** |

Setting a forbidden value to `true` fails startup:

```text
connex.deployment.profile=saas forbids: connex.bootstrap.enabled=true
connex.deployment.profile=on-prem forbids: connex.mail.managed=true
```

The on-prem managed-mail refusal is what keeps the advertised capability matrix and actual
behaviour consistent. `MailConfigResolver`, `WorkspaceMailConfigService`, and
`MailManagedController` read `MailProperties` directly rather than through the capability
registry — a deliberate, arch-test-sanctioned exemption. Without this refusal, an on-prem instance
with `CONNEX_MAIL_MANAGED=true` would advertise `mailManaged: false` while still routing mail
through the managed transport *and* locking the customer out of configuring their own SMTP. Failing
startup makes that divergent state unreachable rather than merely undocumented.

### 2. Capability × profile matrix

| Capability | `saas` | `silo` | `on-prem` |
|---|---|---|---|
| `SSO` | allowed | allowed | allowed |
| `SOCIAL_LOGIN_GOOGLE` | allowed | allowed | allowed |
| `SOCIAL_LOGIN_MICROSOFT` | allowed | allowed | allowed |
| `CONNECTED_ACCOUNTS_GOOGLE` | allowed | allowed | allowed |
| `CONNECTED_ACCOUNTS_MICROSOFT` | allowed | allowed | allowed |
| `CONNECTED_CAPTURE_GOOGLE` | allowed | allowed | allowed |
| `CONNECTED_CAPTURE_MICROSOFT` | allowed | allowed | allowed |
| **`MANAGED_MAIL`** | allowed | allowed | **forbidden** |
| `BUSINESS_CARD_SCANNING` | allowed | allowed | allowed |
| `BUSINESS_CARD_IMPORT` | allowed | allowed | allowed |
| `CAMPAIGN_DELIVERY` | allowed | allowed | allowed |

**Managed mail is the only profile-constrained capability.** It is mail transport Connex operates
on the customer's behalf, which cannot exist in an installation Connex does not run; an on-prem
operator configures their own SMTP instead. Every other capability is profile-neutral because its
availability is already decided by its own operator setting — the edition says nothing about
whether an operator wants SSO, social login, connected accounts, card scanning, or campaigns.

This row is enforced twice, on purpose: the registry refuses the capability, and the startup
validator refuses `connex.mail.managed=true` outright under `on-prem` (see above). An on-prem
instance therefore cannot reach a state where the two disagree.

**"Allowed" is not "enabled."** The profile gate is one of four. A capability is available only
when the profile permits it *and* entitlement permits it *and* rollout permits it *and* the
operator has configured it. `CAMPAIGN_DELIVERY` being allowed on every profile does not mean any
given instance has campaign delivery turned on.

Password breach screening is not an edition capability and cannot be disabled. Every edition uses
the remote HIBP k-anonymity source by default. A restricted-egress on-prem or silo operator selects
the verified offline source explicitly; the availability policy remains the same across editions.

The effective matrix for the running instance is logged once at startup:

```text
Deployment capability matrix: profile=on-prem, forbidden=[MANAGED_MAIL], allowed=[SSO, ...]
```

Grep for `Deployment capability matrix` to see what an instance actually decided, rather than
inferring it from source.

## Demonstrating the difference

`/api/capabilities` is public and reports the composed result, so the edition difference is
observable without a login:

```bash
curl -s http://localhost:8080/api/capabilities | jq .mailManaged
```

With `CONNEX_MAIL_MANAGED=true`, that is `true` under `saas` and `silo`. Under `on-prem` the same
variable is refused at startup, so an on-prem instance boots without it and reports `false`.

[`deploy-smoke.yml`](../.github/workflows/deploy-smoke.yml) enforces exactly this in CI: it boots
the built WAR under all three profiles — `saas` and `silo` with `CONNEX_MAIL_MANAGED=true`,
`on-prem` without it — and asserts the resulting `mailManaged` split, so collapsing the matrix back
to "everything everywhere" fails the build.

The fail-loud contract itself is covered by unit tests rather than a boot case, because the
profile-boot job runs with dev-relaxed database transport and the `dev` profile is exempt by
design — a negative boot there would pass for the wrong reason.

## Adding a capability

`CapabilityRegistry.FORBIDDEN_PROFILES` lists every capability explicitly, including the ones that
forbid nothing, and `CapabilityRegistryTest` pins the whole table literally. Adding a `Capability`
without deciding its profile policy fails that test. Update this document's matrix in the same
change.
