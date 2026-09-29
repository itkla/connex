# Reproducing public edge evidence

This procedure supports CHK-083 / SEC-92 (#1242) and CHK-042 / SEC-47. It does not authorize
configuration changes or abuse traffic. The policy remains [EDGE_DEFENCE.md](EDGE_DEFENCE.md).
Run from the repository root with Bash, curl and Python 3 (standard library only).

## Public, unauthenticated observations

```bash
bash deploy/edge/verify-public-headers.sh
```

Exit 0 means the representative matrix passed; 1 means a measured policy/response-class failure;
2 means transport or parsing failed. Fixed-order output omits cookies, Ray IDs, bodies and dynamic
asset names. GET requests verify TLS using the system trust store, do not follow redirects, discover
one actual same-origin CSS asset from login, and assert exactly one HSTS header with
`max-age=31536000; includeSubDomains` and no `preload` or unexpected directive. Duplicate HSTS
headers fail. The HTTP login check separately requires the documented same-host HTTPS 308.

Coverage is app/root, bare `/api`, `/api/version`, a real static asset, HTTPS trailing-slash redirect,
app/static 404s and API denial. These are response-class samples, not a proof of every possible
response. No safe external probe can force every Cloudflare challenge, outage/5xx, authenticated
route, cache hit, protocol or point of presence. Operators must add those results during the
approved compatibility test window; do not manufacture an outage to fill a table.

Cloudflare server/Ray/cache header presence establishes only an observable proxy response.
It does not establish managed/OWASP rules, bot controls, threshold enforcement, exceptions, origin
lock-down, tenant isolation or security-event handling. This script makes no login attempts, sends
no malicious payload and does not cross a rate threshold. An application 429 is never WAF evidence.

## HSTS ownership and preload decision

Spring's application, public API and CSP-report security chains specify one year and
`includeSubDomains`, with no preload. `SecurityResponseHeaders.apply` also supplies that value for
secure requests rejected before those chains. These cover backend responses and depend on the
backend's secure-request perception. They cannot cover frontend assets or Cloudflare-generated
responses. See `backend/.../config/SecurityConfig.java`, `PublicApiSecurityConfig.java`,
`CspReportSecurityConfig.java` and `SecurityResponseHeaders.java`.

The distribution's `deploy/Caddyfile` defers replacement of upstream HSTS on both normal and error
routes when `CONNEX_CADDY_HSTS_ENABLED=true`; its default is false for private HTTP installations.
When disabled it does not remove backend HSTS, so API and frontend responses can disagree.
The public TLS edge is the final authority: Cloudflare must enforce the approved policy for public
responses, including those it generates itself. Origin settings cannot demonstrate that behavior.
The earlier independent review in #1242 reports preview uses systemd/Tunnel and does not use Caddy;
this lane did not inspect host configuration. The measured API/frontend split is consistent with
that report, but does not prove the exact Cloudflare setting.

**Decision: deliberately do not submit Connex hostnames to the preload list as part of this
rollout; keep the preload directive disabled.** Subdomain HTTPS inventory and all-host redirect
gates are incomplete, and browser preload creates a long-lived commitment with slow removal.
This is the deployment policy, not a claim that historical list membership was checked or that
someone has withdrawn an earlier submission. Account Owner must record actual submission/list
status in the control register before any future preload decision. Ordinary dynamic HSTS remains
required; omitting preload does not excuse a missing HSTS header.

The live 2026-09-08 matrix in [the measured evidence](evidence/edge-preview-2026-09-08.txt) found
HSTS absent on app, static, HTTPS redirect and 404 responses, while API responses passed. HTTP login
returned 200 instead of the required 308. **Proposed CHK-042 verdict: NG (previously 未確認).**
No deploy change was made: editing unused Caddy or globally opting private HTTP customers into
HSTS would not safely fix this preview. The Account Owner must validate HTTPS for the intended
hosts/subdomains, deploy the hostname-scoped 308 redirect, enable the runbook's Cloudflare HSTS
policy after its gates, and rerun the matrix. Keep the backend header as defense in depth.

## Private zone/ruleset export

Use a zone-scoped read-only token from the approved secret store with Zone Read, Zone Settings Read,
Zone WAF Read and the applicable Bot Management/configuration read permissions. Product permission
names and entitlements must be checked in the token UI; missing permission or plan support is a
blocked export, never evidence that a feature is off. Do not request a write token to make a read
succeed. Export account-level policy separately if it applies; this tool only reads the named zone.

```bash
# Populate these from the secret store, without shell history or command-line literal secrets.
# CLOUDFLARE_API_TOKEN and CLOUDFLARE_ZONE_ID must already be exported.
# Set TMPDIR to the approved encrypted operator workspace outside every Git checkout.
bash deploy/edge/export-zone.sh "$TMPDIR/zone-export-before"
```

Only a successful run creates `COMPLETE.txt`. API errors and pagination fail the run. Files are
mode-restricted, pretty-printed sorted-key JSON in `.private.txt` files; rule array order is preserved
because it affects evaluation. Zone details, settings, bot configuration, ruleset inventory and each
full listed ruleset are captured. Object keys and filenames are deterministic; vendor timestamps,
versions and ordered arrays remain visible so reviewers can detect changes. Diff private exports
with `diff -ru BEFORE AFTER` only in the restricted workspace. Do not paste that raw diff into GitHub.

**These are raw private configuration, not sanitized events.** Expressions, descriptions, zone/account
IDs, nameservers, addresses and referenced lists can contain confidential data. Account Owner must
produce a human-readable reviewed configuration attachment in the restricted control register:
remove account/zone identifiers, email, IPs and secret literals; replace sensitive expression operands
with typed placeholders and explain their scope privately to the independent reviewer. Do not redact
host/method/path matcher structure, precedence, enabled state, rule/action overrides, rate counters,
periods, mitigation timeout or logging decisions needed to judge enforcement. Keep the raw export
in the approved encrypted Japan-region sink with access/retention recorded; never commit either raw
configuration or native events. The event sanitizer is deliberately NOT a configuration sanitizer.

Reviewer checklist for the configuration attachment:

- Zone plan/entitlements, proxied intended DNS names (dashboard evidence; no origin IP in attachment),
  Full (strict), WebSockets, upload ceiling, HSTS and redirects.
- Active phase entry points, Cloudflare Managed and OWASP execute targets, versions/defaults and all
  overrides. Follow every execute reference absent from the inventory using the documented ruleset
  GET operation; capture pinned versions where present. Resolve referenced account policies/lists
  in the restricted console; zone-only export cannot rule out account overrides.
- Ordered custom methods rule, four Skip exceptions and their logging flags, configuration rule,
  bot categories, cache exclusions and all five rate rules, compared field-by-field to EDGE_DEFENCE.
- Origin Tunnel/firewall evidence from the infrastructure owner; no public origin probing in this lane.

## Sanitized block/rate observations

Security/Privacy Owner must approve this sanitizer and the encrypted sink/access/retention contract
before it operates on native events. Local tests are implementation evidence, not that approval.
Default until approval remains manually reviewed aggregate counts only. Do not enable payload logging.

1. In the private export, map actual 32-character rule IDs to the runbook's stable `CF-RL-01`–`05`,
   `CF-CUSTOM-01`, `CF-EX-01`–`04`, `CF-MANAGED` or `CF-OWASP` labels (only the exact labels declared in `LABELS`
   are accepted; free-form suffixes are rejected). Store a JSON object in `$TMPDIR/rule-map.private.txt`. Review the mapping against
   rule actions, not just descriptions. Many managed IDs may map to one family; retain exact rule
   correlation in the private configuration, not event evidence.
2. Account Owner schedules the runbook's staging compatibility and threshold checks using dedicated
   synthetic accounts/files and a bounded agreed traffic budget. Record UTC start/end, control/rule,
   request count and HTTP outcome without paths, users, IPs or headers. Include a managed/custom
   block and `CF-RL-01` plus one non-auth rate rule, then recovery after each mitigation window.
   Correlate each to its actual rule/action/source in the restricted Security Events console.
   Do not infer Cloudflare mitigation from status alone. This lane did not run those tests.
3. With a zone analytics read token, capture each short interval (at most one hour):

   ```bash
   bash deploy/edge/capture-events.sh 2026-09-08T01:00:00Z 2026-09-08T01:10:00Z \
     "$TMPDIR/rule-map.private.txt" > "$TMPDIR/events.sanitized.txt"
   ```

   Query selects only datetime, host, method, action, source and rule ID. No IP/path/body/header is
   requested. Raw response is temporary; failures emit a generic error. Errors, missing zones and
   a full 10,000-row page fail closed: shorten the interval rather than claim completeness.
   Adaptive data can be sampled even below the cap. Counts mean observed rows, not total traffic;
   absence of events does not prove absence of mitigation. API schema/entitlement remains untested
   against the account. If unsupported, use a restricted native JSON event array as sanitizer stdin:

   ```bash
   python3 deploy/edge/sanitize_events.py "$TMPDIR/rule-map.private.txt" \
     < "$TMPDIR/native-events.private.txt" > "$TMPDIR/events.sanitized.txt"
   ```

4. Sanitizer emits schema 1 sorted hourly aggregates only. Unknown fields are dropped recursively by
   non-selection; unrecognized values/types in allowed fields become `REDACTED`. Unknown rule IDs
   become `REDACTED`; malformed envelopes/mapping/JSON produce no stdout and exit 2. No raw string,
   identifier, country/ASN or hash of an IP/secret is retained. Review any `REDACTED` bucket before
   using it as rule attribution. The 32 MiB input bound prevents oversized retention/processing.
5. Independently inspect the sanitized output and correlate it with the private ruleset and synthetic
   observation record. Retain sanitizer Git SHA, test output, interval and capture exit status in the
   restricted register. Obtain Security Owner review of daily triage, an escalation/recovery example,
   retention/deletion and access control. Delete temporary native exports after review per the
   approved retention contract (shell unlink is not a secure-erasure guarantee).

## Concrete outstanding operator gates

CHK-083 remains **NG / In Review**. AC1 and AC5 documentation still pass. This change implements and
tests the missing sanitization mechanism, but AC2–4 and account-dependent AC6 cannot pass without:

1. Account Owner supplying the reviewed export and resolving every checklist item above.
2. Security/Privacy Owner approving the sanitizer, processing contract and operational sink; Account
   Owner running capture and providing correlated block/rate-limit/recovery observations.
3. Operator completing the runbook compatibility matrix: tenant/host selection, auth/workspace cookies,
   login/passkeys/invites, signed/replayed/invalid webhooks, unsubscribe/acceptance, boundary uploads,
   downloads/imports, SAML, WebSocket idle exchange, AI polling and forwarded-IP handling. Record each
   result and sanitized artifact reference; unauthenticated probes cannot substitute for these flows.
4. Named role holders countersigning these still-pending records in the signed control register:

   | Record | Required accountable owner / approver | Gate |
   |---|---|---|
   | `EDGE-RISK-SILO-2026-01` | Connex Security Owner / Risk and Exception Approver | Before public exposure |
   | `EDGE-RISK-ONPREM-PUBLIC-2026-01` | Customer Security Owner / Customer Risk Acceptance Authority | Before public exposure |
   | `EDGE-RISK-ONPREM-PRIVATE-2026-01` | Customer Security Owner / Customer Risk Acceptance Authority | Before deployment |

   Each needs named signers, acceptance date, scope, compensating controls and approval reference;
   proposed re-review 2026-10-13 and maximum expiry 2026-11-13. No signature was obtained or invented.
   A record for a deployment that does not apply must be explicitly scoped out by its owner, not
   silently marked accepted. Applicable managed-WAF evidence may instead close its exception.
5. Independent Security reviewer reproducing public results and reviewing the restricted artifacts
   before changing SEC-92 to OK. Remediate the separately measured HTTPS/HSTS findings first.

## Verification and API references

```bash
python3 -m unittest discover -s deploy/edge/tests -v
bash -n deploy/edge/export-zone.sh deploy/edge/capture-events.sh deploy/edge/verify-public-headers.sh
python3 .github/scripts/test_edge_security_headers.py
```

No backend/frontend code changes or new dependencies; Gradle is intentionally not run.
API contracts checked against official documentation on 2026-09-08:
[Ruleset export](https://developers.cloudflare.com/ruleset-engine/rulesets-api/view/),
[Bot Management](https://developers.cloudflare.com/api/resources/bot_management/),
[GraphQL events](https://developers.cloudflare.com/analytics/graphql-api/tutorials/querying-firewall-events/),
[GraphQL variables](https://developers.cloudflare.com/analytics/graphql-api/getting-started/querying-basics/),
[sampling](https://developers.cloudflare.com/analytics/graphql-api/sampling/).
