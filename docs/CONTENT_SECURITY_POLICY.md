# Frontend Content Security Policy

The Next.js proxy owns the full frontend Content Security Policy because it creates the per-request nonce before App Router rendering. The backend continues to own the API policy. Next's static header configuration supplies the enforced `frame-ancestors 'none'` baseline, and Caddy's default-only header defers to it. If an upstream response lacks that baseline, Caddy supplies the same fallback.

## Modes

`CONNEX_CSP_MODE` controls the full policy at frontend runtime:

- The literal `report-only` — and nothing else — emits the full policy as `Content-Security-Policy-Report-Only`.
- **Unset, empty, misspelled, and every other value emit the full policy as the enforced `Content-Security-Policy` and remove the Report-Only header.**

Enforcement is the shipped default in every deployment profile and in CI. The fail-closed resolution is deliberate: a deployment cannot lose enforcement by forgetting a variable. It has one loud consequence — **a typo while rolling back keeps enforcing.** `CONNEX_CSP_MODE=reportonly`, `Report-Only`, and `report_only` all enforce. Copy the value exactly.

A nonce makes every covered App Router page dynamically rendered. Static optimization, ISR, CDN HTML caching, and Partial Prerendering are therefore unavailable for these pages; static Next.js assets remain cacheable.

`CONNEX_CSP_IMAGE_ORIGINS` is an optional comma-separated allowlist for user-authored remote note images. Each entry must be an exact HTTPS origin with no path, query, credentials, or wildcard, for example `https://images.example.com,https://media.example.net:8443`. Invalid entries are ignored. Same-origin images, `blob:` upload previews, and `data:` image assets are always allowed.

`connect-src` includes the exact `ws://` and `wss://` variants of the browser-facing host plus the exact origin of `NEXT_PUBLIC_WS_URL` when configured. Listing both host variants keeps same-origin realtime working when TLS terminates before the bundled HTTP Caddy instance; browser mixed-content enforcement still prevents insecure WebSocket use from an HTTPS page. Localhost also permits the development backend on port 8080.

The policy intentionally keeps `style-src 'unsafe-inline'`. Sonner 2.0.7 creates an un-nonced `<style>` element at module runtime, the shared chart component emits a runtime `<style>` element, and React components throughout Motion, Recharts, and XYFlow use dynamic `style` attributes for geometry and positioning. This weakens protection against injected CSS and visual spoofing, but does not relax the nonce-only script policy. Removing it requires replacing Sonner's injected stylesheet, adding nonce plumbing to the chart style, and moving or narrowly separating every remaining style attribute with `style-src-attr`.

### Observed header shape

Under enforcement an HTML response carries exactly **one** `Content-Security-Policy` header: the proxy's `set` replaces the static `frame-ancestors 'none'` baseline that `next.config.ts` `headers()` attaches, on every route the proxy matcher covers (observed on a `200` login page, the `307` from `/dashboard`, a `404`, and a `/document-acceptance/<token>` page). Under Report-Only the proxy removes only its own value, so the static baseline is what remains in `Content-Security-Policy` while the full policy moves to `Content-Security-Policy-Report-Only`. The static baseline is also the only policy on responses the proxy never sees (`/api`, `/saml2`, `/attachments`, `/_next/static`, `/_next/image`), and the full policy already contains `frame-ancestors 'none'`, so the effective policy is the same either way. Do not "fix" the mode-dependent shape by removing the static baseline.

## Violation reports

The policy ends with `report-uri /api/csp-reports`, and — when the browser-facing origin is HTTPS — also `report-to csp-endpoint` alongside a `Reporting-Endpoints: csp-endpoint="<origin>/api/csp-reports"` response header.

The `report-to` group and the `Reporting-Endpoints` header are emitted **only** when the browser-facing origin is `https:`. Chromium registers a reporting endpoint only when its scheme is cryptographic (`SchemeIsCryptographic()` in `net/reporting/reporting_header_parser.cc`) — `http://localhost` is discarded even though it is "potentially trustworthy" — and a Chromium that sees `report-to` stops honouring `report-uri` altogether. Emitting the pair on a non-HTTPS origin would therefore silently discard every report on localhost, in CI, and on a plain-HTTP LAN deployment; there the relative `report-uri` keeps working and Chromium posts the legacy `application/csp-report` body directly from the page. Both headers are emitted in Report-Only mode too; reports are useful either way.

### The collector

`POST /api/csp-reports` on the backend is unauthenticated, CSRF-exempt, exempt from tenant resolution and from privileged-MFA confinement, and always answers `204` for a body the size filters admitted.

| Request | Response |
|---|---|
| `application/csp-report` with a legacy report body | 204, one log line |
| `application/reports+json` with an array of N reports | 204, at most 10 log lines |
| garbage bytes, wrong shape, or an empty body | 204, no log line |
| body over 16 KiB (`CONNEX_CSP_REPORTS_MAX_BODY_BYTES`) | 413, at the edge and again in the application |
| a report carrying a session cookie and no CSRF header | 204 |
| a report from a privileged account confined pending MFA enrollment | 204 |
| more than 60 reports per 60 seconds from one client IP | 204, dropped silently |
| `GET`, `PUT`, `DELETE` | 401 — the collector is write-only |
| any other content type | 415 |

The per-IP window is `connex.csp-reports.max-reports-per-window` / `connex.csp-reports.window-seconds` and the client address is resolved through the trusted-proxy chain. It is used **only** as the throttle key: it is never logged.

### Reading the reports

Each accepted violation produces exactly one WARN line, rendered as a single ECS JSON event by the console encoder:

```
csp.violation disposition=enforce directive=img-src blockedHost=https://cdn.example.invalid documentPath=/dashboard sourceHost=unknown line=-1 statusCode=200 format=legacy forwardedByTrustedProxy=true
```

