<!-- BEGIN:nextjs-agent-rules -->
# This is NOT the Next.js you know

Next.js in this repository may differ from training data. Read the relevant guide in `node_modules/next/dist/docs/` before using an API or convention that may have changed. Heed deprecations.
<!-- END:nextjs-agent-rules -->

# Frontend — Agent Guide

The root `../AGENTS.md` applies here. This file contains only frontend-wide rules; detailed design contracts are loaded on demand through **Task routing**.

Before writing user-facing copy, naming anything, or designing a flow, read `../docs/PRODUCT.md`. Its vocabulary, voice, interaction semantics, and target IA are authoritative.

## Stack and structure

Next.js 16 App Router/RSC · React 19 · strict TypeScript · Tailwind v4 · shadcn/ui on Base UI + Radix (`radix-vega`) · `motion` · `recharts`/`d3`/`@xyflow/react` · `next-intl` · `next-themes` · `sonner` · Heroicons.

- `@/*` points to the frontend project root.
- Shared UI: `components/ui`.
- Shared API client: `app/lib/api.ts`; shared DTO types: `app/lib/types.ts`.
- Server Components by default. Add `"use client"` only for client behavior.
- Keep presentational components free of data fetching and business logic.
- `landing/` vendors the landing, legal, and not-found surface byte-for-byte. After changing those files or `messages/*/{common,legal,errors}.json`, run `landing/scripts/sync-from-frontend.sh` and commit the result; the `Landing` workflow fails on drift.

## Task routing

Read the relevant contract before editing that area:

| Work | Required reading |
|---|---|
| Copy, naming, flows, IA, states | `../docs/PRODUCT.md` |
| New page, redesign, cross-surface UI pattern | `../docs/frontend/PRODUCT_GRAMMAR.md` |
| Motion or animated interaction | `../docs/frontend/MOTION.md` |
| Frontend testing/browser verification | `../docs/FRONTEND_TESTING.md` |
| Production build/image/release asset gate | `../docs/frontend/BUILD_ASSET_GATE.md` |
| Deployment/staging/build pipeline | `../docs/DEPLOYMENT.md`, `../docs/STAGING_DEPLOY.md` |

For subsystem-specific behavior, inspect the nearest shipped implementation and its tests before searching for a new abstraction.

## Design review

Use one focused design pass, not a pile of overlapping rituals.

- A new page, redesign, or new cross-surface visual/interaction system gets one structured **pre-implementation** design audit covering information architecture, hierarchy, state model, responsive behavior, and reuse of the design system.
- A routine in-place edit or component that clearly extends an established pattern follows the nearest live reference surface; it does not need a broad redesign exercise.
- Motion changes additionally receive one focused animation review against `../docs/frontend/MOTION.md`.

The active agent environment may provide named design/review skills; use the smallest one that satisfies the relevant audit rather than encoding transient tool names or running several equivalent passes.

## Design system

- **Reuse `components/ui` first.** Do not hand-roll a primitive that already exists. Extend shared primitives through their existing variants and `cn`.
- **Use design tokens.** Colors, spacing, and motion come from `app/globals.css` and shared helpers. Do not introduce arbitrary hex values or ad-hoc motion timings. Relationship and chart colors use the existing `--warmth-*` and `--chart-*` families.
- **Use shared page grammar.** Normal routed app pages use `PageShell`; normal page titles use `PageHeader`; collection states use the shared loading, `EmptyState`, `ErrorState`/`SectionBoundary`, and `AccessDenied` patterns. Exceptions and surface-specific decisions live in `../docs/frontend/PRODUCT_GRAMMAR.md`.
- **Match live reference surfaces.** Dashboard, analytics, records, and library pages are the practical visual baseline. Prefer an existing pattern over a novel one.
- **Buttons and controls come from shared primitives.** Use the established context sizes, `IconButton` for icon-only actions, `SegmentedControl` for mode/view switches, and existing dialog/sheet primitives.
- **Theming is mandatory.** Verify light and dark modes for materially changed visual surfaces.

