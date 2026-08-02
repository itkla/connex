# Controlled Partner Admission Checklist

> **Status:** Internal per-engagement checklist. Copy this file's checklist into the engagement issue for **{{PARTNER}}** and work it through; do not edit the template in place to record one partner's state.
> **Not legal advice.** Contract, DPA, and notification obligations must be confirmed by counsel and by the signed agreement. Where a signed agreement and this checklist disagree, **the signed agreement governs**.
>
> **Owner:** {{OWNER}} · **Partner:** {{PARTNER}} · **Admission decision date:** {{DATE}}

Related: [INTERNAL_OPERATIONS_RUNBOOK.md](INTERNAL_OPERATIONS_RUNBOOK.md) for the mechanics behind
every step here, [DEPLOYMENT_EDITIONS.md](DEPLOYMENT_EDITIONS.md) for the deployment shapes,
[APPI_DPA_TEMPLATE.md](APPI_DPA_TEMPLATE.md) for the entrustment posture.

## Purpose and scope

A **controlled partner** is a named, contracted early customer — design partner or first
production customer — admitted to Connex **before general availability**, on either a
Connex-operated deployment or a customer-operated one. "Controlled" means three things at once:

1. **Named and contracted.** There is a signed agreement and an executed DPA with a specific legal
   entity. There is no self-serve path onto a controlled-partner deployment; provisioning is
   operator-driven (see [INTERNAL_OPERATIONS_RUNBOOK.md](INTERNAL_OPERATIONS_RUNBOOK.md)).
2. **Explicitly scoped feature posture.** Each capability is a deliberate on/off decision recorded
   below, not a default inherited from a template. Several capabilities are default-off and some
   are **not authorized for partner data at all** until external verification gates clear.
3. **Reversible.** Export and teardown are agreed and rehearsed before admission, not improvised at
   the end.

This checklist governs admission only. It does not replace the DPA, the security questionnaire
answers in [ENCRYPTION_GUARANTEE_MATRIX.md](ENCRYPTION_GUARANTEE_MATRIX.md), or the deployment
runbook in [DEPLOYMENT.md](DEPLOYMENT.md).

**Claim hygiene applies to every conversation in this engagement.** Access and encryption claims
must come from [ENCRYPTION_GUARANTEE_MATRIX.md](ENCRYPTION_GUARANTEE_MATRIX.md). The Connex backend
processes customer CRM content in plaintext to provide the service in every deployment shape,
including customer-operated on-prem. Do not describe hosted SaaS or a Connex-operated silo as
end-to-end encrypted, zero-knowledge, or customer-only-key encrypted, and do not tell a partner
that Connex "cannot see" their data.

## Admission checklist

### 1. Commercial and legal

- [ ] Signed commercial agreement with **{{PARTNER}}**'s legal entity, naming the deployment shape.
- [ ] **DPA (委託契約) executed**, derived from [APPI_DPA_TEMPLATE.md](APPI_DPA_TEMPLATE.md) and
      reviewed by counsel. Bracketed items completed; Connex legal entity named.
- [ ] **Entrustee posture understood by both sides and written down.** Connex acts as the
      **entrustee (委託先)**; **{{PARTNER}}** is the handling operator (個人情報取扱事業者) for the CRM
      content it loads, and therefore owns PPC reporting and individual notification. Confirm the
      partner's privacy team knows this, not just its procurement team.
- [ ] Subprocessor annex agreed for a Connex-operated deployment. For a customer-operated
      deployment, confirm in writing that the partner's own infrastructure, storage, key manager,
      and backup providers are **the partner's** responsibility and are not Connex subprocessors
      (DPA template §4).
- [ ] Processing-location and cross-border position recorded (DPA template §5).
- [ ] Encryption and key-custody statements taken verbatim from
      [ENCRYPTION_GUARANTEE_MATRIX.md](ENCRYPTION_GUARANTEE_MATRIX.md) — including that application
      exports are **plaintext at generation** and that the partner must protect them after download.

### 2. Deployment shape decided

- [ ] Shape chosen and recorded: `saas` / `silo` / `on-prem`. See
      [DEPLOYMENT_EDITIONS.md](DEPLOYMENT_EDITIONS.md) for what each one means.