```bash
# systemd staging
ssh connex-target 'sudo journalctl -u connex-staging-backend --since -1h | grep csp.violation'
# bundled Compose deployment
cd deploy && docker compose logs backend | grep csp.violation
```

Field meanings: `disposition` is `enforce`, `report`, or `unknown` (only malformed reports omit it); `directive` is the effective directive, lowercased and bounded to `[a-z-]{1,40}`; `blockedHost` and `sourceHost` are an origin (`scheme://host[:port]`), a CSP keyword (`inline`, `eval`, `data`, `blob`, `self`, `wasm-eval`, `about`), `other`, or `unknown`; `documentPath` is the reporting document's path mapped onto the server-owned route vocabulary of `RequestPathRedactor` (`/records/contacts/{id}`, `/invite/{token}`, …) — a path the vocabulary does not know, including a token-bearing one such as `/document-acceptance/<token>`, logs as `unknown`, so a path-borne credential never reaches the log; `line` and `statusCode` are `-1` when absent; `format` is `legacy` or `reporting-api`.

Nothing is persisted and there is no retention obligation. The collector deliberately never reads `script-sample`, `referrer`, `original-policy`, request headers, or cookies: those carry page content and would turn the operator log into an untrusted-content sink. Every value is stripped of control characters and truncated to 256 characters before it reaches the log.

### Expected noise

Workspaces populated by the volume seeder carry `https://assets.seed.invalid/...` company logos, and any workspace can hold user-supplied cross-origin logo, avatar, or note-image URLs. Those are rendered as plain `<img>` elements and are blocked by `img-src` under enforcement — by design; the UI already degrades to its fallback or initials. They will generate `directive=img-src` reports. Allowlist the origin through `CONNEX_CSP_IMAGE_ORIGINS` if the images are genuinely wanted; otherwise treat the reports as confirmation that the control is working.

## Verify enforcement

1. `curl -sS -o /dev/null -D - https://<host>/auth/login | tr -d '\r' | grep -i '^content-security\|^reporting-endpoints'` — expect exactly one `content-security-policy` header carrying the full policy and ending in `report-uri /api/csp-reports; report-to csp-endpoint`, no `content-security-policy-report-only`, and `reporting-endpoints: csp-endpoint="https://<host>/api/csp-reports"`. On a plain-HTTP origin (`http://localhost` included) the policy ends at `report-uri /api/csp-reports` and there is no `reporting-endpoints` header — see [Violation reports](#violation-reports). Repeat for `/`, `/dashboard` (the 307 redirect carries it too), a 404, and a `/document-acceptance/<token>` page.
2. `curl -sS -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/csp-report' --data '{"csp-report":{"document-uri":"https://<host>/dashboard?x=1","effective-directive":"img-src","blocked-uri":"https://example.invalid/logo.png","disposition":"enforce","status-code":200}}' https://<host>/api/csp-reports` — expect `204` and one `csp.violation` line showing `documentPath=/dashboard` with the query stripped. A garbage body must also answer `204`, a `GET` must answer `401`, and a 17 KiB body must answer `413`.
3. Run the executable browser proof: `frontend/test/e2e/csp-enforcement.spec.ts`, which the required `Frontend — unit & e2e` CI job runs against a real backend with `CONNEX_CSP_MODE=enforce`. It asserts zero `securitypolicyviolation` events and zero `Refused to` console messages across the dashboard, analytics charts, the records list and detail, the documents library, the Ask Connex panel, an upload and its same-origin preview, the dark theme, a passkey enrollment followed by a passkey sign-in through a CDP virtual authenticator, and the logged-out login, register, and document-acceptance pages — and separately proves enforcement by injecting an inline event handler (`<img onerror>`, the markup-injection shape that `'strict-dynamic'` does not trust; a `document.createElement("script")` probe is deliberately allowed by that keyword and proves nothing), asserting it did not run, and awaiting the `204` from `/api/csp-reports`. Under `CONNEX_CSP_MODE=report-only` the header assertions and the probe's `disposition=enforce` assertion fail, which is how the spec guards the default.

## Roll back enforcement

Set `CONNEX_CSP_MODE=report-only` — exactly that string — in `deploy/.env` and run `cd deploy && docker compose up -d frontend`. For systemd staging, set the same variable in `/etc/connex-staging/frontend.env` and run `sudo systemctl restart connex-staging-frontend`; env-only changes do not trigger a deploy, so the restart is required. For a local run, set it in the frontend process environment and restart the process. Changing the variable does not require rebuilding the frontend image.

Verify the rollback: the full policy must move to `Content-Security-Policy-Report-Only`, `Content-Security-Policy` must now carry only the static `frame-ancestors 'none'` baseline, `Reporting-Endpoints` must still be present on an HTTPS origin, and a report must still arrive at `/api/csp-reports` (`disposition=report` in the log line). If the full policy is still in `Content-Security-Policy`, the value was misspelled — see [Modes](#modes).

## Staging cut-over

1. Merge and wait for the staging deploy, then **confirm the frontend release actually rebuilt** — a stale `.next` build cannot emit the new header ([#1351](https://github.com/itkla/connex/issues/1351)). `curl -sSI https://preview.connexcrm.jp/auth/login | grep -i report-uri` must show the directive before anything else is judged.
2. Set `CONNEX_CSP_MODE=enforce` explicitly in `/etc/connex-staging/frontend.env` (removing any `report-only`) and `sudo systemctl restart connex-staging-frontend`. The backend needs no env change.
3. Run the checks in [Verify enforcement](#verify-enforcement) against `https://preview.connexcrm.jp`.
4. Drill the rollback: switch to `report-only`, restart, confirm the header moved and reports still flow, then restore `enforce`.
