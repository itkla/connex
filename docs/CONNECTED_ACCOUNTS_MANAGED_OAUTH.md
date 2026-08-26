# Connected Accounts — Connex-Managed OAuth

How a user connects their own Google or Microsoft mailbox and calendar to a Connex installation
without the operator first creating a Google Cloud project, and what that costs and guarantees in
security, privacy, and vendor independence.

Related: [CONNECTED_CAPTURE.md](CONNECTED_CAPTURE.md) for what happens to a connection after it
exists, [SECURITY.md](SECURITY.md) for the general posture,
[ENCRYPTION_GUARANTEE_MATRIX.md](ENCRYPTION_GUARANTEE_MATRIX.md) for how the stored token bundle is
protected, [DEPLOYMENT.md](DEPLOYMENT.md) for the install shapes, and
[MULTITENANCY_PLAN.md](MULTITENANCY_PLAN.md) for the workspace/tenant boundaries the connection sits
inside.

## Status today — read this first

**The Connex-managed mode is not usable in this build.** The Connex-owned Google application is
still going through restricted-scope verification and production-app approval, tracked in
[#868](https://github.com/itkla/connex/issues/868). The managed client id therefore ships **blank**,
the managed capability is not advertised, and every managed connect attempt fails closed with
`managed_identity_unavailable`. The account screen says so explicitly instead of showing a generic
error.

**Custom / bring-your-own credentials is the working path today.** Everything in the "Custom" column
below is shipped and supported, and it remains supported after managed mode turns on. Nothing in
this document asks an operator to migrate away from it.

## The three credential modes

| | **1. Connex-managed** (intended default) | **2. Custom / bring-your-own** (shipped fallback) | **3. Domain-wide delegation** (not implemented) |
| --- | --- | --- | --- |
| OAuth client identity | Connex-owned, provider-verified application | An application the operator created in their own Google Cloud / Entra tenant | A Workspace service account with organization-wide impersonation |
| Who consents | Each user, for their own mailbox | Each user, for their own mailbox | An administrator, once, for everyone |
| Operator setup | Choose the mode; no Cloud project | Create the project/app, configure the consent screen, request scopes, obtain verification | Create a service account, have a super administrator authorize scopes org-wide |
| Where credentials live | Encrypted, user-scoped, on the installation | Encrypted, user-scoped, on the installation | An installation-wide credential impersonating any user |
| Status in Connex | **Fail-closed pending #868** | **Available** | **Not implemented and not planned as the per-user path** |

Domain-wide delegation is listed only so the question has a written answer. It is an exceptional
enterprise arrangement, it collapses per-user consent into one administrator decision, and it hands
one credential the ability to read every mailbox in the organization. Connex does not implement it,
does not impersonate users by default, and would treat any future support for it as a separately
reviewed, separately gated enterprise feature — never as the ordinary way a person connects their
own mail.

## The managed flow, end to end

The design constraint is that a Connex-owned OAuth application requires fixed, pre-registered
redirect URIs, but an on-premises installation's hostname is unknown to Connex and must not be
registered with, or routed through, anything Connex owns. The flow resolves this by putting the
redirect on **the user's own machine**: a small helper process binds a loopback port, and the
provider redirects there. The instance never has to be reachable from the internet, and no
Connex-owned host takes part.

The critical secrets are split so that no single participant holds enough to complete the exchange:

- the **PKCE code verifier** is minted and kept by the instance backend; it never leaves it;
- the **handoff ticket** is single-use, issued to whoever claimed the pairing code, and required to
  submit the authorization code;
- the **authorization code** is the only value that touches the local helper, and it is useless
  without both of the above.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Browser as Browser (authenticated session)
    participant Instance as Connex instance (on-prem)
    participant Helper as Local helper (user's machine)
    participant Google as Provider (Google / Microsoft)

    User->>Browser: Connect account
    Browser->>Instance: POST /native/{provider}/pairing (session + step-up re-auth)
    Instance-->>Browser: pairingCode, expiresAt, instanceBaseUrl, helperCommand
    User->>Helper: Run helper and enter pairing code on stdin
    Helper->>Helper: Bind loopback port, form redirectUri http://127.0.0.1:{port}/callback
    Helper->>Instance: POST /native/prepare {pairingCode, redirectUri}
    Instance->>Instance: Validate loopback redirectUri, mint PKCE verifier/challenge + state
    Instance-->>Helper: authorizeUrl, handoffTicket, accountLabel, expiresAt
    Helper->>User: Show destination Connex account and require confirmation
    Helper->>Browser: Open authorizeUrl
    Browser->>Google: Consent to read-only mail, calendar, OpenID identity
    Google-->>Helper: Redirect to loopback with code + state
    Helper->>Instance: POST /native/complete {handoffTicket, code, state}
    Instance->>Google: Token exchange with code + PKCE verifier + client secret
    Google-->>Instance: Access + refresh tokens, id_token
    Instance->>Instance: Store encrypted, user-scoped bundle; mark pairing completed
    Browser->>Instance: GET /native/{provider}/pairing (polled ~2s)
    Instance-->>Browser: status completed
```

### Endpoint contract

Base path `/api/account/connections/native`.

| Method + path | Caller | Auth | Purpose |
| --- | --- | --- | --- |
| `POST /{provider}/pairing` | Browser | Session + step-up re-authentication | Issues `pairingCode`, `expiresAt`, `instanceBaseUrl`, `helperCommand` |
| `GET /{provider}/pairing` | Browser | Session | Returns `status` (`none`, `pending`, `prepared`, `exchanging`, `completed`, `failed`), `errorCode`, `expiresAt` |
| `DELETE /{provider}/pairing` | Browser | Session | Cancels the pairing so a stale code cannot be claimed |
| `GET /helper` | Browser | Session | Serves the helper script (`text/plain`) for download |
| `POST /prepare` | Local helper | None — the pairing code is the bearer | Claims the code, validates the redirect URI, returns `authorizeUrl`, the pairing owner's `accountLabel`, and a single-use `handoffTicket` |
| `POST /complete` | Local helper | None — the handoff ticket is the bearer | Submits `code` + `state`; the instance performs the token exchange |

Failures answer with `{ "error": "<machine_code>" }`. The account screen renders each code as a
specific sentence: `managed_identity_unavailable`, `invalid_redirect_uri`, `state`, `denied`,
`exchange`, `no_offline_access`, `connection_conflict`, `retained_data_reset_required`,
`superseded`; anything else falls back to one honest "couldn't be completed" message rather than
inventing a diagnosis.

### Redirect URI validation

`POST /prepare` accepts a redirect URI only when **every** one of these holds:

- scheme is exactly `http` (loopback is the one place where plaintext is correct — TLS to `127.0.0.1`
  would require a certificate the helper cannot legitimately hold);
- host is exactly `127.0.0.1` or `[::1]` — no `localhost`, no name that could resolve elsewhere, no
  other literal;
- port is in `1024`–`65535`, so the helper never asks for a privileged port;
- path is exactly `/callback`;
- there is no query string, no fragment, and no user-info component.

Anything else is refused with `invalid_redirect_uri`. This is what keeps a stolen pairing code from
redirecting the provider's response to an attacker-controlled host.

## Threat model

The trust boundary is: the user's browser session, the instance backend, and the provider. The
helper process is **inside** the user's machine but **outside** the secret boundary — it is
deliberately given the least useful secret in the exchange.

| Threat | What the attacker gains | What stops it | Residual risk |
| --- | --- | --- | --- |
| A malicious local process races the helper callback or wins the loopback bind first | The authorization `code` and `state` | The helper returns `404` unless callback `state` matches the value in its provider URL, without consuming the callback. The code is also single-use, PKCE-bound, and worthless without the backend-held verifier and handoff ticket | A hostile process can still deny service by holding the port. A process that can also learn the exact provider URL/state can race a matching callback, so same-user local compromise remains outside the protocol boundary |
| Pairing-code theft (shoulder-surfing, a screenshot, a pasted support ticket, or use of the discouraged `--pairing-code` argument) | The ability to call `prepare` for the pairing owner's Connex account | The default helper reads the code from stdin rather than exposing it in process arguments. The code is short-lived (10 minutes), single-use, cancellable, and constrained to loopback redirects. The prepare response names the destination Connex account and the helper requires confirmation before provider consent | Storing under the pairing owner prevents a thief from pulling the owner's mailbox into the thief's Connex account, but does not prevent a malicious code holder from attaching an attacker-controlled mailbox to the owner's account. Treat pairing codes as short-lived secrets |
| A pairing owner sends their helper command and code to another person | An attempt to attach that person's mailbox to the pairing owner's Connex account | Before opening the provider, the helper prints the pairing owner's email, display name, or user id and accepts only an explicit `y`/`yes` confirmation | A person who ignores the account label or uses `--yes` can still be phished. The protocol cannot make an informed user heed the warning |
| Handoff-ticket theft | The ability to submit a code once | The ticket is single-use, expires with the pairing, and is bound to that pairing and its redirect URI | Requires already having compromised the helper's process memory or transport |
| Replay of a consumed pairing code or ticket | Nothing | Both are one-shot: claiming a code moves the pairing to `prepared`, and completing consumes the ticket. Replays are refused (`state` or `superseded`) | — |
| A forged or cross-user callback (submitting a code obtained for a different user or client) | Attempted binding of a different mailbox to the pairing owner's account | `state` must match the value the backend minted for that pairing, the exchange uses that pairing's verifier, and the helper makes the destination account explicit before consent | The destination remains the pairing owner by design. A user who ignores the label or passes `--yes` can still consent a mailbox into somebody else's pairing |
| `state` tampering | Nothing | The shipped helper returns `404` without consuming a wrong-state callback. A mismatch submitted directly to the backend still fails closed and terminates the claimed handoff | A local process that learns the exact expected state can still submit a matching denial callback |
| A hostile `redirectUri` at `prepare` | Redirecting the provider response to an attacker host | The loopback-only rules above; anything else is `invalid_redirect_uri` | — |
| A network attacker between helper and instance | Pairing code, ticket, and authorization code in transit | The helper talks to the instance over the operator's own transport. **Serve the instance over HTTPS.** On a plain-HTTP install, everything on that hop is exposed to anyone on the path | Real, and the operator owns it. Loopback traffic between the browser, the helper, and the provider redirect stays on the machine |
| A hostile helper binary (user ran something that is not the shipped script) | The authorization code, plus whatever else that process can do on the machine | Nothing in this protocol — the user has already run attacker code as themselves | Explicitly outside the trust boundary. Download the helper from `GET /helper` on the instance you are connecting to |
| A stale pairing left open | A window in which a code is still claimable | Expiry (10 minutes), explicit `DELETE` on cancel or dialog close, and supersession when a new pairing starts. Expired session rows and retained verifier secrets are reaped after a one-day recovery grace | — |
| An administrator wanting another user's mailbox | — | The credential is user-scoped, encrypted, and never rendered; administrators can restrict what is allowed but cannot assume a user's grant | An administrator can still remove a user or purge captured workspace data — see [CONNECTED_CAPTURE.md](CONNECTED_CAPTURE.md) |

Two properties do the heavy lifting against authorization-code interception: **the PKCE verifier
never leaves the backend**, and **the redirect can only ever be loopback**. Together they prevent a
local process that did not claim the pairing from redeeming an intercepted provider code. They do
not make the pairing code safe to share; the destination-account label and confirmation address
that separate social-engineering risk.

## Vendor-disconnection guarantee

**No Connex-owned domain participates in authorization or in steady-state synchronization.**
Specifically, there is no Connex-hosted:

- authorization broker or redirect/callback host;
- token exchange, refresh, or revocation proxy;
- mailbox or calendar proxy;
- Cross-Account Protection / RISC receiver;
- webhook or Pub/Sub relay;
- mandatory telemetry, license check, or phone-home on the connect path.

Connex the company contributes exactly one thing to managed mode: the OAuth **client identity**
(client id and secret) shipped inside the release artifact. Every request that carries user data
goes from the installation to the provider.

### Egress allowlist

| Host | Purpose |
| --- | --- |
| `accounts.google.com` | Google authorization/consent (the user's browser; the URL is built by the instance) |
| `oauth2.googleapis.com` | Google token exchange, refresh, and revocation (instance → Google) |
| `www.googleapis.com` | Google Calendar API and related Google APIs (instance → Google) |
| `gmail.googleapis.com` | Gmail API (instance → Google) |
| `login.microsoftonline.com` | Microsoft authorization and token exchange/refresh (browser and instance) |
| `graph.microsoft.com` | Microsoft Graph mail and calendar (instance → Microsoft) |

An operator may block **every** Connex-owned domain at the egress firewall and the integration keeps
working. That is the test of this claim: if blocking `*.connexcrm.jp` breaks connecting or syncing,
the guarantee has been violated and it is a bug.

## Vendor-disconnected is not air-gapped

These are two different things and conflating them causes real disappointment:

- **Disconnected from Connex** — the installation needs nothing from Connex to run, authorize, or
  sync. This is guaranteed above.
- **Disconnected from the internet** — an installation with no route to Google or Microsoft
  **cannot** integrate with Gmail, Google Calendar, Outlook, or Microsoft 365. There is no offline
  mode, no relay, and no export/import substitute for OAuth. A truly air-gapped installation runs
  Connex without connected accounts and without connected capture; everything else in the product
  still works.

If an environment forbids egress to Google and Microsoft, the honest answer is "connected accounts
are not available here," not "we will proxy it for you."

## Multi-user model

- Many independent per-user grants exist per installation. Each is authorized by that user, stored
  encrypted under that user's scope, and usable only on their behalf.
- One connection per user per provider today. Reconnecting replaces the existing grant for that
  provider rather than accumulating credentials.
- There is **no** installation-wide mailbox credential, **no** default service-account
  impersonation, and no path by which one user's grant reads another user's mail.
- Administrators govern the envelope, not the credential: which providers are enabled, which capture
  streams and body/metadata modes are permitted, backfill windows, exclusions, and the visibility of
  what is captured (see [CONNECTED_CAPTURE.md](CONNECTED_CAPTURE.md)). They never receive another
  user's tokens and never gain access to another user's mailbox through Connex.
- A user disconnecting best-effort revokes and deletes that user's credential while retaining
  captured content. Explicit current-workspace erasure and membership/account removal follow the
  separate retention and erasure paths documented in [CONNECTED_CAPTURE.md](CONNECTED_CAPTURE.md).

## Google Workspace administrator steps

Keep these two things separate — they are different jobs, and managed mode removes only the first:

### Cloud project creation — not required in managed mode

In managed mode the operator does **not** create a Google Cloud project, does **not** configure an
OAuth consent screen, does **not** request restricted scopes, and does **not** undergo verification.
Connex owns the application and its verification. (In Custom/BYO mode the operator does all of this
themselves — that is the entire difference between the modes.)

### Workspace organization policy — may still be required

Independently of who owns the application, a Google Workspace organization may restrict which
third-party apps its users may authorize. If it does, an administrator must trust the Connex
application before users can connect:

1. Google Admin console → **Security** → **Access and data control** → **API controls** →
   **App access control**.
2. **Manage third-party app access** → **Configure new app** → search by the app name or **OAuth
   client ID** (the Connex-managed client id for the release you are running).
3. Set the app to **Trusted** (or explicitly allow the requested scopes) for the relevant
   organizational units.
4. If **Block all third-party API access** is on, or the requested Gmail/Calendar scopes are
   restricted, the app must be explicitly allowed or users will see the provider's own consent
   refusal.

The equivalent on Microsoft is Entra ID user-consent policy and, where required, admin consent for
the application in the tenant.

Users may also need to be told that the consent screen names the Connex application rather than
their own company's project. That is the visible trade-off of managed mode.

## Synchronization posture

- Synchronization is **bounded polling with delta contracts**: Gmail `historyId`, Google Calendar
  `syncToken`, Microsoft Graph delta anchors. Pages are bounded, requests carry hard deadlines, and
  cursors are persisted. Details are in [CONNECTED_CAPTURE.md](CONNECTED_CAPTURE.md).
- **Connex-hosted Pub/Sub, webhooks, or push relays are prohibited** on the default on-premises
  path. They would reintroduce a vendor-owned component on the data path, which is precisely what
  this design refuses.
- A **customer-managed** Google Cloud Pub/Sub subscription (the customer's own project, their own
  topic, their own endpoint) may exist as an optional advanced mode for lower latency. It can never
  be required, and a customer who does not want it loses nothing but latency.

## Operating the shared Connex application

Managed mode means many customers' installations use one Connex-owned OAuth client. That has
consequences an operator deserves to know about up front.

- **Quota.** Google's per-project API quotas (Gmail units/day, per-user rate limits) are shared
  across every installation using the managed client. Per-user limits are unaffected by other
  customers; project-wide limits are not. Connex monitors project quota and raises limits as
  adoption grows, and the bounded-polling design keeps per-user consumption predictable. An
  installation that needs guaranteed, isolated quota should use Custom/BYO credentials — that is a
  legitimate and supported reason to choose it.
- **Abuse controls.** Pairing codes are short-lived and rate-limited per user; `prepare` and
  `complete` are single-use; the loopback-only redirect rule prevents the shared client from being
  used as an open redirector for someone else's application.
- **Incident response.** If the managed client is compromised or abused, Connex may rotate the
  client secret or, at worst, disable the client. Rotation invalidates nothing that is already
  stored except the ability to refresh — a rotated secret ships in a release and installations
  update; a **disabled** client stops refresh for every installation using managed mode until they
  either update or switch to Custom/BYO. Provider-side action against the application (Google
  suspending it) has the same blast radius. This shared-fate property is the honest cost of managed
  mode, and it is the reason Custom/BYO remains a first-class, permanently supported path.
- **Provider incident duties** — revocation, notification, and evidence handling — follow the
  incident steps in [CONNECTED_CAPTURE.md](CONNECTED_CAPTURE.md) and the release governance in
  [#868](https://github.com/itkla/connex/issues/868).

### Migrating from managed to Custom/BYO without losing data

Tokens are client-specific; captured data is not. The migration is therefore a re-authorization, not
a data movement:

1. Create the operator's own OAuth application and configure
   `CONNEX_CONNECTED_ACCOUNTS_<PROVIDER>_CLIENT_ID` / `_CLIENT_SECRET`.
2. Set the provider's mode to `custom` and restart.
3. Each user reconnects once. Existing grants issued under the managed client stop refreshing; the
   user reconnects through the ordinary browser redirect flow.
4. **Locally captured data and connection rows are unaffected.** Activities, participants, source
   envelopes, review decisions, policies, and cursors all stay where they are.
5. Re-linking is deterministic because the stable provider account id is
   `provider:issuer:subject` — derived from the provider's OpenID `iss`/`sub` claims, not from the
   OAuth client. The same mailbox reconnecting under a different client resolves to the same account
   identity, so the reconnect reattaches to the existing connection instead of creating a duplicate.
6. A user who wants to reconnect a **different** mailbox must first confirm the all-workspace
   retained-data reset. Connex erases the old mailbox's captured namespace and deletes its
   credential-free tombstone before the different account identity can create a new connection;
   it never combines two mailboxes in one retained namespace.

The reverse direction (Custom → managed) works the same way once managed mode is available.

## What Connex the vendor can and cannot see

Because Connex owns the Cloud project behind the managed client, Google shows Connex the metrics
Google shows any project owner:

**Can see** (Google-controlled, aggregate, application-level):

- aggregate API call volume, error rates, and latency for the project;
- quota consumption against project limits;
- aggregate consent/authorization counts and Google's own app-verification and abuse signals.

**Cannot see** (and does not receive):

- any roster of customers, installations, or accounts using the application;
- user credentials, access tokens, or refresh tokens — these are exchanged by, and stored on, the
  installation;
- mailbox or calendar content, participants, subjects, or bodies;
- synchronization metadata, cursors, capture policies, or activity data;
- installation-correlated OAuth telemetry — Connex does not receive, and does not add, any signal
  tying an authorization to a particular customer or deployment.

Aggregate project metrics are a property of being a Google Cloud project owner. They contain no
customer identity and no user data, and Connex adds nothing to them.

## Operator runbook

### Choosing a mode

Per provider, in application configuration:

```yaml
connex:
  connected-accounts:
    google:
      mode: custom   # custom | managed
    microsoft:
      mode: custom
```

- `custom` — the shipped default. Requires `CONNEX_CONNECTED_ACCOUNTS_GOOGLE_ENABLED=true`,
  `CONNEX_CONNECTED_ACCOUNTS_GOOGLE_CLIENT_ID`, and
  `CONNEX_CONNECTED_ACCOUNTS_GOOGLE_CLIENT_SECRET` (and the `MICROSOFT` equivalents). The user
  connects through the ordinary browser redirect at `POST /api/account/connections/{provider}/authorize`.
- `managed` — uses the Connex-owned client from `CONNEX_MANAGED_GOOGLE_CLIENT_ID` /
  `CONNEX_MANAGED_GOOGLE_CLIENT_SECRET`. **These ship blank pending #868**, so the capability stays
  off and the account screen explains why. The mode is inert, not broken.

Everything fails closed: an unset client id disables the provider rather than starting a flow that
cannot finish.

### How a user connects (managed mode)

1. Account → **Connected accounts** → **Connect** on the provider (a passkey step-up is required).
2. Copy the pairing code and download the helper from the dialog.
3. Run it on the same machine, with Node.js 18 or newer. The pairing code is prompted for on stdin so
   it does not normally appear in `ps` or `/proc/<pid>/cmdline`:

   ```bash
   node connex-connect.mjs --instance https://connex.example.com
   ```

4. Enter the pairing code, verify that the printed Connex account label is your own account, and
   answer `y` or `yes`. Any other answer exits without opening the provider.
5. Approve the consent screen the helper opens. The dialog polls every ~2 seconds and reports
   completion; the code expires after 10 minutes and can be cancelled at any time.

The helper needs outbound access to the instance and to the provider, and permission to bind a
loopback port. It is a plain script — read it before running it.

For controlled non-interactive use, provide the pairing code on stdin from the automation's secret
source and pass `--yes`:

```bash
printf '%s\n' "$PAIRING_CODE" |
  node connex-connect.mjs --instance https://connex.example.com --yes
```

`--yes` skips only the destination-account confirmation and therefore preserves the phishing risk
that the prompt is designed to reduce. The compatibility form `--pairing-code <code>` still works,
but is explicitly discouraged because local processes may read command-line arguments.

### Diagnosing failure codes

| Code | Meaning | Operator action |
| --- | --- | --- |
| `managed_identity_unavailable` | No managed client id is configured in this build | Expected today (#868). Use Custom/BYO credentials |
| `invalid_redirect_uri` | The helper proposed a non-loopback or malformed callback | Ensure the user runs the helper downloaded from `GET /helper` on this instance; a modified or third-party helper is refused by design |
| `state` | A matching callback reached the backend but its `state` did not match the pairing, or the pairing had already moved on | Have the user start again. The shipped helper rejects wrong-state callbacks locally; repeated backend occurrences warrant checking whether the helper or local machine was modified or compromised |
| `denied` | The user declined at the provider, or organization policy blocked the app | Check Workspace/Entra app access control (above) |
| `exchange` | The provider rejected the token exchange | Check client id/secret validity, system clock skew, and egress to the token host |
| `no_offline_access` | No refresh token was granted | The user must approve every requested permission; on Microsoft confirm `offline_access` is consented |
| `superseded` | A newer pairing replaced this one | Benign — the user started the flow twice. Finish the newest one |

For anything else, the UI shows one honest failure message and the pairing is terminated rather than
retried silently. Support diagnostics stay metadata-only: status, code, timestamps. Pairing codes,
tickets, authorization codes, and tokens must never be written to logs or pasted into tickets.