- [ ] **`CONNEX_DEPLOYMENT_PROFILE` set to that exact value.** This is **mandatory** — an unset,
      blank, or unrecognized value **fails startup** with
      `CONNEX_DEPLOYMENT_PROFILE must be set to saas, silo, or on-prem outside dev/test/seeder`.
- [ ] Startup posture confirmed on the **real instance**, not inferred from the env file or source:

```bash
journalctl -u <backend-unit> | grep -E 'posture enforced|Deployment capability matrix'
```

- [ ] For `on-prem`: confirm **`CONNEX_MAIL_MANAGED` is not set to `true`** — the `on-prem` profile
      refuses it at startup, because instance-managed mail is transport Connex operates and cannot
      exist in an installation Connex does not run.
- [ ] For `saas`: confirm none of the internal-access opt-ins (`connex.bootstrap.enabled`,
      `connex.sso.allow-private-issuer-hosts`, `connex.ai.allow-internal-endpoints`,
      `connex.mail.allow-internal-hosts`) is `true`. Each one fails startup under `saas`.
- [ ] `connex.tenancy.routing.mode` left at `single-database`. `catalog-per-placement` is **not
      authorized for production** and must not be enabled for a partner.

### 3. Security prerequisites

- [ ] Every **fail-closed production variable** set and the instance verified to boot with them.
      The full enumerated list with enforcer and exact failure message is in
      [INTERNAL_OPERATIONS_RUNBOOK.md](INTERNAL_OPERATIONS_RUNBOOK.md); at minimum:
      `CONNEX_DEPLOYMENT_PROFILE`, `CONNEX_AUDIT_INTEGRITY_HMAC_SECRET` (≥32 characters),
      `CONNEX_SECRET_STORE_MASTER_KEY`, `CONNEX_DB_URL`, `CONNEX_DB_USERNAME`, `CONNEX_DB_PASSWORD`.
- [ ] **Database TLS verified.** `CONNEX_DB_URL` uses `sslMode=VERIFY_CA` or `VERIFY_IDENTITY`.
      Anything weaker is refused outside `dev`/`test`. See
      [DEPLOYMENT.md](DEPLOYMENT.md).
- [ ] **Warn-only settings deliberately decided, not defaulted by accident.** These do *not* fail
      startup and will silently degrade the engagement if skipped — see the "What is NOT
      fail-closed" section of [INTERNAL_OPERATIONS_RUNBOOK.md](INTERNAL_OPERATIONS_RUNBOOK.md):
      - [ ] `CONNEX_SECURITY_TRUSTED_PROXIES` set to the proxy/CDN/tunnel egress ranges. Unset
            behind a proxy makes per-IP rate limiting and audit source IPs worthless.
      - [ ] `CONNEX_METRICS_SCRAPE_TOKEN` set, or the partner has accepted that `/api/metrics` is
            unreachable by every caller.
      - [ ] `connex.delivery.public-base-url` set if campaign delivery is enabled.
- [ ] Session and workspace cookie `Secure` left at the fail-closed default (`true`); the `dev`
      profile is **not** active on the partner instance.
- [ ] **Backups installed** from the release deploy bundle and their timers enabled — see
      [BACKUP_RESTORE.md](BACKUP_RESTORE.md). Object storage
      (`/var/lib/connex/objects`) included in the backup media alongside MySQL.
- [ ] **Restore drill run and its result recorded.** A backup that has never been restored is not a
      backup. Record the restore date, the schema restored into, and the outcome. For a
      customer-operated deployment this drill is run by the partner; Connex has no access to their
      deployment and cannot run or verify it for them.
- [ ] Readiness gate wired to `GET /api/health/ready` (not TCP connectivity), with a timeout that
      accommodates multi-minute startup tasks on a large dataset.

### 4. Data protection

- [ ] **Special-care data policy acknowledged** — [SPECIAL_CARE_DATA_POLICY.md](SPECIAL_CARE_DATA_POLICY.md).
      Confirm **{{PARTNER}}** understands it must not load special-care-required personal
      information without a lawful basis and required consent, and that classification changes
      handling policy, not the encryption boundary for searchable CRM fields.
