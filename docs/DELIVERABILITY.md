# Email deliverability

> Status: describes the mail behaviour that ships today. **Connex signs nothing and publishes
> nothing.** It hands a message to the SMTP relay you configure; every deliverability control that
> inbox providers actually evaluate — SPF, DKIM, DMARC — lives in your DNS and your relay, not in
> this application. One diagnostic *reads* SPF and DMARC presence and reports a status — never a
> record's contents, and never affecting what is sent ([§1](#1-what-connex-does-and-does-not-do)).
> Read it before pointing a production instance at a mail server.

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
- **Publish SPF, DKIM, or DMARC records.** Nothing in the product emits a DNS record of any kind.
  Publishing is your job; signing is your relay's.
- **Resolve DNS to decide whether a message may be sent.** The mail path performs DNS in two places
  and neither one gates delivery. It resolves a **workspace-supplied** SMTP host so
  `SmtpDestinationGuard` can pin the connection to a single verified address (an SSRF/DNS-rebinding
  defence, see [§2.4](#24-the-smtp-destination-is-pinned)); the instance transport is exempt: it is
  not workspace-supplied, so the guard returns immediately and JavaMail resolves `connex.mail.host`
  itself, unpinned, at connect time. And the mail diagnostics send-test performs the advisory TXT
  lookups described immediately below.
- **Set a separate envelope sender.** `mail.smtp.from` is never set, so the SMTP `MAIL FROM` is
  derived from the header `From`. See [§5](#5-aligning-the-from-address).
- **Set `Reply-To`.** Replies go to the `From` address.
- **Emit `List-Unsubscribe` or `List-Unsubscribe-Post` headers.** Campaign unsubscribe is a body
  link only ([§8](#8-campaign-mail-versus-transactional-mail)).
- **Send a `text/plain` alternative.** Every message is single-part `text/html`. `MailMessage.html(…)`
  is the only constructor any sender uses, so the plain-text half is always null and
  `MimeMessageHelper` is built non-multipart. A missing plain-text part is a mild spam signal with
  some filters, and there is no setting that adds one.

**One advisory exception: the mail diagnostics send-test reads SPF and DMARC.**
`POST /api/workspaces/{id}/mail/diagnostics/test-send` ([§6.1](#61-the-send-tests)) performs two
bounded, content-free TXT lookups against the effective sender's domain — `v=spf1` at the domain
itself, and `v=dmarc1` at `_dmarc.<domain>`. It reports a **status only** — `present`, `unknown`, or
`not_configured` — and **never the contents of a record**. A resolvable domain with no matching
record and a domain that does not resolve at all deliberately share the single `unknown` status, and
no record count is returned, so the endpoint is **not a domain-existence oracle** against whatever
resolver the instance uses. The lookup is fail-soft: a resolver failure yields `unknown` and never
changes the send outcome. **DKIM is never checked** — its status is unconditionally
`not_configured`, because no selector field exists anywhere in the mail configuration and so there is
nothing to look up.

**That lookup is skipped entirely unless the sender is workspace-supplied.** Under managed mail, or
when a workspace falls back to the instance transport, the send-test still sends and still reports
the transport outcome, but reports **no DNS status at all**: no domain, and SPF and DMARC both
`unknown`. This is deliberate, for two reasons — a tenant cannot act on a sender domain it does not
control, so the check has no value there, and that is precisely the path that would turn the endpoint
into a domain-existence oracle. On a managed-mail workspace, an empty DNS section is the expected
result and not a fault.

**Therefore: DMARC alignment is entirely your relay's responsibility.** Choosing a relay that will
sign as your domain, and publishing the records that authorize it, is the deliverability work. There
is no Connex setting that substitutes for it, and the advisory check above reports posture without
changing any of it.

Two in-product send-tests exist today, both in [§6.1](#61-the-send-tests), and nothing else. Richer
in-product mail diagnostics remain tracked separately.

## 2. Choosing a mail shape

Two **SMTP transport** shapes exist. They differ in **who owns the transport and the sending
identity**.

| | Instance / managed mail | Per-workspace SMTP override |
|---|---|---|
| Configured by | Operator, via `connex.mail.*` env | Workspace admin, via **Settings → Email** |
| Sending identity | One instance-wide `From` | Each workspace's own `From` |
| Password storage | Environment / `.env` | Central secret store, encrypted per workspace |
| Turned on by | `connex.mail.enabled=true` | Saving an enabled config in the UI |
| Locked to instance-only by | `connex.mail.managed=true` | — |
| Allowed on `on-prem` | **Yes** — only `connex.mail.managed` is forbidden, see below | Yes |

**A third sending identity exists outside both**, reachable only by campaigns dispatched through an
ESP delivery provider. It is not an SMTP transport and `MailConfigResolver` never sees it — see
[§2.2](#22-which-from-address-is-used) before you publish any DNS.

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
  nothing in managed mode. Only the **workspace SMTP send-test** uses it, deliberately, so that test
  validates exactly what the workspace configured rather than silently proving the instance relay
  works. The diagnostics send-test uses `resolveForWorkspace` instead, precisely so that it covers
  the managed and fallback cases ([§6.1](#61-the-send-tests)).

**The fallback in step 3 is silent to the user — and effectively unreachable through the UI.** When it
does fire, a workspace keeps receiving mail sent as the *instance* identity, from a domain it does not
control. But `saveConfig` refuses to enable a config with a blank host (*"SMTP host is required to
enable workspace email"*) or a blank from-address (*"A from address is required to enable workspace
email"*) **before writing the row**, and those two fields are exactly what `usable()` tests. **Saving
through the UI can no longer produce a config that falls back.** The warn branch survives only for a
legacy row or one written straight to the database.

Treat the log line ([§6.2](#62-what-to-check-in-logs)) as confirmation when you already suspect such a
row — not as the first thing to grep. A workspace mailing under an unexpected identity is far more
likely to be managed mode, or an ESP `From` ([§2.2](#22-which-from-address-is-used)).

### 2.2 Which `From` address is used

**There are three sending identities, not two.** The two SMTP shapes above are joined by a third that
never touches `MailConfigResolver`: when a campaign is delivered through an **ESP delivery provider**,
the `From` address and display name come from that workspace's **delivery provider configuration**
row (**Settings → Delivery**) — not from `connex.mail.*`, and not from the workspace SMTP override.

| Identity | Used by | `From` address | `From` display name |
|---|---|---|---|
| Instance | account mail always; workspace mail in managed mode, or as the fallback | `connex.mail.from`, or `connex.mail.username` when blank | `connex.mail.from-name` (default `Connex`) |
| Workspace SMTP override | workspace mail, and campaigns on the built-in `smtp` provider | the workspace's *From address*, or its *username* when blank | the workspace's, falling back to the instance name |
| **ESP delivery provider** | **campaigns dispatched through the ESP provider — nothing else** | **the provider config's *From address*** (Settings → Delivery) | **the provider config's *From name***, omitted from the payload when blank |

The workspace's port also falls back to `connex.mail.port` (default `587`) when unset.

**The ESP identity takes part in no precedence at all.** `HttpEspDeliveryProvider` builds its send
payload directly from the resolved provider config's `fromAddress` and `fromName`; there is no
fallback to `connex.mail.*` and no fallback to the workspace SMTP override, in either direction. Only
the built-in `smtp` delivery provider re-resolves the transport through `MailConfigResolver`, and only
that path inherits the two-tier fallback in [§2.1](#21-how-a-sender-is-resolved).

**So an ESP `From` domain is a separate domain you must publish DNS for**
([§4](#4-dns-records-per-deployment-shape)). This is the single easiest way to get deliverability
wrong on this system, because [§8](#8-campaign-mail-versus-transactional-mail) actively steers bulk
senders onto an ESP: configure the instance and workspace domains perfectly, terminate campaigns at an
ESP, and **every campaign then sends from a domain with no SPF, DKIM, or DMARC at all** — while every
check in this document still looks green.

### 2.3 What counts as a "usable" configuration

A resolved configuration is usable when its **host is non-blank and its From address is non-blank**.
That is the entire test.

This floor is lower than operators expect, and the consequence matters: a wrong port, a wrong
password, TLS misconfiguration, or a host that refuses the connection all produce a **usable**
configuration. Such a config is selected and used — it does *not* trigger the fallback-to-instance
warning. It fails later, at transport time, where most senders swallow the error
([§3.1](#31-failure-semantics-most-send-failures-are-invisible)). Only a blank host or a blank From
address makes a workspace configuration fall back — and saving now blocks both of those
([§2.1](#21-how-a-sender-is-resolved)), so in practice a saved config is always "usable" and the floor
never rejects anything.

### 2.4 The SMTP destination is pinned

`SmtpDestinationGuard` resolves the SMTP host and `PinnedSocketFactory` connects to that exact
address, so the name cannot be re-resolved to a different host between check and connect.

**The guard governs workspace-supplied hosts only.** `resolveForSend` returns `null` immediately when
`config.workspaceSupplied()` is false, so **the instance relay is trusted and never checked** — not at
startup, not at send time, ever. A workspace host is checked twice: once at save time, and again at
send time.

`connex.mail.allow-internal-hosts` (default **false**) is what permits a private-network relay; it is
**forbidden on the `saas` profile** and allowed on `silo` and `on-prem`. A workspace override pointing
at an internal host is refused at save time on a hardened profile. Two consequences follow:

- **A private-network *instance* relay needs no flag.** It is never validated, so setting
  `allow-internal-hosts=true` on its behalf achieves nothing — and on `saas` that setting is a **hard
  startup failure**, so reaching for it turns a working instance into a crash loop. Set it only to let
  a *workspace override* reach an internal host.
- **The flag is checked first and returns early**, so enabling it also switches off the port allowlist
  below, not just the private-address check.

### 2.5 The SMTP port allowlist

`SmtpDestinationGuard` additionally enforces a fixed port allowlist — **25, 465, 587, and 2525**. Any
other port is refused with *"SMTP port must be one of 25, 465, 587, or 2525"*, at save time and again
at send time, for workspace-supplied hosts. A relay listening on a non-standard submission port cannot
be configured as a workspace override, and no setting extends the list.

**`connex.mail.allow-internal-hosts=true` returns before the port check**, so it disables the port
allowlist as well — a wider grant than the name suggests. The instance transport, being unchecked, has
no port restriction at all.

## 3. What mail Connex sends

Nine senders exist. `sendForWorkspace` uses the workspace identity (with instance fallback);
`sendInstance` uses the instance identity **only**; `sendNow` is synchronous and throws.

| Mail | Transport call | Identity | Enabled by |
|---|---|---|---|
| Workspace invite | `sendForWorkspace` | workspace → instance | `connex.mail.enabled`, **or** any enabled workspace override; link built from `connex.mail.app-base-url` |
| Notification email channel | `sendForWorkspace` | workspace → instance | the recipient's per-user `email` notification preference |
| Scheduled report delivery | `sendForWorkspace` | workspace → instance | `connex.reports.scheduling-enabled` (default **true**) |
| Password reset | `sendInstance` | **instance only** | `connex.password-reset.email-enabled` (default **false**) |
| Email-change verification | `sendInstance` | **instance only** | `connex.email-change.email-enabled` (default **false**) |
| Registration verification | `sendInstance` | **instance only** | `connex.registration-verification.enabled` **and** `.email-enabled` (both default **false**) |
| Mail settings test email | `sendNow` (synchronous) | **workspace only**, no fallback | on demand, from Settings → Email |
| Mail diagnostics test email | `sendNow` (synchronous) | the **effective** transport: instance in managed mode, else workspace → instance | on demand, from Settings → Diagnostics; throttled ([§6.1](#61-the-send-tests)) |
| Campaign delivery | own dispatcher, never async | **built-in `smtp` provider:** workspace → instance. **ESP provider:** the provider config's own `From`, no fallback ([§2.2](#22-which-from-address-is-used)) | `connex.delivery.enabled` **and** `connex.delivery.dispatch-enabled` (both default **false**) |

Five consequences worth planning around:

**`connex.mail.enabled=true` alone does not start sending account mail.** It wires the default sender
and lets invites be emailed. Password reset, email-change verification, and registration
verification each have their own flag and **all three default to false** — until you set them, those
flows record only that delivery was unavailable. They never write a usable bearer link to a log.
Enabling the transport and expecting reset mail to start flowing is a routine misdiagnosis.

**There is no log-based or manual bearer-retrieval fallback.** On a mail-disabled or air-gapped
deployment, configure a reachable instance SMTP relay and set `connex.mail.enabled=true`. Then set
`connex.password-reset.email-enabled=true` for resets,
`connex.email-change.email-enabled=true` for email changes, or both
`connex.registration-verification.enabled=true` and its `.email-enabled=true` for registration
verification. Before enabling each flow, set its link origin to the canonical public origin:
`CONNEX_PASSWORD_RESET_BASE_URL=https://<host>`,
`CONNEX_EMAIL_CHANGE_BASE_URL=https://<host>`, and
`CONNEX_REGISTRATION_VERIFICATION_BASE_URL=https://<host>`, respectively. Then have the user issue a
fresh request. An internal relay is appropriate for an air-gapped network. Until that transport
exists, leave registration verification disabled and do not initiate email changes; Connex has no
supported procedure that exports those credentials to an operator.

**Account-level mail always leaves as the instance identity.** The three `sendInstance` flows ignore
workspace overrides entirely. If `connex.mail.*` is unconfigured, they have no sender at all, no
matter how many workspaces have working SMTP. Plan the instance identity's DNS even on an instance
where every workspace brings its own relay.

**`connex.mail.enabled=false` is not an instance-wide kill switch.** `resolveForWorkspace` never
consults it on the workspace-override branch: it checks `managed`, then the workspace's own enabled,
usable config, and reaches `resolveInstance()` — the only place the flag is read — solely as a
fallback. So with the flag off, every workspace holding its own enabled SMTP config keeps sending
invites, notifications, scheduled reports, and campaigns; only the three `sendInstance` flows stop.
**Stopping all outbound mail takes `connex.mail.managed=true` *and* `connex.mail.enabled=false`** —
managed mode routes workspace mail into `resolveInstance()`, which then returns nothing. Clearing the
flag alone during an incident stops far less than it looks like, and the operator will believe
outbound has halted when it has not.

**Campaign mail on an ESP leaves under a third identity.** The `workspace → instance` resolution above
holds for the built-in `smtp` provider only. On an ESP provider the `From` is the delivery provider
config's own, with no fallback to either — a distinct domain with its own DNS obligations
([§2.2](#22-which-from-address-is-used), [§4](#4-dns-records-per-deployment-shape)).

**Three templates render with a hard-coded `en` locale: invite, notification, and registration
verification.** Password reset, email-change verification, scheduled report delivery, and both
send-tests all render in the recipient's or actor's locale. This is a content matter, not an
authentication one, but the three hard-coded ones surprise Japanese-language deployments.

Neither `connex.delivery.enabled` nor `connex.delivery.dispatch-enabled` appears in
`application.yml`; both are bound from the environment only, and campaign mail sends only when
**both** are true.

### 3.1 Failure semantics: most send failures are invisible

`sendInstance` and `sendForWorkspace` are `@Async` and **swallow every failure**. The delivery helper
logs and returns; nothing propagates to the request that triggered it, and no user-visible error is
produced. Only `sendNow` — the transport behind both send-tests — throws, and even there the calling
service catches the throw and returns a generic message or a stable error code
([§6.1](#61-the-send-tests)).

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
- **Campaigns on an ESP delivery provider** — **a third domain that neither of the above covers.** The
  ESP `From` is set per workspace in **Settings → Delivery** and is used verbatim in the ESP send
  payload; nothing in `connex.mail.*` or the workspace SMTP override touches it. If you followed
  [§8](#8-campaign-mail-versus-transactional-mail) and terminated bulk mail at an ESP, **this is the
  domain your campaigns actually send from.** Publish its full set, or every campaign fails DMARC
  while your instance and workspace domains stay green.
- **Both** — do both, and remember the account-mail carve-out above: the instance domain still needs
  records even when every workspace overrides. If campaigns run through an ESP, that is a third set on
  top.

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
  step in the application. That absence is also why the diagnostics send-test reports DKIM as
  `not_configured` unconditionally: with no selector to query, there is nothing it could look up
  ([§6.1](#61-the-send-tests)). Read that status as *not implemented*, never as *your DKIM is
  missing*.

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

Connex sets the header `From` and **no other address or authentication header** — no `Reply-To`, no
`Sender`, no `Return-Path`, no `List-Unsubscribe`, no DKIM signature. (The message naturally still
carries `To`, `Subject`, `Content-Type`, and the `Message-ID`, `Date`, and `MIME-Version` JavaMail
adds.) `mail.smtp.from` is never set, so **the envelope sender is derived from the header `From`: they
are the same address, and there is no separate Return-Path or bounce address to configure.**

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

### 6.1 The send-tests

**Two send-tests ship, and they are not interchangeable.** The older one validates what a workspace
*saved*; the newer one, added with tenant diagnostics, validates the transport a workspace *actually
sends through* — including the managed and fallback cases the older one refuses to touch.

| | `POST /mail-config/test` (existing) | `POST /mail/diagnostics/test-send` (new) |
|---|---|---|
| Surfaced at | Settings → Email | Settings → Diagnostics |
| Resolves | `resolveWorkspaceOnly` — workspace SMTP only | `resolveForWorkspace` — managed / override / instance fallback |
| Managed mode | refused (`requireWorkspaceOverridesAllowed`) | works, and mutates no config |
| Recipient | the caller's own address | the caller's own address, and the actor's email must be **verified** |
| DNS | none | advisory SPF + DMARC, fail-soft, **skipped unless the sender is workspace-supplied** |
| Gate | `WORKSPACE_SETTINGS` + recent authentication | same |
| Host/port shown | the workspace transport's | **suppressed unless workspace-supplied**, so a managed relay is never disclosed |
| Throttled | no | **yes** — see below |

Both write the same audit event, `workspace.mail_config.test`; only the description distinguishes
them (*"Sent a test email"* versus *"Sent a diagnostic test email"*). Neither mutates any
configuration.

**The workspace SMTP send-test — `POST /api/workspaces/{id}/mail-config/test`.** Settings → Email has
a **send test** action, backed by this endpoint. It is unchanged by the diagnostics work.

- Requires the `WORKSPACE_SETTINGS` permission **and** recent authentication (step-up re-auth).
- Resolves via `resolveWorkspaceOnly`, so it exercises **exactly what the workspace saved**, with no
  instance fallback to make a broken config look healthy.
- Re-checks the destination through `SmtpDestinationGuard` before connecting.
- Sends **synchronously** via `sendNow`, so the outcome is known before the response returns rather
  than swallowed on an async thread. **The response does not carry the real error, though.** A
  transport failure is caught and returned as the fixed string *"Could not send the test email. Check
  the host, port, and credentials."*; the underlying cause appears only in the WARN log
  ([§6.2](#62-what-to-check-in-logs)). Only the destination guard's own refusals — a private address,
  a port outside the allowlist — come back verbatim.
- Sends **only to the requesting user's own account email**. It is not a way to mail an arbitrary
  address. If the account has no email, the test refuses rather than sending.
- Refused with `403` in managed mode, because workspace overrides do not exist there.
- Writes the audit event `workspace.mail_config.test`.

**The diagnostics send-test — `POST /api/workspaces/{id}/mail/diagnostics/test-send`.** Settings →
Diagnostics has its own test action, backed by this endpoint.

- Same gate: `WORKSPACE_SETTINGS` **and** recent authentication.
- Resolves via `resolveForWorkspace`, so it exercises the transport the workspace's mail really
  leaves through — the managed instance relay, the workspace override, or the instance fallback
  ([§2.1](#21-how-a-sender-is-resolved)). **It therefore works in managed mode**, where the older
  test returns `403`, and it changes no stored configuration.
- Sends **only to the requesting user's own account email**, and additionally requires that address
  to be **verified**. An unverified or missing address returns an outcome rather than a message.
- Re-checks the destination through `SmtpDestinationGuard` before connecting, exactly as the older
  test does.
- Returns a structured, redacted report: the effective mode, the sender identity, the transport
  outcome, a stable error code, a correlation id, and the advisory DNS block from
  [§1](#1-what-connex-does-and-does-not-do). **It does not return the underlying SMTP error** — a
  transport failure comes back as the code `smtp_transport_failed`, and a guard refusal as
  `smtp_destination_rejected`.
- Writes the audit event `workspace.mail_config.test`, scoped to the workspace named in the path
  rather than the ambient tenant context.

**It is throttled: 3 sends per 5 minutes, per workspace and actor together.** The cap is
`connex.mail.diagnostics.max-test-sends` (default **3**) over
`connex.mail.diagnostics.test-send-window-seconds` (default **300**), keyed `workspaceId:actorId`.
Exceeding it does not raise an error — the call returns normally with a **failed** outcome and the
error code `rate_limited`, and **no mail is sent**. A tester who fires the button repeatedly will see
this and conclude the transport is broken; it is not. The limiter is **single-JVM only**, the same
model as the password-reset limiter, so a multi-replica deployment gets a proportionally higher
effective rate. (There is deliberately no deployment-wide bound; that, the organization-scope query
fan-out, and the snapshot-id oracle are known and tracked, not shipped.)

**Managed mode discloses the sender address but not the host or port, on purpose.** The test message
is delivered to the requesting administrator's own mailbox, so its `From` is already disclosed to
exactly that person; withholding it in the report would only make the report harder to read. The
relay host and port are withheld because they are not otherwise disclosed to a tenant, and a tenant
can do nothing about the operator's transport anyway. The same reasoning is why the advisory DNS
lookup is skipped for a sender the tenant does not control.

**Their limits, stated plainly.** Both send-tests are **SMTP connectivity and authentication tests**
first. Neither inspects the headers of the message it sent, and neither performs any seed-list,
reputation, or inbox-placement check. The workspace SMTP test checks no DNS at all. The diagnostics
test adds only the **advisory** SPF and DMARC presence check of
[§1](#1-what-connex-does-and-does-not-do) — presence, never contents, never DKIM, and skipped
entirely on a sender the workspace did not supply. A green send-test tells you the credentials work
and the relay accepted the message; a `present` SPF or DMARC status tells you a record of that kind
exists. Neither tells you whether the message will reach an inbox, nor whether anything **aligned**.

To assess authentication you need the delivered message itself: send to a mailbox you control, open
the raw source, and read the `Authentication-Results` header the receiver added. That is where you
find out whether DKIM signed, what `d=` it used, and whether DMARC aligned. Neither send-test can
substitute for it — a published record is not a passing one — and neither can anything else in the
product today.

### 6.2 What to check in logs

Backend logs are structured ECS JSON, so these are greppable by message. The mail path emits three
distinct lines:

| Log line | Level | Means |
|---|---|---|
| `Workspace {} has SMTP enabled but its config is unusable; falling back to the instance default sender` | WARN | The workspace's saved config has a blank host or blank From address. Mail is going out as the **instance** identity. Save-time validation blocks this, so expect it only for a legacy or hand-written row ([§2.1](#21-how-a-sender-is-resolved)). |
| `Email to {} not sent: no usable SMTP configuration ({})` | WARN | Nothing usable resolved at all. The message was **dropped**, not queued or retried. |
| `Failed to send email to {} ({}): {}` | ERROR | The relay was reached and the send failed. The relay's own message is the tail of the line. This is the async swallow path — the user saw no error. |

The parenthesized source is `instance` or `workspace <id>`, which tells you which identity was
attempted. The workspace SMTP send-test additionally logs `Test email for workspace {} failed: {}` at
WARN with the underlying cause, while returning a generic message to the browser.

**The diagnostics send-test logs no failure line at all.** `sendNow` throws rather than logging, and
that path catches the throw and converts it into a response error code without a log statement, so a
failed diagnostic send leaves nothing greppable behind. Its only log line is
`Diagnostic test email audit could not be written for workspace {}` at WARN, which reports an audit
write failure and not a mail failure. Read the response's error code and correlation id
([§6.1](#61-the-send-tests)); for a cause, reproduce through the workspace SMTP send-test, which does
log one.

**A fourth failure path emits none of these three.** Resolution runs *outside* `deliverQuietly`'s
try/catch — `sendForWorkspace` calls `resolveForWorkspace` first and only then enters the guarded
helper. If resolution itself throws (a failure decrypting the workspace SMTP password in the secret
store, say), the exception escapes the `@Async` method entirely and surfaces through Spring's
uncaught-async-exception handler, not through any line in the table. Make sure that handler's output
is collected too.

**There is no retry and no outbound queue.** A swallowed failure is a permanently lost message.
Alerting on that ERROR line is the only way to learn about a swallowed *transport* failure — and it
will not catch the resolution failure above.

## 7. Common failure modes

| Symptom | Likely cause | Fix |
|---|---|---|
| Mail arrives, but `From` is the instance address, not the workspace's | Usually `connex.mail.managed=true`, which ignores the workspace row outright. Only rarely the silent fallback — save-time validation blocks a blank host or From address, so that needs a legacy or hand-written row | Check `connex.mail.managed` first. Then grep for `has SMTP enabled but its config is unusable; falling back to the instance default sender`; if it appears, re-save the config through the UI to repair the row |
| Campaign mail leaves under a `From` that matches neither `connex.mail.*` nor the workspace override | The campaign went out through an **ESP delivery provider**, whose `From` comes from its own config row and has no fallback | Expected. Read the ESP `From` in Settings → Delivery and publish SPF, DKIM, and DMARC for **that** domain ([§2.2](#22-which-from-address-is-used)) |
| Outbound mail continues after setting `connex.mail.enabled=false` | Not an instance-wide kill switch — workspaces with their own enabled SMTP never consult it | Also set `connex.mail.managed=true`; only the pair stops workspace mail ([§3](#3-what-mail-connex-sends)) |
| A user reports mail never arrived and saw no error in the UI | `sendForWorkspace`/`sendInstance` are `@Async` and swallow failures — nothing surfaces to the browser | Grep for `Failed to send email to` and `Email to {} not sent`; alert on both. The message is gone; there is no retry |
| Reset / verification mail never sends although `connex.mail.enabled=true` | Each flow has its own flag, all defaulting false | Set `connex.password-reset.email-enabled`, `connex.email-change.email-enabled`, and/or `connex.registration-verification.enabled` + `.email-enabled` |
| A reset or verification request logs that delivery is unavailable, but no link appears | The flow's `email-enabled` flag is false, or the instance transport is unusable. Connex intentionally never logs the bearer | Configure a reachable instance SMTP relay, enable the relevant account-mail flag, and issue a fresh request; there is no log-based retrieval path |
| The **Email** settings tab is missing; `/settings/email` redirects to Members | `connex.mail.managed=true`; workspace overrides are disabled instance-wide | Expected. Configure `connex.mail.*` instead, or unset `managed` — note `on-prem` forbids `managed` outright |
| Startup fails with `connex.deployment.profile=on-prem forbids: connex.mail.managed=true` | Managed mail on a customer-run install | Unset `CONNEX_MAIL_MANAGED`; configure your own relay ([DEPLOYMENT_EDITIONS.md](DEPLOYMENT_EDITIONS.md)) |
| Campaign unsubscribe link in the email body is unclickable | `connex.delivery.public-base-url` is unset, so the URL is emitted as the relative path `/api/delivery/unsubscribe/{token}` | Set it to the instance's absolute public base URL before sending any campaign |
| Campaigns never dispatch although `connex.delivery.enabled=true` | `connex.delivery.dispatch-enabled` also defaults false; neither key is in `application.yml` | Set both in the environment |
| Mail is accepted by the relay but lands in spam or is rejected by DMARC | The relay signs with its own `d=`, or rewrites `MAIL FROM` to its own bounce domain, so nothing aligns with your `From` | Have the relay sign as your From domain and publish its selector; verify with the `Authentication-Results` header of a real delivered message ([§6.1](#61-the-send-tests)) |
| Every workspace-override domain fails DMARC while the instance domain passes | Only the instance domain's records were published | Each workspace's From domain needs its own SPF, DKIM, and DMARC records ([§4](#4-dns-records-per-deployment-shape)) |
| SPF fails on a domain that clearly has an SPF record | Two `v=spf1` records, or more than 10 lookup mechanisms — both are permanent errors | `dig +short TXT <domain>` and collapse to one record within the limit |
| Saving a workspace SMTP host is refused: *"…must be a public server; private and loopback addresses are not allowed"* | `SmtpDestinationGuard` rejected a private-network destination; `connex.mail.allow-internal-hosts` is false and forbidden on `saas` | Use a publicly routable relay, or run the profile that permits internal hosts. Do **not** set the flag for an *instance* relay — that host is never checked, and the flag is a startup failure on `saas` ([§2.4](#24-the-smtp-destination-is-pinned)) |
| Saving a workspace SMTP host is refused: *"SMTP port must be one of 25, 465, 587, or 2525"* | The port allowlist, a separate check from the private-address one | Use an allowlisted submission port; the list is fixed and no setting extends it ([§2.5](#25-the-smtp-port-allowlist)) |
| The diagnostics send-test suddenly returns a failed outcome with `rate_limited`, and no mail arrives | The per-actor throttle: 3 sends per 5 minutes per workspace-and-actor. Not a transport fault | Wait out the window, or raise `connex.mail.diagnostics.max-test-sends`. It is single-JVM, so replicas each carry their own allowance ([§6.1](#61-the-send-tests)) |
| The diagnostics send-test reports no DNS domain, and SPF and DMARC as `unknown`, on a workspace whose mail works | The sender is not workspace-supplied — managed mode or the instance fallback — so the advisory lookup is skipped deliberately | Expected, not a bug. Check the instance `From` domain's records with `dig` yourself ([§4.4](#44-verifying-the-records-with-dig)) |
| The diagnostics send-test reports SPF or DMARC `unknown` on a domain you believe is published | `unknown` covers both *no matching record* and *the lookup did not resolve* — the two are deliberately indistinguishable | Confirm with `dig` from a host whose resolver you trust ([§4.4](#44-verifying-the-records-with-dig)). The check is advisory and never blocks a send |
| The diagnostics send-test always reports DKIM as `not_configured` | It is hard-coded to that: there is no DKIM selector field anywhere in the mail configuration, so nothing is looked up | Expected. Verify DKIM at the relay and with `dig` against the selector the relay issued ([§4.2](#42-dkim)) |
| A diagnostic send fails and nothing appears in the logs | That path returns an error code instead of logging; only an audit-write failure logs | Read the response error code and correlation id; reproduce through the workspace SMTP send-test for a logged cause ([§6.2](#62-what-to-check-in-logs)) |
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

**If you do that, publish DNS for the ESP's `From` domain before the first send.** An ESP provider
sends under the `From` on its own config row — not `connex.mail.*`, not the workspace SMTP override —
so it is a domain [§4](#4-dns-records-per-deployment-shape) does not otherwise cover. Moving bulk mail
to an ESP to satisfy a bulk-sender program, and leaving that domain without SPF, DKIM, and DMARC,
fails the program on the authentication requirement instead. See
[§2.2](#22-which-from-address-is-used).