## Interaction and accessibility

- Every user action acknowledges input promptly; network latency must not make the UI appear dead.
- Loading, empty, error, and permission states are distinct. Never render a permission failure as an empty result.
- Choose interaction weight proportionately: simple reversible fields may edit inline; short focused operations use dialogs/sheets; complex authoring gets a page. Follow `../docs/PRODUCT.md` and the product-grammar contract for exact mappings.
- Every interactive control is keyboard-operable with visible focus. Prefer semantic HTML and Base UI/Radix behavior over custom ARIA implementations.
- Do not use color alone to communicate state.
- Respect `prefers-reduced-motion`. Motion changes must follow `../docs/frontend/MOTION.md` and be verified with reduced motion enabled.
- Mobile preserves capability semantics but may use task-adaptive presentation. Do not solve mobile by horizontally scrolling desktop tables or stacking desktop cards blindly.

## API, auth, and data

- The backend is reached through `/api/*`; `next.config.ts` rewrites to the backend during local development.
- Use `app/lib/api.ts` instead of scattering raw `fetch` calls. Keep frontend types aligned with backend DTOs.
- Auth/session is cookie-based and workspace-aware. Route protection lives in `proxy.ts`; update it when a new protected route requires it.
- Uploads go to authenticated backend endpoints. Never store user uploads in Next.js `public/` or add a frontend filesystem upload path.
- Keep established fail-closed contracts intact. Features such as duplicate preflight, import review proofs, AI generation polling, auth/workspace transitions, and OCR submission have security/data-integrity semantics; read their existing client implementation and backend contract before changing them.
- Surface request outcomes through the shared toast/error patterns. Do not swallow failures or leave dead UI.
- Any `NEXT_PUBLIC_*` variable is browser-visible. Secrets remain server-only.

## Internationalization

Connex is bilingual English/Japanese.

- Every user-facing string goes through `next-intl` and is added to both `messages/en` and `messages/ja`.
- Do not hardcode product copy in components.
- Use the canonical vocabulary in `../docs/PRODUCT.md`.
- If editing the product vocabulary, regenerate its generated lint model with `node scripts/generate-vocabulary.mjs` and run the relevant tests. Do not raise a lint baseline merely to accommodate new debt.
- Layouts must tolerate Japanese text widths and use the configured Japanese font path.

## Code conventions

- Strict TypeScript stays enabled. No `any` or unchecked casts; derive types from data and validate boundaries.
- Prefer self-explanatory code and TSDoc/JSDoc for public contracts. Inline comments are reserved for non-obvious safety, protocol, compatibility, or lifecycle reasoning.
- Heroicons are the default icon family; use Lucide only when Heroicons lacks a suitable icon. Do not mix equivalent icons across families.
- Memoize expensive derived chart/graph data and virtualize long collections where appropriate. Avoid rerendering heavy visualizations on unrelated state changes.
- Do not reformat unrelated files or introduce a second implementation of an existing composer/control/pattern.

## Verification

Minimum frontend loop for a material change:

1. `pnpm lint`
2. `pnpm exec tsc --noEmit`
3. `pnpm test`
4. Run `pnpm e2e` when the changed flow is covered by the Playwright suite.
5. Run `pnpm dev` and exercise every materially changed path in a real browser with no console errors. Use an isolated Playwright MCP profile when multiple agents share the host; authenticate inside that profile rather than relying on a persisted browser session.
6. For visual changes, check representative desktop/mobile widths and light/dark. For motion changes, also verify reduced motion and complete the focused animation review.
7. Self-review the exact diff. Material/high-risk changes receive the root-required independent review; auth, sharing, permissions, cross-workspace state, or other security-sensitive UI also gets security-focused review.

