# Email deliverability

> Status: describes the mail behaviour that ships today. **Connex signs nothing and resolves no DNS.**
> It hands a message to the SMTP relay you configure; every deliverability control that inbox
> providers actually evaluate — SPF, DKIM, DMARC — lives in your DNS and your relay, not in this
> application. Read it before pointing a production instance at a mail server.

Related: [DEPLOYMENT.md](DEPLOYMENT.md) for the operator runbook,
[DEPLOYMENT_EDITIONS.md](DEPLOYMENT_EDITIONS.md) for what each profile permits,
[SECRET_STORE_KEY_LIFECYCLE_RUNBOOK.md](SECRET_STORE_KEY_LIFECYCLE_RUNBOOK.md) for the store that
holds workspace SMTP passwords.

---

## 1. What Connex does and does not do

Connex is an SMTP **submission client**. It builds a MIME message, opens an authenticated connection
to the host you configured, and sends. That is the whole of its involvement in deliverability.

**Connex does not:**

- **Sign anything with DKIM.** There is no signing code, no key material, and no configuration slot
  for a private key. If your mail is DKIM-signed, your relay signed it after Connex handed it over.
- **Evaluate or publish SPF, DKIM, or DMARC.** No part of the backend reads these records, warns
  about them, or reports on them.
- **Resolve DNS for policy purposes.** The only DNS the mail path performs is resolving the SMTP
  host so `SmtpDestinationGuard` can pin the connection to a single verified address (an
  SSRF/DNS-rebinding defence, see [§2.4](#24-the-smtp-destination-is-pinned)).
- **Set a separate envelope sender.** `mail.smtp.from` is never set, so the SMTP `MAIL FROM` is
  derived from the header `From`. See [§5](#5-aligning-the-from-address).
- **Set `Reply-To`.** Replies go to the `From` address.
- **Emit `List-Unsubscribe` or `List-Unsubscribe-Post` headers.** Campaign unsubscribe is a body
  link only ([§8](#8-campaign-mail-versus-transactional-mail)).

**Therefore: DMARC alignment is entirely your relay's responsibility.** Choosing a relay that will
sign as your domain, and publishing the records that authorize it, is the deliverability work. There
is no Connex setting that substitutes for it.

Richer in-product mail diagnostics are tracked separately; nothing beyond the SMTP send-test in
[§6](#6-verifying) exists today.

## 2. Choosing a mail shape

Two shapes exist. They differ in **who owns the transport and the sending identity**.

| | Instance / managed mail | Per-workspace SMTP override |
|---|---|---|
| Configured by | Operator, via `connex.mail.*` env | Workspace admin, via **Settings → Email** |
| Sending identity | One instance-wide `From` | Each workspace's own `From` |
| Password storage | Environment / `.env` | Central secret store, encrypted per workspace |
| Turned on by | `connex.mail.enabled=true` | Saving an enabled config in the UI |
| Locked to instance-only by | `connex.mail.managed=true` | — |
| Allowed on `on-prem` | **No** — see below | Yes |

`connex.mail.managed=true` means the instance transport is authoritative for **all** workspace mail
and per-workspace overrides are refused outright (`403 Workspace SMTP overrides are disabled on this
instance`). The frontend follows suit: the **Email** tab disappears from settings and
`/settings/email` redirects to `/settings/members`.

**`on-prem` forbids `connex.mail.managed` and fails startup if it is set.** Instance-managed mail is
transport Connex operates, which cannot exist in an installation Connex does not run. An on-prem
operator configures `connex.mail.*` against their own relay, or leaves workspace overrides
available, or both. The full profile matrix is in
[DEPLOYMENT_EDITIONS.md](DEPLOYMENT_EDITIONS.md) — do not duplicate it here.

### 2.1 How a sender is resolved

`MailConfigResolver` has three entry points and they behave differently. Knowing which one a given
mail uses tells you which identity it will leave under.

- **`resolveForWorkspace(workspaceId)`** — the normal path for workspace-scoped mail.
  1. In managed mode, **short-circuits to the instance config** and stops. The workspace's own row is
     never consulted.
  2. Otherwise, if the workspace has an *enabled* config that is *usable*, uses it.
  3. Otherwise, logs a warning and **falls back to the instance sender**.
- **`resolveInstance()`** — the instance config, or nothing at all when `connex.mail.enabled=false`.
- **`resolveWorkspaceOnly(workspaceId)`** — the workspace's own config with **no fallback**; returns
  nothing in managed mode. Only the send-test uses it, deliberately, so the test validates exactly
  what the workspace configured rather than silently proving the instance relay works.

**The fallback in step 3 is silent to the user.** A workspace admin who saves a broken configuration
may keep receiving mail — sent as the *instance* identity, from a domain they do not control. This is
the single most common source of surprise DMARC failures on this system. The log line to grep for is
in [§7](#7-common-failure-modes).

### 2.2 Which `From` address is used

| Shape | `From` address | `From` display name |
|---|---|---|
| Instance | `connex.mail.from`, or `connex.mail.username` when blank | `connex.mail.from-name` (default `Connex`) |
| Workspace | the workspace's *From address*, or its *username* when blank | the workspace's, falling back to the instance name |

The workspace's port also falls back to `connex.mail.port` (default `587`) when unset.

### 2.3 What counts as a "usable" configuration

A resolved configuration is usable when its **host is non-blank and its From address is non-blank**.
That is the entire test.

This floor is lower than operators expect, and the consequence matters: a wrong port, a wrong
password, TLS misconfiguration, or a host that refuses the connection all produce a **usable**
configuration. Such a config is selected and used — it does *not* trigger the fallback-to-instance
warning. It fails later, at transport time, where most senders swallow the error
([§3.1](#31-failure-semantics-most-send-failures-are-invisible)). Only a blank host or a blank From
address makes a workspace configuration fall back.

### 2.4 The SMTP destination is pinned

`SmtpDestinationGuard` resolves the SMTP host and `PinnedSocketFactory` connects to that exact
address, so the name cannot be re-resolved to a different host between check and connect.
`connex.mail.allow-internal-hosts` (default **false**) is what permits a private-network relay; it is
**forbidden on the `saas` profile** and allowed on `silo` and `on-prem`. An internal relay on a
hardened profile will be refused, at save time for a workspace config and at send time otherwise.

## 3. What mail Connex sends

Eight senders exist. `sendForWorkspace` uses the workspace identity (with instance fallback);
`sendInstance` uses the instance identity **only**; `sendNow` is synchronous and throws.

| Mail | Transport call | Identity | Enabled by |
|---|---|---|---|
| Workspace invite | `sendForWorkspace` | workspace → instance | `connex.mail.enabled`; link built from `connex.mail.app-base-url` |
| Notification email channel | `sendForWorkspace` | workspace → instance | the recipient's per-user `email` notification preference |
| Scheduled report delivery | `sendForWorkspace` | workspace → instance | `connex.reports.scheduling-enabled` (default **true**) |
| Password reset | `sendInstance` | **instance only** | `connex.password-reset.email-enabled` (default **false**) |
| Email-change verification | `sendInstance` | **instance only** | `connex.email-change.email-enabled` (default **false**) |
| Registration verification | `sendInstance` | **instance only** | `connex.registration-verification.enabled` **and** `.email-enabled` (both default **false**) |
| Mail settings test email | `sendNow` (synchronous) | **workspace only**, no fallback | on demand, from Settings → Email |
| Campaign delivery | own dispatcher, never async | workspace → instance | `connex.delivery.enabled` **and** `connex.delivery.dispatch-enabled` (both default **false**) |

Three consequences worth planning around:

**`connex.mail.enabled=true` alone does not start sending account mail.** It wires the default sender
and lets invites be emailed. Password reset, email-change verification, and registration
verification each have their own flag and **all three default to false** — until you set them, those
flows use the logging fallback that only writes the link to the log. Enabling the transport and
expecting reset mail to start flowing is a routine misdiagnosis.

**Account-level mail always leaves as the instance identity.** The three `sendInstance` flows ignore
workspace overrides entirely. If `connex.mail.*` is unconfigured, they have no sender at all, no
matter how many workspaces have working SMTP. Plan the instance identity's DNS even on an instance
where every workspace brings its own relay.

**Invite and notification templates render with a hard-coded `en` locale.** Only the test email
follows the actor's locale. This is a content matter, not an authentication one, but it surprises
Japanese-language deployments.

Neither `connex.delivery.enabled` nor `connex.delivery.dispatch-enabled` appears in
`application.yml`; both are bound from the environment only, and campaign mail sends only when
**both** are true.

### 3.1 Failure semantics: most send failures are invisible

`sendInstance` and `sendForWorkspace` are `@Async` and **swallow every failure**. The delivery helper
logs and returns; nothing propagates to the request that triggered it, and no user-visible error is
produced. Only `sendNow` — the send-test — throws.

The operational consequence is sharper than it first looks. Scheduled report delivery freezes its
report snapshot and then records the audit event `report.schedule.delivery` (*"Queued scheduled
report delivery"*) **after** handing every recipient's message to the async sender. If SMTP is
unusable, the snapshot is still frozen and that audit record is still written. The audit is honest —
it says *queued* — but it is exactly that and no more. **"The audit says the report was delivered" is
not evidence that mail left the building.** Only the SMTP logs, or the receiving mailbox, are.

## 4. DNS records per deployment shape

Publish these on the domain in the **`From` address Connex will use** — the one from
[§2.2](#22-which-from-address-is-used), not your corporate domain and not the relay's domain.

Which domain that is depends on the shape:

- **Managed / instance mail** — one domain, `connex.mail.from`'s. Whoever operates that domain
  publishes the records and authorizes the relay.
- **Per-workspace overrides** — **each workspace's own From domain needs its own complete set.** A
  correctly configured instance domain does nothing for a workspace sending as a different domain.
  This is the part operators most often miss when turning overrides on.
- **Both** — do both, and remember the account-mail carve-out above: the instance domain still needs
  records even when every workspace overrides.

### 4.1 SPF

SPF authorizes the hosts that may send from a domain, and is evaluated against the **envelope**
sender (`MAIL FROM`). Publish a single TXT record at the domain itself:

```text
example.com.  IN  TXT  "v=spf1 <authorized senders> -all"
```

The mechanisms in the middle come from your relay's documentation — normally an `include:` of the
relay's own SPF domain, sometimes explicit `ip4:`/`ip6:` ranges for a self-hosted relay. **Use what
your relay publishes; do not invent mechanisms.** The trailing qualifier is your policy choice:
`-all` (hard fail) or `~all` (soft fail). Start at `~all` if you are unsure, and tighten once you
have DMARC reports.

Two rules that break SPF silently:

- **Exactly one SPF record per domain.** Two `v=spf1` TXT records is a permanent error, and the
  domain fails SPF outright — a common outcome when a second relay is added by a different team.
- **A maximum of 10 DNS-lookup mechanisms.** `include`, `a`, `mx`, `ptr`, `exists`, and `redirect`
  each cost lookups, and nested `include`s count. Exceeding the limit is also a permanent error.

### 4.2 DKIM

**Your relay signs. You publish its public key.** The relay chooses a selector and gives you either a
TXT record or a CNAME that points at a record it maintains; take whichever it issues.

A DKIM public-key record lives at `<selector>._domainkey.<domain>`:

```text
sel1._domainkey.example.com.  IN  TXT  "v=DKIM1; k=rsa; p=<base64 public key>"
```

Practical notes:

- **The selector is the relay's choice, not yours.** It only has to be unique per key on the domain,
  which is what lets several relays and several rotations coexist.
- **Use the key your relay generates.** RSA-2048 is the common current default; some relays offer
  Ed25519 alongside it. Do not hand-roll key material for a relay that will issue its own — the
  private half has to live where the signing happens, and that is not Connex.
- **Many relays hand you a CNAME instead of a TXT record.** That is preferable when offered: the
  relay can then rotate its key without another DNS change from you.
- **Connex never touches any of this.** There is no selector setting, no key path, and no signing
  step in the application.

### 4.3 DMARC

DMARC ties SPF and DKIM to the **header `From`** domain and tells receivers what to do when neither
aligns. Publish a TXT record at `_dmarc.<domain>`:

```text
_dmarc.example.com.  IN  TXT  "v=DMARC1; p=none; rua=mailto:dmarc-reports@example.com"
```

- `p=` is the policy: `none` (monitor), `quarantine`, or `reject`. **Deploy at `p=none` with `rua`
  first**, read the aggregate reports for a couple of weeks, and only then tighten. Publishing
  `p=reject` before you know which of your senders align is how legitimate mail disappears.
- `rua=` is where aggregate reports go. Without it you are flying blind, and DMARC's whole value is
  the reporting.
- `adkim=` and `aspf=` set alignment strictness, `r` (relaxed, the default) or `s` (strict). Relaxed
  accepts an organizational-domain match, so a subdomain satisfies it; strict requires an exact
  domain match.

**DMARC passes when SPF *or* DKIM passes *and* is aligned.** One is enough. Because Connex does not
sign and cannot control envelope rewriting, which of the two you can rely on is decided entirely by
your relay's behaviour — see [§5](#5-aligning-the-from-address).

### 4.4 Verifying the records with `dig`

Check the From domain, from a host whose resolver you trust, after the TTL of any previous record has
expired:

```bash
dig +short TXT example.com
dig +short TXT sel1._domainkey.example.com
dig +short TXT _dmarc.example.com
```

Reading the answers:

- **SPF** — among the TXT strings returned for the domain there must be **exactly one** starting
  `v=spf1`. Zero means no SPF. Two or more is a permanent error. Other TXT records (domain
  verification tokens and the like) are unrelated and harmless.
- **DKIM** — a `v=DKIM1;` string with a non-empty `p=`. An empty `p=` is a **revoked** key, not a
  configured one. Nothing returned usually means the wrong selector: ask the relay which selector it
  signs with rather than guessing. `dig +short TXT` follows a CNAME, so a CNAME-delegated record
  answers here normally.
- **DMARC** — a single `v=DMARC1;` string. Note the `_dmarc.` prefix; a DMARC record published at the
  bare domain does nothing.
- **Long records come back split.** TXT strings are limited to 255 characters each, so a 2048-bit
  DKIM key is returned as several quoted strings. They concatenate with nothing between them; that is
  normal, not corruption.

Confirm against the relay's own dashboard as well. A published record and a relay that agrees it is
authorized are two different facts, and only the second one signs.

## 5. Aligning the `From` address

Connex sets the header `From` and nothing else. `mail.smtp.from` is never set, so **the envelope
sender is derived from the header `From`: they are the same address, and there is no separate
Return-Path or bounce address to configure.**

That single design fact drives everything below.

**It helps SPF alignment, by default.** SPF authenticates the envelope sender's domain, and DMARC's
SPF alignment compares that domain with the header `From` domain. Because Connex emits one address
for both, they are trivially aligned as submitted. If your relay also passes SPF for that domain,
DMARC passes on the SPF leg without DKIM.

**Your relay can take that away.** Many relays and ESPs rewrite `MAIL FROM` to their own bounce
domain so they can collect bounces. That is legitimate and usually desirable — but it breaks SPF
alignment, because the envelope domain is now the relay's and the header `From` is still yours. When
that happens, **DKIM is your only route to a DMARC pass**, and the relay must sign with a `d=` that
matches your From domain. Ask your relay two questions before going live: *do you rewrite the
envelope sender?* and *what `d=` do you sign with?*

**There is no bounce address to align or monitor.** Bounces return to the `From` address — a real
mailbox you should watch, or a relay-owned address if it rewrites. Connex ingests nothing from it for
transactional mail ([§8](#8-campaign-mail-versus-transactional-mail)).

**Set `From` to an address on a domain you control.** Leaving `connex.mail.from` blank makes the
username the From address, which is frequently the relay's own login rather than a deliverable
mailbox on your domain. Set it explicitly.

**Do not point a From address at a domain you have not authorized.** Setting `connex.mail.from` to a
customer's domain on managed mail means that customer must publish records authorizing *your* relay.
Until they do, that mail fails DMARC for them. This is a contractual conversation, not a config
change.

## 6. Verifying

### 6.1 The send-test

Settings → Email has a **send test** action, backed by
`POST /api/workspaces/{id}/mail-config/test`. It is the primary tool and it ships today.

- Requires the `WORKSPACE_SETTINGS` permission **and** recent authentication (step-up re-auth).
- Resolves via `resolveWorkspaceOnly`, so it exercises **exactly what the workspace saved**, with no
  instance fallback to make a broken config look healthy.
- Re-checks the destination through `SmtpDestinationGuard` before connecting.
- Sends **synchronously** via `sendNow`, so the transport error surfaces in the response instead of
  being swallowed.
- Sends **only to the requesting user's own account email**. It is not a way to mail an arbitrary
  address. If the account has no email, the test refuses rather than sending.
- Refused with `403` in managed mode, because workspace overrides do not exist there.
- Writes the audit event `workspace.mail_config.test`.

**Its limits, stated plainly.** The send-test is an **SMTP connectivity and authentication test and
nothing more.** It does not check SPF, DKIM, or DMARC; it does not inspect the headers of the message
it sent; it performs no seed-list, reputation, or inbox-placement check. A green send-test tells you
the credentials work and the relay accepted the message. It tells you nothing about whether that
message will reach an inbox.

To assess authentication you need the delivered message itself: send to a mailbox you control, open
the raw source, and read the `Authentication-Results` header the receiver added. That is where you
find out whether DKIM signed, what `d=` it used, and whether DMARC aligned. The send-test cannot
substitute for it, and neither can anything else in the product today.

### 6.2 What to check in logs

Backend logs are structured ECS JSON, so these are greppable by message. The mail path emits three
distinct lines:

| Log line | Level | Means |
|---|---|---|
| `Workspace {} has SMTP enabled but its config is unusable; falling back to the instance default sender` | WARN | The workspace's saved config has a blank host or blank From address. Mail is going out as the **instance** identity. |
| `Email to {} not sent: no usable SMTP configuration ({})` | WARN | Nothing usable resolved at all. The message was **dropped**, not queued or retried. |
| `Failed to send email to {} ({}): {}` | ERROR | The relay was reached and the send failed. The relay's own message is the tail of the line. This is the async swallow path — the user saw no error. |

The parenthesized source is `instance` or `workspace <id>`, which tells you which identity was
attempted. The send-test additionally logs `Test email for workspace {} failed: {}` at WARN with the
underlying cause, while returning a generic message to the browser.

**There is no retry and no outbound queue.** A swallowed failure is a permanently lost message.
Alerting on that ERROR line is the only way to learn about it.

## 7. Common failure modes

| Symptom | Likely cause | Fix |
|---|---|---|
| Mail arrives, but `From` is the instance address, not the workspace's | The workspace config has a blank host or blank From address and silently fell back | Grep for `has SMTP enabled but its config is unusable; falling back to the instance default sender`; fill both fields and re-run the send-test |
| A user reports mail never arrived and saw no error in the UI | `sendForWorkspace`/`sendInstance` are `@Async` and swallow failures — nothing surfaces to the browser | Grep for `Failed to send email to` and `Email to {} not sent`; alert on both. The message is gone; there is no retry |
| Reset / verification mail never sends although `connex.mail.enabled=true` | Each flow has its own flag, all defaulting false | Set `connex.password-reset.email-enabled`, `connex.email-change.email-enabled`, and/or `connex.registration-verification.enabled` + `.email-enabled` |
| Reset links appear in the backend log instead of being mailed | Same cause — the logging fallback is the default when a flow's `email-enabled` is false | As above |
| The **Email** settings tab is missing; `/settings/email` redirects to Members | `connex.mail.managed=true`; workspace overrides are disabled instance-wide | Expected. Configure `connex.mail.*` instead, or unset `managed` — note `on-prem` forbids `managed` outright |
| Startup fails with `connex.deployment.profile=on-prem forbids: connex.mail.managed=true` | Managed mail on a customer-run install | Unset `CONNEX_MAIL_MANAGED`; configure your own relay ([DEPLOYMENT_EDITIONS.md](DEPLOYMENT_EDITIONS.md)) |
| Campaign unsubscribe link in the email body is unclickable | `connex.delivery.public-base-url` is unset, so the URL is emitted as the relative path `/api/delivery/unsubscribe/{token}` | Set it to the instance's absolute public base URL before sending any campaign |
| Campaigns never dispatch although `connex.delivery.enabled=true` | `connex.delivery.dispatch-enabled` also defaults false; neither key is in `application.yml` | Set both in the environment |
| Mail is accepted by the relay but lands in spam or is rejected by DMARC | The relay signs with its own `d=`, or rewrites `MAIL FROM` to its own bounce domain, so nothing aligns with your `From` | Have the relay sign as your From domain and publish its selector; verify with the `Authentication-Results` header of a real delivered message ([§6.1](#61-the-send-test)) |
| Every workspace-override domain fails DMARC while the instance domain passes | Only the instance domain's records were published | Each workspace's From domain needs its own SPF, DKIM, and DMARC records ([§4](#4-dns-records-per-deployment-shape)) |
| SPF fails on a domain that clearly has an SPF record | Two `v=spf1` records, or more than 10 lookup mechanisms — both are permanent errors | `dig +short TXT <domain>` and collapse to one record within the limit |
| Saving a workspace SMTP host is refused | `SmtpDestinationGuard` rejected a private-network destination; `connex.mail.allow-internal-hosts` is false and forbidden on `saas` | Use a publicly routable relay, or run the profile that permits internal hosts |
| The audit shows a scheduled report delivered, but nobody received it | The audit records *queued*, written after the async handoff; the send failed afterwards and was swallowed | Check the ERROR log line. Treat the audit as evidence of queueing only ([§3.1](#31-failure-semantics-most-send-failures-are-invisible)) |

## 8. Campaign mail versus transactional mail

The two are asymmetric, and the asymmetry is easy to misread as a product-wide capability.

| | Campaign (marketing) | Transactional (invites, notifications, reports, account mail) |
|---|---|---|
| Bounce handling | Yes — **ESP provider with webhooks only** | **None** |
| Complaint handling | Yes — same condition | **None** |
| Suppression list | Yes — `unsubscribe`, `hard_bounce`, `complaint`, `do_not_contact`, `manual` | **None** |
| Unsubscribe | Yes — body link, per recipient | **None** |
| `List-Unsubscribe` header | **No** | **No** |
| Per-message outcome | Recorded against the delivery row | Swallowed ([§3.1](#31-failure-semantics-most-send-failures-are-invisible)) |

**Bounce and complaint telemetry requires an ESP provider with webhooks.** A hard bounce or a
complaint arriving on the webhook endpoint records a suppression entry and revokes consent
automatically. On the **built-in SMTP provider none of that exists**: you get `sent` or `rejected` at
handoff and nothing after, so `delivered`, `bounced`, and `complained` on the campaign engagement
figures are all zero. Zero there means *no receipts*, not *no bounces* — do not read a clean SMTP
campaign report as a clean campaign.

**Transactional mail has no bounce handling, no suppression, and no unsubscribe at all.** That
machinery is exclusively campaign-scoped. An invite sent to a dead address bounces to the `From`
mailbox and Connex never learns of it; sending to that address again is not prevented. If your
transactional volume is high enough that repeated hard bounces threaten your sending reputation, that
monitoring has to happen at the relay.

**Unsubscribe is a body link only, for campaigns only.** No `List-Unsubscribe` or
`List-Unsubscribe-Post` header is emitted anywhere. Mailbox providers that surface a one-click
unsubscribe affordance will not do so for Connex mail, and bulk-sender programs that require the
header are not satisfied by this build. If you send campaign volume that falls under such a
requirement, terminate it at an ESP that adds the header itself.
