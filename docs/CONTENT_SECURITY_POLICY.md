# Frontend Content Security Policy

The Next.js proxy owns the full frontend Content Security Policy because it creates the per-request nonce before App Router rendering. The backend continues to own the API policy. Next's static header configuration supplies the enforced `frame-ancestors 'none'` baseline, and Caddy's default-only header defers to it. If an upstream response lacks that baseline, Caddy supplies the same fallback. The two layers therefore never emit competing enforced policies.

## Modes

`CONNEX_CSP_MODE` controls the full policy at frontend runtime:

- Unset, `report-only`, or any unrecognized value emits the full policy as `Content-Security-Policy-Report-Only` alongside the enforced `frame-ancestors 'none'` baseline.
- `enforce` emits the full policy as `Content-Security-Policy` and removes the Report-Only header.

The deployment profiles default to Report-Only because Connex does not yet collect CSP reports centrally. The Proxy intentionally does not emit the enforced frame-only header itself in Report-Only mode: Next copies Proxy response headers back onto the render request, which would overwrite the nonce-bearing request policy and leave framework scripts without nonces. The response-only static header configuration retains the baseline without changing the render request.

A nonce makes every covered App Router page dynamically rendered. Static optimization, ISR, CDN HTML caching, and Partial Prerendering are therefore unavailable for these pages; static Next.js assets remain cacheable.

`CONNEX_CSP_IMAGE_ORIGINS` is an optional comma-separated allowlist for user-authored remote note images. Each entry must be an exact HTTPS origin with no path, query, credentials, or wildcard, for example `https://images.example.com,https://media.example.net:8443`. Invalid entries are ignored. Same-origin images, `blob:` upload previews, and `data:` image assets are always allowed.

`connect-src` includes the exact `ws://` and `wss://` variants of the browser-facing host plus the exact origin of `NEXT_PUBLIC_WS_URL` when configured. Listing both host variants keeps same-origin realtime working when TLS terminates before the bundled HTTP Caddy instance; browser mixed-content enforcement still prevents insecure WebSocket use from an HTTPS page. Localhost also permits the development backend on port 8080.

The policy intentionally keeps `style-src 'unsafe-inline'`. Sonner 2.0.7 creates an un-nonced `<style>` element at module runtime, the shared chart component emits a runtime `<style>` element, and React components throughout Motion, Recharts, and XYFlow use dynamic `style` attributes for geometry and positioning. This weakens protection against injected CSS and visual spoofing, but does not relax the nonce-only script policy. Removing it requires replacing Sonner's injected stylesheet, adding nonce plumbing to the chart style, and moving or narrowly separating every remaining style attribute with `style-src-attr`.

## Observe in staging

1. Set `CONNEX_CSP_MODE=report-only` in `deploy/.env` for Compose, or in the staging frontend service environment, then recreate or restart only the frontend runtime.
2. In browser developer tools, open the Network panel and confirm an HTML response contains both `Content-Security-Policy: frame-ancestors 'none'` and the full `Content-Security-Policy-Report-Only` policy. Confirm the response's nonce changes after a full reload.
3. Clear the Console and exercise login, dashboard, a record browser and detail page, AI streaming, upload and attachment preview, analytics charts, the workflow or relationship graph, theme switching, printing, SSO, and WebAuthn. Record every `Content Security Policy` violation with its page, directive, blocked URL, and reproduction steps.
4. Add only exact origins required by intended behavior. Repeat the full journey in both light and dark themes until no unexplained violations remain. Arbitrary remote note-image origins stay blocked unless explicitly allowlisted.
5. Set `CONNEX_CSP_MODE=enforce`, restart the frontend, repeat the journey, and confirm HTML responses now contain the full `Content-Security-Policy` header and no Report-Only header.

For the bundled deployment, apply a mode or image-origin change with:

```bash
cd deploy
docker compose up -d frontend
```

Changing either variable does not require rebuilding the frontend image.

## Roll back enforcement

Set `CONNEX_CSP_MODE=report-only` in `deploy/.env` and recreate the frontend service with the command above. For systemd staging, set the same variable in `/etc/connex-staging/frontend.env` and run `sudo systemctl restart connex-staging-frontend`. For a local run, set it in the frontend process environment and restart the process. Verify that the full policy moved back to `Content-Security-Policy-Report-Only`; the enforced `frame-ancestors 'none'` baseline must remain present.
