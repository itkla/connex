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

## Task routing

Read the relevant contract before editing that area:

| Work | Required reading |
|---|---|
| Copy, naming, flows, IA, states | `../docs/PRODUCT.md` |
| New page, redesign, cross-surface UI pattern | `../docs/frontend/PRODUCT_GRAMMAR.md` |
| Motion or animated interaction | `../docs/frontend/MOTION.md` |
| Frontend testing/browser verification | `../docs/FRONTEND_TESTING.md` |
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

Build-pipeline changes must preserve the production build-asset gate. Its detailed behavior belongs with the frontend CI/testing documentation rather than this always-loaded guide.
