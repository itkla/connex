# Connex — Personal-Data Breach Response Runbook (APPI Art. 26)

> **Status:** Process deliverable for the APPI compliance pathway ([#224]) — issue [#223]. Connex now has an org-scoped incident register and audit-scope helper; alerting hooks remain future work, and no standing on-call or verified 24/7 intake exists.
> **Not legal advice.** The thresholds, deadlines, and entrustee construction below are framework reasoning and must be confirmed with Japanese counsel and, where relevant, each customer's Data Processing Agreement (DPA, [#93]). When this runbook and a signed DPA disagree, **the DPA governs** for that customer.
>
> **Owner:** Hunter Nakagawa, Founder · **Last reviewed:** 2026-08-13 · **Next review:** 2027-02-13 · Review at least every 6 months and after every incident.

---

## 0. Posture that drives this runbook

Connex operates as an **entrustee (委託先)** under APPI, not the handling operator (個人情報取扱事業者): the **customer** is the operator for the CRM contents they load, and Connex handles that data under entrustment ([#93]). This changes who reports:

- For personal data **entrusted by a customer**, Art. 26's own proviso lets an entrustee be **exempt from reporting directly to the PPC (個人情報保護委員会) and to individuals** *if it promptly notifies the entruster (委託元 — the customer)*. **So Connex's primary, non-negotiable duty on a breach of customer data is to promptly notify the affected customer(s)** — who, as the operator, then report to the PPC and notify individuals. Connex supports them with forensics and scoping.
- For Connex's **own** personal data (its direct account holders, employees), Connex **is** the operator and carries the full Art. 26 reporting duty itself.

The **`organization`** (`org_id`) is the customer / contract / **breach boundary** (`Organization.java`, `V22__organization.sql:4-5`); the **`workspace`** is the data/RBAC/audit partition inside it. Blast radius is reasoned first at the org boundary, then narrowed to workspace and record.

---

## 1. What counts as an incident

Any actual or suspected **leakage (漏えい), loss (滅失), or damage (毀損)** of personal data, or unauthorized access to it. Examples: cross-tenant data exposure, credential compromise, stolen/lost device with DB access, misdirected export, unauthorized DB dump, ransomware, a vulnerability confirmed to have exposed data.

**When in doubt, open an incident.** Under-reporting is the failure mode APPI penalizes; a false alarm costs nothing but time.

---

## 2. Reportable situations (要報告事態) — the trigger for external duties

Art. 26 (and Enforcement Rules) make a breach a **reportable situation** when **any** of the following holds. These are also the situations where the customer must notify individuals:

| # | Trigger | Notes |
|---|---|---|
| A | Breach involving **要配慮個人情報** (special-care-required PI) | Health, criminal record, social-status, etc. Use [SPECIAL_CARE_DATA_POLICY.md](SPECIAL_CARE_DATA_POLICY.md) for classified custom fields and free-text handling; assume special-care data may be present unless proven otherwise. |
| B | Breach likely to cause **property damage** | Data usable for financial fraud (payment data, credentials enabling it). |
| C | Breach committed with **wrongful/improper purpose** | Unauthorized access, exfiltration, malicious insider, ransomware. Nearly every external attack lands here. |
| D | Breach involving **> 1,000 data subjects** | Count distinct affected individuals across all affected workspaces/orgs. |

If none apply (e.g. a single misdirected record recovered immediately, no wrongful intent), external reporting may not be triggered — **but still log it, notify the affected customer, and record the reasoning.** Counsel confirms the call for anything ambiguous.

---

## 3. Deadlines (once a reportable situation is identified)

Deadlines run from **awareness** of the reportable situation. These bind whichever party reports (the customer as operator, or Connex for its own data):

| Obligation | Deadline |
|---|---|
| **Notify the affected customer(s)** (Connex → 委託元) | **Promptly** — target **within 24 hours** of confirming a reportable situation; earlier for large/active incidents. This is Connex's core duty. |
| **速報 (preliminary report) to PPC** | Promptly — **within ~3–5 days** of awareness. |
| **確報 (definitive report) to PPC** | **Within 30 days** of awareness — **or 60 days** if the breach is a wrongful-purpose / unauthorized-access case (Trigger C). |
| **Notify affected individuals (本人通知)** | Promptly, in a manner appropriate to the situation. If individual notice is difficult, substitute measures (public announcement + inquiry line). |

Connex does not usually file with the PPC for customer data — but must move fast enough that the customer can still meet the ~3–5-day 速報.

---

## 4. Roles

| Role | Who | Responsibility |
|---|---|---|
| **Incident Lead** | Hunter Nakagawa, Founder | Owns the incident end-to-end; makes the reportable-situation call with counsel; single source of truth. |
| **Technical Responder** | Hunter Nakagawa or an engineer assigned for the incident; no standing on-call rota | Contains, scopes affected data, preserves forensics. |
| **Customer Liaison** | {{CUSTOMER_LIAISON}} | Notifies affected customers; coordinates their PPC/individual notifications per DPA. |
| **Legal/Counsel** | {{COUNSEL_CONTACT}} | Confirms triggers, deadlines, report wording. |
| **Comms** | {{COMMS_CONTACT}} | Public statement / inquiry line if individual notice is by substitute measure. |

**Designated data-protection contact point** (for publication per Art. 32 — see disclosure page, [#219]): privacy@connexcrm.jp. Named ownership, acknowledgement targets, and the pending operational-verification caveat are defined in [SECURITY.md](SECURITY.md).

**Coverage limitation.** Hunter Nakagawa monitors the contact point as a single operator on a
best-effort basis. There is no 24/7 coverage, standing rota, or verified mailbox alert path. A 24/7
or on-call claim may be activated only after the external receipt test, tested responder alerts,
named coverage rota, and measured acknowledgement evidence required by [SECURITY.md](SECURITY.md)
are recorded in [#249].

---

## 5. Phases

### 5.1 Detect & declare (target: immediate)
- **Trigger:** report from monitoring, a customer, a researcher, or staff.
- **Action:** the receiver opens an incident record in `/api/orgs/{orgId}/appi-incidents`, assigns an Incident Lead, starts a timestamped timeline. Set a provisional severity.
- **Escalation target:** notify the Incident Lead within 1 hour of receiving any credible report on
  a best-effort basis. This is not a 24/7 commitment.

### 5.2 Contain (target: within hours)
- **Action:** stop ongoing loss — revoke compromised sessions/credentials, disable affected accounts, block the source, take the exposed surface offline if needed. Do **not** wipe evidence (see 5.4).
- **Verification:** confirm the leak path is closed before moving on.

### 5.3 Scope — who and what was affected
Use the append-only **`audit_log`** (`AuditLog.java`, `V10__audit_log_workspace.sql`) to bound blast radius. Relevant columns:

- **`workspace_id`** — primary partition. Determine the set of affected workspaces, then map to `org_id` via `workspace` to get the affected **customers** (breach boundary).
- **`entity_type` / `entity_id`** — which records (person, note, activity, custom field…) were read/exported/modified.
- **`actor_id` / `ip_address` / `user_agent` / `session_id`** — forensic attribution of unauthorized access.
- **`action` / `changes` / `created_at`** — what happened, when, with before/after diffs.

The org-admin incident scope endpoint
`/api/orgs/{orgId}/appi-incidents/{incidentId}/scope` returns metadata-only
counts grouped by workspace, entity type, action, and outcome for the incident
window. It deliberately does not expose audit `changes`, target labels, IP
addresses, user agents, session hashes, or request IDs; those remain in the
workspace/org audit exports under their existing gates.

Deliverables from this phase:
- List of affected **organizations** (customers) and **workspaces**.
- Count of **distinct affected data subjects** (drives Trigger D).
- Whether any **要配慮個人情報** was in scope (Trigger A) — check `special_care` custom fields and the free-text surfaces listed in [SPECIAL_CARE_DATA_POLICY.md](SPECIAL_CARE_DATA_POLICY.md).
- Nature of access (Trigger C).

> **Caveat:** `audit_log` triggers block row UPDATE/DELETE but not DDL — a privileged attacker could drop them. Tamper-evidence is tracked in [#91]; until it lands, corroborate audit_log with DB/infra logs and treat gaps as worst-case.

### 5.4 Preserve evidence
- Snapshot relevant logs (audit_log export, DB/infra/access logs), affected DB state, and the timeline. Store outside the affected system. Preserve for the report and any counsel/PPC follow-up (retain ≥ the report period; align with audit retention in [#91]/[#105]).

### 5.5 Assess the reportable-situation call
- Incident Lead + Counsel apply §2 against the §5.3 scope. Record the decision and reasoning **either way**.
- Start the §3 clocks from the awareness timestamp.

### 5.6 Notify
1. **Customers (always, for their data):** Customer Liaison notifies each affected org's contact using the §6 template — promptly, target ≤24h. Provide scope, timeline, containment status, and what they need for their own PPC/individual notification. Follow each DPA's specific notice terms ([#93]).
2. **PPC:** the reporting party (customer as operator; Connex for its own data) files 速報 then 確報 per §3.
3. **Individuals:** the operator (customer) notifies affected individuals, or uses substitute measures. Connex supports with the affected-subject list from §5.3.

### 5.7 Remediate & close
- Fix root cause; add detection/regression guards (feed into [#87] hardening, [#80] rate-limiting, [#91] audit).
- Write a blameless post-incident review: timeline, root cause, what worked, gaps, action items with owners/dates.
- Update this runbook with anything learned.

---

## 6. Customer notification template (Connex → affected customer)

```
Subject: [Connex Security] Personal-data incident affecting your organization — action may be required

We are notifying you of a personal-data incident affecting data your organization
handles in Connex, under our Data Processing Agreement.

- What happened: [brief factual description]
- When: detected [timestamp JST]; estimated window [start–end]
- Data involved: [categories; whether special-care PI may be included]
- Scope for your organization: [workspaces / approx. affected individuals]
- Status: [contained / ongoing]; steps taken: [containment actions]
- What we need from you / what you may need to do: as the handling operator you may
  have APPI Art. 26 obligations to report to the PPC (速報 within ~3–5 days, 確報 within
  30/60 days) and to notify affected individuals. We can provide the affected-subject
  list and forensic detail to support this.
- Your contact for this incident: Hunter Nakagawa, Founder — privacy@connexcrm.jp

This is a preliminary notice; we will follow up as the investigation progresses.
```

---

## 7. Quick reference

- **First 24 hours:** declare → contain → begin scope → **notify affected customers** → open the reportable-situation assessment with counsel.
- **Connex's core external duty for customer data:** promptly notify the **customer** (entruster). The customer reports to the PPC.
- **Reportable if:** special-care PI · property-damage risk · wrongful purpose · >1,000 subjects.
- **PPC clock:** 速報 ~3–5 days · 確報 30 days (60 if unauthorized-access/wrongful-purpose).
- **Scope tool:** `audit_log` by `workspace_id` → `org_id`, `entity_type`/`entity_id`, forensic columns.

[#93]: https://github.com/itkla/connex/issues/93
[#223]: https://github.com/itkla/connex/issues/223
[#224]: https://github.com/itkla/connex/issues/224
[#219]: https://github.com/itkla/connex/issues/219
[#222]: https://github.com/itkla/connex/issues/222
[#91]: https://github.com/itkla/connex/issues/91
[#105]: https://github.com/itkla/connex/issues/105
[#87]: https://github.com/itkla/connex/issues/87
[#80]: https://github.com/itkla/connex/issues/80
[#249]: https://github.com/itkla/connex/issues/249