- [ ] **DSR routing agreed in writing** — [APPI_DATA_SUBJECT_REQUEST_PROCEDURE.md](APPI_DATA_SUBJECT_REQUEST_PROCEDURE.md).
      Who receives a 開示等 request, who logs it in the org-scoped register, and the response clock.
      Named contacts on both sides.
- [ ] **Breach co-notification path agreed** — [APPI_BREACH_RESPONSE_RUNBOOK.md](APPI_BREACH_RESPONSE_RUNBOOK.md).
      Connex notifies the partner promptly (target ≤ 24h); the partner reports to the PPC and
      notifies individuals as the handling operator. Confirm out-of-hours contact details for both
      directions and that they are reachable, not just recorded.
- [ ] Retention and deletion expectations recorded — [GOVERNANCE_DELETION_AND_RETENTION.md](GOVERNANCE_DELETION_AND_RETENTION.md)
      and the export-then-delete window in [APPI_DPA_TEMPLATE.md](APPI_DPA_TEMPLATE.md) §8.
- [ ] Audit reading understood: workspace audit at `GET /api/audit`, organization audit at
      `GET /api/orgs/{orgId}/audit`, both with `/export`.

### 5. Feature posture

Record an explicit decision per capability. **"Allowed by profile" is not "enabled"** — a
capability is available only when the profile permits it, entitlement permits it, rollout permits
it, *and* the operator has configured it. Every operator setting below defaults to **off** unless
noted.

| Capability | Operator setting | Default | Decision for {{PARTNER}} |
|---|---|---|---|
| `SSO` | `CONNEX_SSO_ENABLED` | off | |
| `SOCIAL_LOGIN_GOOGLE` | `connex.social-login.google.enabled` + client id/secret | off | |
| `SOCIAL_LOGIN_MICROSOFT` | `connex.social-login.microsoft.enabled` + client id/secret | off | |
| `CONNECTED_ACCOUNTS_GOOGLE` | `connex.connected-accounts.google.enabled` | off | |
| `CONNECTED_ACCOUNTS_MICROSOFT` | `connex.connected-accounts.microsoft.enabled` | off | |
| `CONNECTED_CAPTURE_GOOGLE` | `CONNEX_CONNECTED_CAPTURE_GOOGLE_ENABLED` (+ accounts + scheduling) | off | **must be off — see below** |
| `CONNECTED_CAPTURE_MICROSOFT` | `CONNEX_CONNECTED_CAPTURE_MICROSOFT_ENABLED` (+ accounts + scheduling) | off | **must be off — see below** |
| `MANAGED_MAIL` | `CONNEX_MAIL_MANAGED` | off; **forbidden on `on-prem`** | |
| `BUSINESS_CARD_SCANNING` | `CONNEX_BUSINESS_CARD_SCANNING_ENABLED` | off in `application.yml`, **on** in the silo/on-prem templates | |
| `BUSINESS_CARD_IMPORT` | same subsystem; requires binary-store readiness | off | |
| `CAMPAIGN_DELIVERY` | `connex.delivery.enabled` | off | |

- [ ] Decision recorded for every row above.
- [ ] Composed result verified against the running instance rather than the env file:

```bash
curl -s https://<partner-host>/api/capabilities | jq .
```

