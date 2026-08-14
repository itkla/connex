# Edge defence and managed WAF

This document is the decision record and operating runbook for CHK-083. It is the authoritative,
reviewable Cloudflare configuration for the public Connex SaaS edge and the origin-side baseline for
every supported deployment edition. Cloudflare account, DNS, certificate, and firewall changes are
manual owner actions; this repository does not contain credentials or claim that those actions have
already happened.

Related controls: [deployment topology](DEPLOYMENT.md#topology),
[deployment editions](DEPLOYMENT_EDITIONS.md), [security posture](SECURITY.md), and
[special-care data policy](SPECIAL_CARE_DATA_POLICY.md). Contractual subprocessor controls are in
the [APPI DPA template](APPI_DPA_TEMPLATE.md).

## Decision record

| Public configuration | Decision | Accountable owner | Status and review |
|---|---|---|---|
| Shared SaaS at `connexcrm.jp` | Adopt Cloudflare WAF on a Business plan or a contract that provides at least the same five rate-limiting rules, managed rules, exceptions, bot controls, and security-event visibility specified below. | Cloudflare Account Owner | Account deployment and DNS cutover are manual. Complete every gate in this runbook before public traffic. Review quarterly and after any material route or Cloudflare product change. |
| Staging at `preview.connexcrm.jp` | Adopt the same Cloudflare zone policy before using staging for public security retest. Staging is the canary for rule changes before production. | Cloudflare Account Owner | Account deployment and DNS cutover are manual. The current systemd application deploy does not configure the public edge. |
| Public Connex-operated `silo` | The bundle does not impose a managed WAF because the customer may control its hostname and edge account. Public exposure is blocked until a deployment-specific Cloudflare/equivalent WAF is evidenced or the pending residual-risk record below is countersigned. | Connex Security Owner role | Proposed maximum expiry **2026-11-13** and re-review **2026-10-13** if accepted. Do not expose while the record is pending. |
| Public customer-operated `on-prem` | Cloudflare is not adopted by the product because Connex cannot route or inspect a customer's private-network traffic. The customer must deploy its approved equivalent or countersign the pending public residual-risk record before internet exposure. | Customer Security Owner role | Proposed maximum expiry **2026-11-13** and re-review **2026-10-13** if accepted. Internet exposure is blocked while neither alternative is evidenced. |
| Private-network-only `on-prem` | No managed public WAF. The network boundary, VPN/private ingress, Caddy controls, and application controls are the compensating controls. | Customer Security Owner role | Re-review whenever the service becomes internet-reachable and no later than **2026-11-13**. |

The proposed, countersignature-dependent silo/on-prem threat acceptance covers direct automated
exploitation, bot abuse, credential stuffing,
request floods, and expensive endpoint abuse that an adaptive managed edge could have rejected
before origin capacity was consumed. Compensating controls are the request/header/time bounds in
the shipped Caddy configuration, the application's endpoint-specific request limits and precise
login/passkey/business-card/AI controls, private service networks, and the deployment owner's
firewall or private ingress. Those controls reduce impact but do not provide managed signatures,
bot reputation, or globally coordinated rate counters; that residual risk is why the acceptance is
owner- and expiry-bound.

Local evaluation and development profiles are not supported public configurations and are outside
this decision.

### Pending formal residual-risk records

| Record | Scope | Accountable owner | Approval authority | Status | Re-review if accepted | Maximum expiry / exit condition |
|---|---|---|---|---|---|---|
| `EDGE-RISK-SILO-2026-01` | An internet-reachable Connex-operated silo without a managed WAF | Connex Security Owner role | Risk and Exception Approver role | Pending countersignature before exposure | 2026-10-13 | 2026-11-13, or earlier when Cloudflare/equivalent evidence passes the public retest |
| `EDGE-RISK-ONPREM-PUBLIC-2026-01` | Internet-reachable customer-operated on-prem without a customer-managed WAF | Customer Security Owner role | Customer Risk Acceptance Authority role | Pending customer countersignature before exposure | 2026-10-13 | 2026-11-13, customer-approved WAF evidence, or ownership change, whichever occurs first |
| `EDGE-RISK-ONPREM-PRIVATE-2026-01` | Private-network-only customer-operated on-prem without a managed WAF | Customer Security Owner role | Customer Risk Acceptance Authority role | Pending customer countersignature before deployment | 2026-10-13 | 2026-11-13, network exposure, or ownership change, whichever occurs first |

Each record proposes acceptance of the threat and compensating controls stated immediately above;
none is approval evidence while its status is Pending. The deploying organization must copy the
applicable record into its signed control register, assign the named roles, record the acceptance
date, and retain the approval reference. The accepted expiry may be earlier but never later than the
maximum above. A repository edit cannot countersign or renew a record. A private on-prem deployment
moving to public exposure must close the private record and approve the public record before the
change. SEC-92 remains `NG`/In Review until the required deployment evidence and independent retest
exist.

## Control boundaries

### Stock Caddy is the origin baseline

[`deploy/Caddyfile`](../deploy/Caddyfile) is intentionally valid with the pinned stock
`caddy:2.11.4-alpine` image. The image contains `http.handlers.request_body` and
`http.ip_sources.static`, but it does not contain `http.handlers.rate_limit`. Caddy documents rate
limiting as a non-standard module requiring a custom build. Connex does not add that third-party
module: doing so would introduce a fourth release image and a new privileged ingress dependency for
a control already provided at the selected SaaS edge. The managed Cloudflare rate rules below are
therefore mandatory for SaaS; the absence of an origin rate plug-in is part of the explicit
silo/on-prem residual risk above.

Caddy enforces these ceilings while the downstream proxy reads the request body; the application
independently enforces the same contracts:

| Route class | Edge ceiling | Application contract |
|---|---:|---|
| `/api/imports` and descendants | 64 MiB | `CONNEX_IMPORT_MAX_BODY_BYTES=67108864` |
| Attachment, assistant-attachment, user/person image, and company-logo upload routes | 27 MiB | A 27 MiB multipart envelope around `ObjectStorageProperties.maxUploadBytes`, whose default stored object maximum is 25 MiB |
| `/api/business-cards` and descendants | 12 MiB | `CONNEX_BUSINESS_CARD_MAX_BODY_BYTES=12582912`; decoded card bytes remain separately limited to 8 MiB |
| `/api/client-errors` | 16 KiB | `CONNEX_CLIENT_ERRORS_MAX_BODY_BYTES=16384` |
| `/api/auth/webauthn` and descendants | 64 KiB | `CONNEX_WEBAUTHN_MAX_BODY_BYTES=65536` |
| `/api/workflows` and descendants | 96 KiB | `CONNEX_WORKFLOW_MAX_BODY_BYTES=98304` |
| Other `/api` routes | 10 MiB | `CONNEX_API_MAX_BODY_BYTES=10485760` |
| `/saml2/*`, `/api/login/saml2/sso/*`, and frontend routes | 1 MiB | Matches the application form-body ceiling without constraining the SAML POST binding |

Compose passes the same `CONNEX_*_MAX_BODY_BYTES` values to Caddy and the backend, with the defaults
shown above, so an operator override changes both boundaries together. A standalone Caddy launch
must supply the same values. The object-storage byte maximum remains a separate inner limit; raising
an upload or card envelope also requires joint review of Spring's independent multipart file/request
caps and the object-storage maximum, or the inner application boundary will still reject it.

The server allows 10 seconds for request headers, five minutes for the whole request body, two minutes for
an idle HTTP keep-alive connection, and at most 64 KiB of request headers. It deliberately has no
server write deadline: an HTTP write timeout is the wrong control for the long-lived `/api/ws`
upgrade. The body deadline means a 64 MiB import must average at least about 213 KiB/s and a 27 MiB
upload at least about 92 KiB/s. Validate those minimum rates on every supported public or on-prem
ingress; a deployment that cannot meet them needs a reviewed timeout change rather than an implicit
exception. Caddy and Cloudflare WebSocket keepalive behavior must be monitored separately.

Caddy adds `Strict-Transport-Security: max-age=31536000; includeSubDomains` to normal and error
responses only when `CONNEX_CADDY_HSTS_ENABLED=true`. The shared SaaS origin configuration must set
this variable after the HTTPS validation and redirect gates below. The Connex-operated silo template
sets it, while on-prem and eval templates default it off because those editions may intentionally use
plain HTTP on a private network. An HTTPS-enabled on-prem operator may opt in only after validating
every hostname covered by `includeSubDomains`. Never enable it merely to compensate for a missing
HTTP-to-HTTPS redirect.

Validate every Caddy edit against the deployment's pinned digest and actual non-secret Caddy
environment from the repository root before deploy:

```bash
sudo docker compose --env-file deploy/.env -f deploy/docker-compose.yml \
  run --rm --no-deps caddy caddy validate --config /etc/caddy/Caddyfile
python3 .github/scripts/test_edge_security_headers.py
python3 .github/scripts/test_deployment_networks.py
```

### Real client IP chain

Caddy accepts `CF-Connecting-IP` and `X-Forwarded-For` only when the direct peer is in Cloudflare's
published IPv4/IPv6 ranges or the deployment's explicit additional-proxy list, and parses that
external chain in strict right-to-left order. An unlisted direct caller cannot reset a throttle by
supplying either header. Before proxying, Caddy overwrites `X-Forwarded-For` with its single resolved
`{client_ip}` value and removes `CF-Connecting-IP`; the backend therefore never reinterprets a
Cloudflare chain. `ClientIpResolver` accepts exactly one IP literal from a configured sanitizing
peer and returns it without checking whether that client value is also in a trusted range. This is
intentional: `172.20.5.10` may be a real on-prem client behind a private Caddy container and must not
collapse to Caddy's address for login/passkey throttling or audit attribution.

The Compose backend port must remain unpublished. The silo and on-prem profiles trust all RFC 1918
socket peers because Docker assigns service addresses dynamically, not from a pinned subnet; that
range is used only to decide whether the immediate peer may supply the one sanitized value. Eval
leaves `CONNEX_SECURITY_TRUSTED_PROXIES` empty. Publishing the backend port or allowing an
unsanitized private peer to reach it would make the broad private-peer trust unsafe and is not a
supported topology.

An operator who puts another proxy between Caddy and Cloudflare may add only that proxy's exact
egress CIDR to `CONNEX_CADDY_ADDITIONAL_TRUSTED_PROXIES` and must restrict the listener so no other
peer can use that address. The added proxy contract is strict: it must discard any inbound
`CF-Connecting-IP` and `X-Forwarded-For`, then set `CF-Connecting-IP` to its authenticated client IP
or emit an equally sanitized XFF chain. Prove a forged value cannot win before accepting that
topology. Never use an unrestricted public range or trust all incoming forwarding headers.

Review `https://www.cloudflare.com/ips-v4/` and `https://www.cloudflare.com/ips-v6/` quarterly and
before cutover. Any difference requires one reviewed Caddy/firewall change, Caddy validation, and a
spoofing retest. A new, not-yet-trusted Cloudflare range fails toward inaccurate proxy attribution,
not toward trusting a client-supplied address.

### Edge versus application throttling

Cloudflare owns coarse per-source volumetric and bot controls before traffic consumes origin
capacity. It does not decide whether a credential is valid, and its distributed counters may allow
a small overshoot. The application remains authoritative for failed password and passkey attempts,
per-account lockout, per-organization AI budgets, idempotency, and business-card admission. Edge
limits count requests regardless of outcome and are intentionally higher than normal interactive
use; application limits count the domain event they understand. Do not lower an edge threshold to
mirror an application failure limit.

## Origin lock-down

A proxied DNS record alone is insufficient because a caller who learns the origin address can
bypass the WAF. Before either SaaS host is proxied, the Cloudflare Account Owner and infrastructure
operator must choose and record one of these designs:

1. **Preferred: Cloudflare Tunnel.** Run `cloudflared` as a separately managed, outbound-only
   service, publish no origin HTTP port to the internet, and route only the intended hostname to
   Caddy. Add only the tunnel peer's exact address to
   `CONNEX_CADDY_ADDITIONAL_TRUSTED_PROXIES`. Tunnel credentials belong in the deployment secret
   store, never this repository or a support bundle.
2. **Alternative: restricted public origin.** Permit origin TLS only from the current Cloudflare
   IPv4/IPv6 ranges, drop every other source, use Full (strict) TLS, and enable Authenticated Origin
   Pulls with a deployment-specific origin certificate. Cloudflare's shared pull certificate alone
   proves Cloudflare network origin, not the Connex zone, so prefer a zone-specific certificate.
   The shipped Caddy listener is intentionally plain HTTP inside its deployment boundary; this
   alternative therefore requires a reviewed deployment-specific Caddy TLS listener and secret
   mount, or a separate authenticated TLS terminator that forwards to Caddy only over loopback or a
   protected private segment. Never expose the shipped port 80 as the public-origin substitute.

In either design, confirm that backend, frontend, database, and OCR ports remain unpublished. Keep
an authenticated out-of-band administration path that does not expose the application listener.
The firewall range list and Caddy trusted-proxy list are one reviewed change set; updating only one
can cause an outage or incorrect attribution.

## Authoritative Cloudflare configuration

The repository deliberately uses a precise ruleset rather than Terraform. This lane has no account,
zone ID, existing ruleset IDs, state backend, API-token boundary, or contract details. Terraform
without those inputs would be an un-runnable second source of truth. The following stable rule IDs,
ordered expressions, actions, thresholds, and exceptions are the configuration artefact the account
owner must reproduce in the dashboard for both SaaS hosts and export for review afterward.

### Zone prerequisites

- Select Business or a contract with equivalent features; confirm all five rate rules are available
  before DNS cutover. Set the zone maximum upload size to at least 100 MB so the 64 MiB import
  contract survives. Do not create an unproxied upload hostname.
- Proxy `connexcrm.jp` and `preview.connexcrm.jp`; use Full (strict) TLS. Enable WebSockets. Do not
  enable HSTS yet: first validate Cloudflare-to-origin certificate authentication and every
  compatibility flow over HTTPS.
- After HTTPS validation succeeds, enable **Always Use HTTPS** for an edge `301` redirect and prove
  each hostname redirects `http://` to the same `https://` hostname without accepting a login form
  over HTTP. Enable Cloudflare edge HSTS only after that redirect proof, with a one-year max age and
  `includeSubDomains`; leave preload disabled. Inventory and validate every subdomain before enabling
  `includeSubDomains`, because HSTS rollback cannot recover a hostname that lacks working HTTPS.
- Leave HTTP DDoS protection at the Cloudflare managed defaults. Deploy the Cloudflare Managed
  Ruleset and Cloudflare OWASP Core Ruleset with their documented defaults. Start new or materially
  changed managed rules in observation where the plan supports it, review 24 hours of staging
  events, then enforce the vendor default actions. Do not enable every disabled managed rule.
- Disable caching for `/api/*`, `/saml2/*`, `/auth/*`, `/dashboard*`, `/settings*`, and any response
  carrying `Set-Cookie`, `Cache-Control: private`, or `Cache-Control: no-store`. Only explicitly
  public immutable assets may be made cache-eligible. Never cache a response selected by session or
  workspace cookies.

### Ordered custom and exception rules

Cloudflare expressions below are restricted to the two intended hosts. Preserve this order and
enable Skip logging except where the table explicitly requires it disabled.

| ID | Expression | Action and purpose |
|---|---|---|
| `CF-CUSTOM-01-METHODS` | Intended host and method is `TRACE` or `CONNECT` | Block. Connex exposes neither method. |
| `CF-EX-01-WEBSOCKET` | Host is `connexcrm.jp` or `preview.connexcrm.jp`, and path is exactly `/api/ws` | Skip Super Bot Fight Mode. Keep managed WAF inspection of the initial handshake; the generic rate expression excludes this path. Logging may remain enabled because this path carries no credential. |
| `CF-EX-02-TOKEN-CALLBACKS` | Intended host and path starts with `/api/delivery/webhooks/` or `/api/delivery/unsubscribe/` | Skip Super Bot Fight Mode, rate limiting, and managed WAF. Disable Skip-rule logging because the path contains a credential. Application webhook signature/token verification and idempotent unsubscribe handling remain authoritative. |
| `CF-EX-03-SAML` | Intended host, method `POST`, and path starts with `/api/login/saml2/sso/` | Skip Super Bot Fight Mode and interactive challenges. Preserve the form body, cookies, and redirect response unchanged. Keep managed WAF inspection unless one rule ID is proven incompatible. |
| `CF-EX-04-UPLOADS` | Intended host and an upload path listed in the Caddy table, including `/api/imports/*` and `/api/business-cards/*` | Skip Super Bot Fight Mode so multipart/binary clients are not challenged mid-transfer. Do not skip the dedicated upload rate rule, the origin body cap, or all managed WAF rules. |

Enter these expressions exactly, then select the actions and phase skips named in the table:

```text
CF-CUSTOM-01-METHODS
(http.host in {"connexcrm.jp" "preview.connexcrm.jp"} and http.request.method in {"TRACE" "CONNECT"})

CF-EX-01-WEBSOCKET
(http.host in {"connexcrm.jp" "preview.connexcrm.jp"} and http.request.uri.path eq "/api/ws")

CF-EX-02-TOKEN-CALLBACKS
(http.host in {"connexcrm.jp" "preview.connexcrm.jp"} and (starts_with(http.request.uri.path, "/api/delivery/webhooks/") or starts_with(http.request.uri.path, "/api/delivery/unsubscribe/")))

CF-EX-03-SAML
(http.host in {"connexcrm.jp" "preview.connexcrm.jp"} and http.request.method eq "POST" and starts_with(http.request.uri.path, "/api/login/saml2/sso/"))

CF-EX-04-UPLOADS
(http.host in {"connexcrm.jp" "preview.connexcrm.jp"} and http.request.method in {"POST" "PUT"} and (http.request.uri.path eq "/api/attachments/upload" or (starts_with(http.request.uri.path, "/api/ai/assistant/sessions/") and ends_with(http.request.uri.path, "/attachments")) or http.request.uri.path eq "/api/users/me/profile-picture" or (starts_with(http.request.uri.path, "/api/persons/") and ends_with(http.request.uri.path, "/profile-picture")) or (starts_with(http.request.uri.path, "/api/companies/") and ends_with(http.request.uri.path, "/logo")) or starts_with(http.request.uri.path, "/api/imports/") or starts_with(http.request.uri.path, "/api/business-cards/")))
```

Add configuration rule `CF-CONFIG-01-COMPATIBILITY` with the expression below. Set Browser
Integrity Check to Off and Security Level to Essentially Off. This prevents the independent Browser
Integrity Check and an emergency Under Attack setting from challenging provider callbacks,
one-shot SAML POSTs, WebSocket handshakes, or binary/large uploads; managed DDoS protection remains
active.

```text
CF-CONFIG-01-COMPATIBILITY
(http.host in {"connexcrm.jp" "preview.connexcrm.jp"} and (http.request.uri.path eq "/api/ws" or starts_with(http.request.uri.path, "/api/delivery/webhooks/") or starts_with(http.request.uri.path, "/api/delivery/unsubscribe/") or (http.request.method eq "POST" and starts_with(http.request.uri.path, "/api/login/saml2/sso/")) or (http.request.method in {"POST" "PUT"} and (http.request.uri.path eq "/api/attachments/upload" or (starts_with(http.request.uri.path, "/api/ai/assistant/sessions/") and ends_with(http.request.uri.path, "/attachments")) or http.request.uri.path eq "/api/users/me/profile-picture" or (starts_with(http.request.uri.path, "/api/persons/") and ends_with(http.request.uri.path, "/profile-picture")) or (starts_with(http.request.uri.path, "/api/companies/") and ends_with(http.request.uri.path, "/logo")) or starts_with(http.request.uri.path, "/api/imports/") or starts_with(http.request.uri.path, "/api/business-cards/")))))
```

Configure Super Bot Fight Mode according to the recorded origin-lock design:

| Bot category | Cloudflare Tunnel | Restricted public origin |
|---|---|---|
| Verified Bots | Allow | Allow |
| Definitely Automated | Managed Challenge after staging evidence | Managed Challenge after staging evidence |
| Likely Automated | Managed Challenge | Managed Challenge |

Cloudflare Tunnel's connector is outbound-only and does not require visitor requests classified as
Definitely Automated to bypass bot mitigation. The managed WAF, rate rules, and DDoS controls
remain active in both designs. The four Skip rules above remain mandatory because bot
classification can otherwise break provider callbacks, WebSocket upgrades, SAML POST binding,
unsubscribe links, and large multipart uploads.

### Rate-limiting rules

Use source IP as the counting characteristic, a `429`/Block mitigation, and the exact host scope
above. Deploy on staging first. These are coarse abuse ceilings, not user entitlements.

| ID | Matching requests | Threshold | Mitigation |
|---|---|---:|---:|
| `CF-RL-01-AUTH-ASSERT` | `POST /api/auth/login`, `POST /api/auth/webauthn/authenticate/options`, and `POST /api/auth/webauthn/authenticate` | 30 requests / 60 seconds / IP | Block 60 seconds |
| `CF-RL-02-ACCOUNT-LIFECYCLE` | `POST /api/auth/register`, `/api/auth/forgot-password`, `/api/auth/reset-password`, `/api/auth/verify-email/confirm`, `/api/users/me/verify-email/resend`, and `POST` paths ending in `/accept` below `/api/invites/` or `/api/invite-links/` | 20 requests / 60 seconds / IP | Block 300 seconds |
| `CF-RL-03-AI` | `POST /api/ai/*`, `POST /api/deals/*/brief`, `POST /api/deals/*/rationale`, `POST /api/introductions/suggestions/rationale`, and `POST /api/reports/*/generate` | 60 requests / 60 seconds / IP | Block 60 seconds |
| `CF-RL-04-UPLOADS` | Multipart/import/business-card routes listed in `CF-EX-04-UPLOADS` | 30 requests / 60 seconds / IP | Block 60 seconds |
| `CF-RL-05-API-VOLUME` | All `/api/*` requests except `/api/ws`, `/api/delivery/webhooks/*`, `/api/delivery/unsubscribe/*`, and `POST`/`PUT` requests counted by the dedicated upload rule | 1,200 requests / 60 seconds / IP | Block 60 seconds |

Use these rate-rule expressions exactly. `CF-RL-04-UPLOADS` intentionally repeats the upload
expression because its exception skips only the bot phase, not the rate phase. `CF-RL-05` excludes
an upload path only when the method is `POST` or `PUT`, because only those requests are counted by
`CF-RL-04`; `GET`, `HEAD`, `DELETE`, and every other method on those path families remain under the
generic API ceiling.

```text
CF-RL-01-AUTH-ASSERT
(http.host in {"connexcrm.jp" "preview.connexcrm.jp"} and http.request.method eq "POST" and http.request.uri.path in {"/api/auth/login" "/api/auth/webauthn/authenticate/options" "/api/auth/webauthn/authenticate"})

CF-RL-02-ACCOUNT-LIFECYCLE
(http.host in {"connexcrm.jp" "preview.connexcrm.jp"} and http.request.method eq "POST" and (http.request.uri.path in {"/api/auth/register" "/api/auth/forgot-password" "/api/auth/reset-password" "/api/auth/verify-email/confirm" "/api/users/me/verify-email/resend"} or (starts_with(http.request.uri.path, "/api/invites/") and ends_with(http.request.uri.path, "/accept")) or (starts_with(http.request.uri.path, "/api/invite-links/") and ends_with(http.request.uri.path, "/accept"))))

CF-RL-03-AI
(http.host in {"connexcrm.jp" "preview.connexcrm.jp"} and http.request.method eq "POST" and (starts_with(http.request.uri.path, "/api/ai/") or (starts_with(http.request.uri.path, "/api/deals/") and (ends_with(http.request.uri.path, "/brief") or ends_with(http.request.uri.path, "/rationale"))) or http.request.uri.path eq "/api/introductions/suggestions/rationale" or (starts_with(http.request.uri.path, "/api/reports/") and ends_with(http.request.uri.path, "/generate"))))

CF-RL-04-UPLOADS
(http.host in {"connexcrm.jp" "preview.connexcrm.jp"} and http.request.method in {"POST" "PUT"} and (http.request.uri.path eq "/api/attachments/upload" or (starts_with(http.request.uri.path, "/api/ai/assistant/sessions/") and ends_with(http.request.uri.path, "/attachments")) or http.request.uri.path eq "/api/users/me/profile-picture" or (starts_with(http.request.uri.path, "/api/persons/") and ends_with(http.request.uri.path, "/profile-picture")) or (starts_with(http.request.uri.path, "/api/companies/") and ends_with(http.request.uri.path, "/logo")) or starts_with(http.request.uri.path, "/api/imports/") or starts_with(http.request.uri.path, "/api/business-cards/")))

CF-RL-05-API-VOLUME
(http.host in {"connexcrm.jp" "preview.connexcrm.jp"} and starts_with(http.request.uri.path, "/api/") and not (http.request.uri.path eq "/api/ws" or starts_with(http.request.uri.path, "/api/delivery/webhooks/") or starts_with(http.request.uri.path, "/api/delivery/unsubscribe/") or (http.request.method in {"POST" "PUT"} and (http.request.uri.path eq "/api/attachments/upload" or (starts_with(http.request.uri.path, "/api/ai/assistant/sessions/") and ends_with(http.request.uri.path, "/attachments")) or http.request.uri.path eq "/api/users/me/profile-picture" or (starts_with(http.request.uri.path, "/api/persons/") and ends_with(http.request.uri.path, "/profile-picture")) or (starts_with(http.request.uri.path, "/api/companies/") and ends_with(http.request.uri.path, "/logo")) or starts_with(http.request.uri.path, "/api/imports/") or starts_with(http.request.uri.path, "/api/business-cards/")))))

```

If legitimate shared-NAT traffic approaches a threshold, use evidence to raise the affected edge
ceiling. Do not key a rule on `JSESSIONID`, workspace cookies, invite tokens, unsubscribe tokens,
authorization headers, request bodies, or query parameters; doing so would disclose credentials or
personal data to edge configuration and event logs.

Webhook and unsubscribe token paths are deliberately excluded from edge rate rules. A provider may
legitimately burst from shared egress, and a Cloudflare block event can retain the live path token.
Managed DDoS protection, the 10 MiB request-read ceiling, signature/token verification, webhook
idempotency, and secret-redacted application controls are the compensating boundary. Replacing that
decision requires either moving the credential out of the URI or a reviewed origin admission
control; do not add a path-token rate event merely to make the rule count look comprehensive.

## Compatibility invariants

- **Tenant identification:** the `Host` header is preserved. Only the two reviewed SaaS hosts are
  in the zone rules, and Caddy does not rewrite tenant or workspace selectors.
- **Session/workspace cookies:** Cloudflare must not cache authenticated/API responses or strip,
  rewrite, log, or use cookies as rate keys. `Set-Cookie` passes end to end.
- **SAML POST binding:** `POST /api/login/saml2/sso/{registrationId}` retains its form body and
  cookies and is exempt from bot challenges. `/saml2/*` still routes to the backend.
- **Webhook delivery:** `/api/delivery/webhooks/{provider}/{token}` remains an unchallenged POST.
  Its application signature/token verification remains authoritative; the path token must not be
  exported in edge logs.
- **Unsubscribe:** GET/POST `/api/delivery/unsubscribe/{token}` remains unchallenged and idempotent;
  the token is redacted from exported paths.
- **File upload/download:** the 27 MiB multipart envelope preserves the 25 MiB stored-object limit,
  imports retain 64 MiB, and Business-plan upload capacity exceeds both. Downloads are not body
  capped and authenticated responses are not cached.
- **STOMP WebSocket:** `/api/ws` keeps the HTTP Upgrade and Connection headers, receives no bot or
  generic-rate challenge, and has no Caddy write deadline. The initial handshake may still be
  inspected by managed WAF rules.
- **AI:** only request-starting POSTs are coarsely rate limited. Generation polling remains under the
  generic high ceiling; application organization budgets and authorization stay authoritative.

## Privacy-safe observability

Use Cloudflare Security Events for daily review during rollout and at least daily on Business after
stabilization because its native event retention is only three days. Review sustained `CF-RL-*`
mitigations, a sharp managed-rule increase, origin-bypass attempts, and every production rule
disable. Restrict the native console to the Cloudflare Account Owner and Security Owner roles with
MFA; record access in the security control register.

Native Security Events are not a secret-free store: Cloudflare may display the full client IP and
request path, including a token-bearing path, even when Skip-rule logging is disabled. Treat those
fields as Cloudflare-held personal/confidential data under the subprocessor decision, never copy a
raw event into a ticket or long-term sink, and do not claim the native view meets the sanitized
export contract. Do not enable matched-payload logging.

Longer-term evidence must be created only through a reviewed field-allowlisting sanitizer. The
allowlist is UTC time bucket, hostname, HTTP method, action, Cloudflare service and stable rule ID,
count, and country/ASN only when operationally justified. It excludes event-level IP/`CF-Ray`, raw
URI paths and query strings, request or response bodies, `Cookie`, `Set-Cookie`, `Authorization`,
CSRF headers, SAML assertions, webhook signatures, invite/unsubscribe/download tokens, AI content,
uploaded filenames, and custom request headers. Business does not include Logpush; until an
approved contract and sanitizer are operating, retain only manually reviewed aggregate rule counts
and do not export native events. Any future exporter is a separate security/privacy-reviewed change
with credentials in the secret store and encrypted output in an approved Japan-region sink.

Cloudflare is a subprocessor and possible cross-border-processing decision, not merely a DNS
toggle. Before cutover, complete vendor/privacy review, update the signed DPA subprocessor annex and
customer notice as required, document applicable processing locations and APPI Art. 28 safeguards,
and reconcile the statements in [SECURITY.md](SECURITY.md). No edge payload-log feature may be
enabled as a shortcut around those approvals.

## Account-owner cutover and independent retest

The Cloudflare Account Owner performs these manual steps; repository completion does not complete
them.

1. Complete procurement, privacy/subprocessor, plan, account-RBAC, MFA, and break-glass review.
2. Add the zone without changing authoritative DNS yet. Configure Full (strict), WebSockets,
   managed rules, cache exclusions, the compatibility configuration rule, ordered exceptions, bot
   settings, and all five rate rules. Keep edge HSTS disabled.
3. Implement and prove one origin-lock design. From a non-Cloudflare network, the origin address
   must time out or reject TLS; from Cloudflare, the authenticated request must succeed.
4. Apply the policy to `preview.connexcrm.jp`. Validate its HTTPS certificate and every compatibility
   path, enable Always Use HTTPS, prove the HTTP redirect, then enable edge HSTS and set
   `CONNEX_CADDY_HSTS_ENABLED=true` on any shipped-Caddy origin. Export the resulting settings/rules
   and attach them to the change record. Run the compatibility and abuse tests below, then observe
   for 24 hours.
5. Independently review every block/challenge, tune only with evidence, and obtain Security Owner
   role approval. Proxy `connexcrm.jp`; repeat the HTTPS, redirect, HSTS, and compatibility tests in
   that order, then retain the sanitized evidence.
6. The independent Security reviewer, not the implementer, moves SEC-92 from `NG`/In Review only
   after reproducing the evidence in the public environment.

Post-cutover tests, using staging-only accounts/files and provider test fixtures, are:

- confirm `http://` returns only an edge redirect to the matching `https://` URL, and the HTTPS
  response contains exactly one `Strict-Transport-Security` header with the approved value;
- confirm normal login, password reset, registration policy, passkey assertion, invite acceptance,
  tenant/host selection, and session/workspace cookie persistence;
- cross `CF-RL-01` and one non-auth threshold and observe `429`/Block in the restricted native
  console, then prove recovery after each mitigation window;
- upload bodies immediately below each supported boundary, reject one immediately above it, and
  download the accepted object byte-for-byte; also complete a boundary-size import at the minimum
  documented transfer rate without crossing the five-minute body deadline;
- complete one SP-initiated SAML POST flow;
- complete a real STOMP-over-WebSocket handshake and exchange after an idle interval;
- deliver and replay a valid signed webhook fixture, reject an invalid signature, and complete an
  unsubscribe GET/POST without a challenge;
- start an authorized AI request and poll its handle without triggering the AI or generic ceiling;
- through each accepted ingress topology, prove two visitor addresses remain distinct in audit and
  login throttling and that forged `CF-Connecting-IP`/`X-Forwarded-For` values cannot win;
- verify the restricted native Security Events view supplies the rule/action evidence, then verify
  any separately approved sanitized artifact contains none of the forbidden fields above. If no
  sanitizer is approved, confirm no native event was exported.

## False-positive and emergency procedures

For a suspected false positive, put only UTC time bucket, hostname, method, stable rule ID,
token-free route class, action, and user-visible status in the ordinary ticket. A route class is the
static matcher label such as `SAML`, `WEBHOOK`, or `UPLOAD`, never a substituted URI. Do not paste
payloads, cookies, tokens, raw webhook URLs, raw paths, event-level IPs/`CF-Ray`, or screenshots
containing personal data into the ticket. If `CF-Ray` is essential to correlate one native event,
place it in a restricted incident note and delete that note within 24 hours after correlation.
Reproduce on staging and confirm the request is valid at the application boundary. Prefer raising a
coarse threshold. If a managed rule is responsible, add a staging exception for that exact host,
method, path class, and managed-rule ID with a 24-hour expiry; never skip an entire managed ruleset
to clear one event. A Security reviewer approves production promotion or the exception expires.

Emergency disable proceeds from smallest to largest blast radius:

1. Disable the single offending rate/custom rule or managed-rule ID on the affected host.
2. If bot classification is the cause, set the affected bot category to Allow while preserving WAF,
   DDoS protection, proxied DNS, TLS, origin lock-down, and the Caddy baseline.
3. Only the Incident Lead role may disable a whole managed ruleset. Record start time, reason,
   affected host, compensating monitoring, and a restoration deadline no later than four hours.
4. Do not gray-cloud DNS or open the origin firewall as a WAF rollback. That exposes the origin and
   removes DDoS protection. No alternate edge is currently approved: if Cloudflare itself is
   unavailable, the public service fails closed and remains unavailable. Activating a future
   alternate requires its own tested runbook, authenticated origin path, monitoring, rollback,
   restoration deadline, and Incident Lead plus Security Owner approval before the incident.

During any disable, monitor origin request rate, authentication failures, 4xx/5xx, latency, CPU,
memory, and connection count. Restore the smallest known-good exported configuration, rerun the
affected compatibility test, close the temporary exception, and attach sanitized evidence to the
incident/change record.

Every normal rule edit requires a ticket with threat/false-positive evidence, exact before/after
expression and action, path compatibility analysis, staging result, rollback action, expiry for any
exception, and independent Security review. Emergency changes receive the same review
retrospectively within one business day.

## Source references

- [Caddy global server options](https://caddyserver.com/docs/caddyfile/options)
- [Caddy request-body directive](https://caddyserver.com/docs/caddyfile/directives/request_body)
- [Caddy rate-limit module status](https://caddyserver.com/docs/modules/http.handlers.rate_limit)
- [Cloudflare IP ranges](https://www.cloudflare.com/ips/)
- [Cloudflare managed rules](https://developers.cloudflare.com/waf/managed-rules/)
- [Cloudflare rate-limiting rules](https://developers.cloudflare.com/waf/rate-limiting-rules/)
- [Cloudflare WAF exceptions](https://developers.cloudflare.com/waf/managed-rules/waf-exceptions/)
- [Cloudflare configuration-rule settings](https://developers.cloudflare.com/rules/configuration-rules/settings/)
- [Cloudflare Browser Integrity Check](https://developers.cloudflare.com/waf/tools/browser-integrity-check/)
- [Cloudflare Security Events](https://developers.cloudflare.com/waf/analytics/security-events/)
- [Cloudflare Logpush availability](https://developers.cloudflare.com/logs/logpush/)
- [Cloudflare WebSockets](https://developers.cloudflare.com/network/websockets/)
- [Cloudflare Tunnel troubleshooting](https://developers.cloudflare.com/cloudflare-one/troubleshooting/tunnel/)
- [Cloudflare origin protection](https://developers.cloudflare.com/fundamentals/security/protect-your-origin-server/)