The Content Security Policy is **enforced** by default (`CONNEX_CSP_MODE=report-only` is the only rollback value). Any new third-party origin, inline `<script>`, `eval`, frame, worker, or non-`'self'` `connect-src`/`img-src`/`font-src` target must be reflected in `security-headers.ts` and pass `test/e2e/csp-enforcement.spec.ts`; a change the policy blocks will fail that spec in CI rather than degrading silently in production. See `../docs/CONTENT_SECURITY_POLICY.md`.

If browser tooling is unavailable, report that explicitly rather than pretending browser verification happened.

## Commands

This repository uses **pnpm**, not npm.

- Install: `pnpm install`
- Add dependency: `pnpm add <pkg>` then `pnpm audit`
- Dev: `pnpm dev`
- Build: `pnpm build`
- Start: `pnpm start`
- Lint: `pnpm lint`
- Typecheck: `pnpm exec tsc --noEmit`
- Unit tests: `pnpm test`
- E2E: `pnpm e2e`
- Verify production build assets: `node ci/verify_build_chunks.mjs .next`
- Regenerate vocabulary model: `node scripts/generate-vocabulary.mjs`

### Package manager supply-chain policy

`pnpm-workspace.yaml` here, in `emails/`, and in `../landing/` enforces the #835 policy:

- `minimumReleaseAge: 1440` with `minimumReleaseAgeStrict: true`: within a range or dist-tag pnpm silently picks the newest version at least one day old, so `pnpm update next` or `next@latest` can quietly install the pre-patch release. Only when no satisfying version is old enough does pnpm stop: CI and non-interactive shells fail with `ERR_PNPM_NO_MATURE_MATCHING_VERSION`, listing every young `name@version`; an interactive terminal prompts instead (see below).
- `minimumReleaseAgeIgnoreMissingTime: false`: packages whose registry metadata has no publish time are refused.
- `trustPolicy: no-downgrade`: a version with weaker trust evidence (staged publish > trusted publisher > provenance attestation; registry signatures are not considered) than any earlier-published version is refused.

pnpm 11 re-checks every committed lockfile entry against these policies on `--frozen-lockfile` installs, so CI and the Docker build fail if a lockfile bypassed them. `../.github/scripts/check-pnpm-supply-chain-policy.py`, run by the Security "Frontend dependency audit" job, fails when a setting is missing or changed, when `trustLockfile` or `trustPolicyIgnoreAfter` appears, or when an exclusion breaks the rules below. Never drop or relax these settings to get an install through.

- **Emergency exception (release age):** only for an urgent framework security patch, and only once the owner has approved the exact version. (1) Request the patched release by exact version (`pnpm add next@16.3.5`), never by range or tag, so pnpm fails (or, on a terminal, prompts) and lists every young package the fix pulls in, including platform-specific optional binaries. (2) Add each listed version to `minimumReleaseAgeExclude` beside the setting as its own `name@x.y.z` entry (no bare name, wildcard, or `||` union), with a comment directly above it naming the advisory (GHSA/CVE), `Approved: <owner>, <YYYY-MM-DD>`, and a `Remove …` condition. (3) Re-run the install and confirm `pnpm-lock.yaml` resolves the patched version. (4) Remove the entries in a follow-up PR once the version is older than one day.
- **pnpm writes exclusions itself.** Decline its interactive "Add to minimumReleaseAgeExclude in pnpm-workspace.yaml and proceed with the install?" prompt unless the owner has approved that exact version. `pnpm audit --fix` appends an entry for every patched version it overrides without asking. Both write uncommented entries: before committing, delete them or obtain approval and annotate them as above. CI rejects them as written.
- **`trustPolicyExclude`** holds only verified legacy-line backports published without trust evidence: one exact `name@x.y.z` per entry, with a comment directly above giving the evidence (publisher, release line, date) and a `Remove …` condition.
- **Dependabot:** npm version updates for `/frontend` and `/landing` carry a one-day `cooldown` to match. Security updates ignore cooldown, so a same-day security PR fails its frozen install until the release ages or the owner approves an exclusion as above.

Build-pipeline changes must preserve every invocation and artifact boundary documented in `../docs/frontend/BUILD_ASSET_GATE.md`.