- [ ] **Connected capture is NOT enabled.** It is an internal preview capability. Google restricted
      -scope OAuth verification plus CASA Tier 2, and Microsoft publisher verification, are owned by
      [#868](https://github.com/itkla/connex/issues/868) and are **not started** as of this
      template's last review — see [RELEASE_1_0_EXTERNAL_BLOCKERS.md](RELEASE_1_0_EXTERNAL_BLOCKERS.md)
      and [CONNECTED_CAPTURE.md](CONNECTED_CAPTURE.md). Until those gates clear, connected capture
      must not process partner or public data. Google development use is limited to allow-listed
      test users of the unverified OAuth application; Microsoft development use is limited to the
      configured development tenant. **If the partner requires connected capture, admission is
      blocked** (see Blocking conditions).
- [ ] **AI posture decided.** AI is **instance-off by default** (`CONNEX_AI_ENABLED=false`) and is
      **BYOP — bring-your-own-provider, configured per organization**, not instance-wide. If AI is
      in scope:
      - [ ] The partner has contracted its own provider (Vertex / Bedrock / Azure AI) and holds that
            provider's contract and DPA. **For that transfer the partner is the data exporter and
            the AI provider is the partner's subprocessor, not Connex's** (DPA template §7).
      - [ ] The partner understands that image features, including business-card reading, send
            metadata-free image pixels that **cannot be masked** and may contain direct or
            special-care identifiers.
      - [ ] Turning on `CONNEX_AI_ENABLED=true` enables **all five** AI features unless each is
            explicitly set to `false`. Decide per feature: `CONNEX_AI_FEATURES_DEAL_BRIEF`,
            `_DEAL_RISK_RATIONALE`, `_INTRO_RATIONALE`, `_REPORT_NARRATIVE`,
            `_BUSINESS_CARD_EXTRACTION`.
      - [ ] The partner knows which of its members hold the `AI_USE` permission. It is held by the
            built-in `owner` and `admin` roles **but not by `member`**, so a plain member sees no AI
            features even on a fully configured instance.
- [ ] Mail posture decided and the sending path agreed. Transactional mail is async and
      failure-swallowing; only the settings test send is synchronous. See
      [DELIVERABILITY.md](DELIVERABILITY.md) for what the platform does and does not do about
      sender authentication and bounce handling, and
      [INTERNAL_OPERATIONS_RUNBOOK.md](INTERNAL_OPERATIONS_RUNBOOK.md) for the invite-link path when
      mail is off.
- [ ] Capabilities deliberately **off** written down with the reason, so a later "why can't we see
      X" question has an answer that is not archaeology.

### 6. Onboarding

- [ ] Organization and workspace provisioned by an operator. Self-service workspace creation stays
      **off** (`CONNEX_WORKSPACES_ALLOW_CREATION=false`, the shipped default and the value in both
      the silo and on-prem templates).
- [ ] First owner account established — either the one-shot bootstrap runner on a fresh instance
      (`CONNEX_BOOTSTRAP_ENABLED=true` on first boot only, and **forbidden on `saas`**) or by
      operator-side admin user creation. See
      [INTERNAL_OPERATIONS_RUNBOOK.md](INTERNAL_OPERATIONS_RUNBOOK.md).
- [ ] `CONNEX_BOOTSTRAP_ENABLED` set back to `false` once a real owner exists.
- [ ] Named partner users invited. If `connex.mail.enabled=false`, use **shareable invite links**
      (Settings → Members) and deliver them out of band — the invite email is asynchronous and
      swallows its own failures, so its absence is silent.
- [ ] Roles assigned per named user and reviewed with the partner. Membership and role mechanics
      are in [INTERNAL_OPERATIONS_RUNBOOK.md](INTERNAL_OPERATIONS_RUNBOOK.md). Note that an invite
      can never grant `owner`, and that the built-in `admin` role differs from `owner` by exactly
      one permission (`ROLE_MANAGE`).
- [ ] **The organization owner has registered a passkey, verified by actually performing one
      step-up-guarded action.** Step-up is WebAuthn-only and an ordinary password login clears the
      stamp, so a partner owner without a passkey **cannot invite, change roles, export, or tear
      down** — they will get `403 RECENT_AUTHENTICATION_REQUIRED` on every one. Do this at
      onboarding, not at offboarding.
- [ ] Starting data decided: **seeded demo fixtures** or **real import**.
      - Demo fixtures use the deterministic seeder — [VOLUME_SEEDER.md](VOLUME_SEEDER.md). It is a
        **one-shot** run against a **dedicated disposable schema**, and all seeded users share a
        publicly known local password. **A seeded schema is never a partner production schema.**
      - Real import: agree the source, the field mapping, and who owns data quality before the
        first load.
- [ ] Partner briefed that relationship warmth is computed **on read** from logged interactions,
      so an empty workspace shows nothing until real activity exists. Set expectations for when the
      first meaningful signal appears — see
      [INTERNAL_OPERATIONS_RUNBOOK.md](INTERNAL_OPERATIONS_RUNBOOK.md).

### 7. Support

- [ ] Named contacts recorded on both sides, with response expectations agreed in writing.
- [ ] **Support reporting flow briefed.** A user reporting a broken page will quote a
      `Reference: <digest>` string from the error screen — that is a Next.js digest, **not** a
      correlation ID, and it is the only identifier any Connex UI shows. The correlation ID exists
      on the `X-Correlation-Id` response header and in unexpected `500` JSON bodies, but no screen
      renders it. Both lookups run against the deployment's own logs; the recipes are in
      [INTERNAL_OPERATIONS_RUNBOOK.md](INTERNAL_OPERATIONS_RUNBOOK.md) and
      [DEPLOYMENT.md](DEPLOYMENT.md).
- [ ] For a customer-operated deployment: confirm the partner can run those log lookups themselves,
      because Connex has no access to their deployment and nothing phones home.
- [ ] Escalation path for a suspected security incident agreed and distinct from ordinary support.
- [ ] Upgrade cadence and maintenance-window expectations agreed — [UPGRADING.md](UPGRADING.md).

## Exit criteria and offboarding

Agree the exit path **at admission**, not at termination. The mechanics — export, verification, and
the two owner-only teardown calls — are in the **Offboarding** section of
[INTERNAL_OPERATIONS_RUNBOOK.md](INTERNAL_OPERATIONS_RUNBOOK.md). Confirm at admission that:

- [ ] The partner knows the export is a streamed ZIP obtained over HTTP by an **organization
      administrator** with recent authentication, and that there is **no CLI**.
- [ ] The partner knows exports are **plaintext at generation** and that protecting the downloaded
      archive is theirs to do.
- [ ] The export-then-delete window from the signed DPA (template §8 proposes **[30] days**) is
      recorded here as an actual number: **{{DATE}} + ___ days**.
- [ ] The partner knows teardown is **organization-owner-only** (export is organization-*admin*),
      requires step-up with a registered passkey and a case-sensitive slug confirmation, and is
      **refused while any APPI data-subject request is still open** — an unfinished obligation must
      be closed before the tenant can be deleted.
- [ ] Retained audit metadata after deletion is understood — [GOVERNANCE_DELETION_AND_RETENTION.md](GOVERNANCE_DELETION_AND_RETENTION.md).

## Blocking conditions

Any one of these **categorically prevents admission**. They are not risks to accept with a note;
they are stop conditions. Escalate to {{OWNER}} rather than working around them.

| Condition | Why it blocks |
|---|---|
| **No executed DPA** | The entrustment has no legal basis and the entrustee/operator split — who reports a breach, who answers a 開示等 request — is undefined. Nothing else on this checklist is meaningful without it. |
| **Connected capture is required by the partner** | Google restricted-scope verification + CASA Tier 2 and Microsoft publisher verification ([#868](https://github.com/itkla/connex/issues/868)) are unmet. Capture is an internal preview and is **not authorized for partner or public data** until those gates pass. There is no compliant workaround. |
| **On-prem partner unwilling to hold its own keys** | On-prem means the partner generates and holds `CONNEX_SECRET_STORE_MASTER_KEY` and `CONNEX_AUDIT_INTEGRITY_HMAC_SECRET` and controls its database and backup keys. A partner that wants Connex to hold them is asking for a Connex-operated deployment; re-decide the shape rather than splitting custody. |
| **No restore drill** | Backups are operator-run and unverified until restored. Admitting a partner whose recovery path has never been executed converts a routine failure into data loss. |
| **Deployment profile unset or wrong** | The instance will not start at all with it unset; a *wrong* value silently gives the partner the wrong posture — for example internal-access opt-ins available on what was sold as hardened SaaS. |
| **Database TLS not verified** | `sslMode` weaker than `VERIFY_CA`/`VERIFY_IDENTITY` is refused outside `dev`/`test`; reaching for the `dev` profile to get past it disables the fail-closed cookie and transport requirements at the same time. |
| **Special-care data in scope without a lawful basis** | [SPECIAL_CARE_DATA_POLICY.md](SPECIAL_CARE_DATA_POLICY.md); the partner is the handling operator and must establish basis and consent before loading it. |

## Sign-off

| Item | Value |
|---|---|
| Partner | {{PARTNER}} |
| Deployment shape / profile | |
| Admission owner | {{OWNER}} |
| Admission date | {{DATE}} |
| Restore drill date and outcome | |
| Capabilities enabled | |
| Capabilities deliberately off | |
| Export-then-delete window | |
| Blocking conditions reviewed | |
