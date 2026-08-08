<!-- BEGIN:nextjs-agent-rules -->
# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` before writing any code. Heed deprecation notices.
<!-- END:nextjs-agent-rules -->

# Frontend — Agent Guide

The root [`/AGENTS.md`](../AGENTS.md) applies here in full. This file adds frontend-specific rules. The Golden Rules — Explore→Plan→Question→Act, match existing patterns, docs-only comments, strict types, scoped skills, and independent risk-tiered review — are not optional here.

## Stack

Next.js 16 (App Router, RSC) · React 19 · TypeScript strict · Tailwind v4 · shadcn/ui on Base UI + Radix (`radix-vega` style) · `motion` · `recharts` / `d3` / `@xyflow/react` · `next-intl` · `next-themes` · `sonner` · `@heroicons/react` (Lucide fallback).

Path alias: `@/*` → project root. Utils at `@/lib/utils` (`cn`), UI at `@/components/ui`, hooks at `@/hooks`.

## Skills are mandatory when their scope matches

Always run the smallest design-skill pipeline that covers the actual change, and run it **before** building. Do not spend three overlapping design passes on a routine component that extends an established pattern.

### Full pipeline — new pages, redesigns, or a new interaction/visual system

Run all three **in this order** — broad to specific, each pass building on the last:

1. **`impeccable`** — first. Audit-first and broadest: UX, information architecture, visual hierarchy, the design system. Sets structural direction before any pixels.
2. **`design-taste-frontend`** — next. With structure settled, lock the visual direction and anti-generic design system, then build the interface.
3. **`emil-design-eng`** (Emil Kowalski) — last. The polish pass: component feel, motion, micro-interactions, the invisible details. Pair with **`review-animations`** whenever you add or change motion.

Don't reorder: polish (step 3) belongs on top of a settled structure (step 1), never before it.

### Focused pipeline — routine components and in-place edits

For components that clearly extend an existing page/component pattern, or minor changes to existing UI (spacing, copy, a prop, a small style fix), run **`emil-design-eng`** only and ground the change in the live reference page. Skip `impeccable` and `design-taste-frontend` unless the work changes hierarchy, layout, information architecture, or establishes a reusable visual/interaction pattern. If it does, stop and escalate to the full pipeline.

## Design system — honor every source of truth

1. **Reuse `components/ui` first.** The shadcn/Base UI primitives there already cover most needs (button, dialog, input, select, combobox, chart, sheet, …). Never hand-roll a primitive that exists. Extend via variants (`class-variance-authority`) and `cn`.
2. **Tokens only — `app/globals.css`.** Use the CSS variables / `@theme` tokens. No arbitrary hex or px. This includes domain tokens:
   - `--warmth-hot` / `--warmth-warm` / `--warmth-cool` / `--warmth-cold` for relationship temperature.
   - `--chart-*` (`chart-1..5`, `chart-won/lost/open`, `chart-grid/axis/stroke`) for data viz — use these with recharts/d3, not raw colors.
   - `--sidebar-*`, semantic `--background/--foreground/--muted/--accent/--destructive`, etc.
3. **Motion presets — `app/lib/motion.ts`.** Use the shared named springs/easings (`springJiggle` — the house bouncy "jiggle", `springSnappy`, `springSmooth`, `easeOut`, `instant`) instead of ad-hoc `{ type: 'spring', … }` literals, so motion feels consistent. Always pair with a `useReducedMotion()` fallback (`instant`).
4. **`emil-design-eng`** for the feel and the invisible details.
5. **Live reference pages** — match these in look and behavior for any new UI:
   - `app/(app)/overview/analytics`
   - `app/(app)/dashboard`
   - `app/(app)/records/*` (contacts, companies, deals, pipelines)
   - `app/(app)/library/*` (files, tags)

When in doubt, open a reference page and mirror it.

## Product grammar

The signed-off 1.0 interaction contract (issue #842). A working checklist, not prose. The shared components named here are the enforcement surface — reuse them; do not hand-roll a wrapper, header, or state that one of them already owns.

**Page shell & width tiers.** Every routed surface that renders inside the app shell `<main>` wraps its content in `PageShell` (`app/components/PageShell.tsx`) — never a hand-rolled `min-h-full … mx-auto max-w-* flex flex-col gap-*` div. Pick the `tier` by job, not by taste:
- `wide` (`max-w-[100rem]`) — list/browser, dashboard, overview, settings, admin, and Contact/Company/Deal record detail (left-rail workbench).
- `reading` (`max-w-5xl`) — long-form reading pages that are not record detail (notes, campaign detail, account surfaces).
- `form` (`max-w-3xl`) — focused single-column forms and narrow detail.
- Full-bleed surfaces (the relationship map), marketing/docs shells, and **full-height editor shells** (the workflow editor, the document template builder, and their `loading.tsx`) do not use `PageShell` — they own their own height and vertical rhythm, and the shell's `gap-10` would fight it.
Section rhythm (`gap-10` between stacked children) and page gutter/padding come from the shell — do not re-declare them, and do not override the gap per page: uniform rhythm is the point. A `loading.tsx` skeleton uses the same `PageShell` tier as the page it stands in for — a skeleton and its page must never disagree about the wrapper, or the page visibly jumps when data arrives.

**Page header.** The page title is a `PageHeader` (`app/components/PageHeader.tsx`), never a bare `<h1>`. Canon: `text-4xl font-extrabold tracking-tight`; the `compact` variant only for a genuinely secondary page. Optional `description` sits under the title; `actions` is the right-aligned cluster (the caller composes primary + secondary). Domain identity headers (record detail with a dynamic name/avatar, the Me and report-document heroes) are §17 domain expression and keep their bespoke header — they still adopt `PageShell`.

**Record detail (#843).** Contact, Company, and Deal detail pages share a long-form grammar — not one rigid layout. Use `PageShell` `tier="wide"`, `RecordStickyContext`, and domain action menus; keep Peek triage-only. Prefer the Deal left-rail canvas (`xl:grid-cols-[minmax(16rem,20rem)_minmax(0,1fr)]`): reference details and settings in the rail, decision metrics and intelligence in the main column. Section order is defined in `app/components/records/recordDetailGrammar.ts` and marked with `RecordDetailSection`: identity → actions → notifications → profile → metrics → relationship intelligence/evidence → activity/work → related records → notes/files → history. Domain adapters place compact stakeholder lists in the rail when they fit, and keep interactive people grids/warm-path controls in the main column when they need width. Do not put Relationship Evidence as the first dominant block after the header, do not put aggregate engagement charts above decision bands, and do not convert these pages into a multi-panel console.

**States.** Every list/collection ships all four:
- Loading → `loading.tsx` skeletons (shape-matched), never a centered spinner over content.
- Empty → `EmptyState` (`app/components/EmptyState.tsx`); `tone="brand"` teaches/encourages on a true first-run empty, `tone="muted"` for a neutral/no-results empty. Not "nothing here". Record browsers wire it through `RecordsRenderView`'s `emptyState` prop.
- Error → `ErrorState` inside a `SectionBoundary` so one failed section never blanks the page.
- Permission (403) → `AccessDenied` (`app/components/AccessDenied.tsx`): `variant="page"` for a route-level refusal (localized via `AccessDeniedPage`), `variant="inline"` for an in-panel refusal (settings/organization tabs). Never a swallowed 403 that renders as empty — an empty security surface implies "nothing happened" when the answer is "you may not view this."

**Cards vs open sections (§13/§16).** Cards are semantic containers — an actionable insight, a record preview, a grouped metric, a self-contained widget, a distinct status. Content that already belongs to the surrounding page uses open sections, dividers, typography, and whitespace. No card-in-card unless hierarchy demands it.

**Density & work modes (§2).** Comfortable is the baseline. Compactness is user-selected, scoped, reversible, and persistent — only in explicit bounded work modes (tables, bulk cleanup, imports, pipeline review). Cards, forms, Home, dashboards, detail pages, and mobile never inherit table density.

**Overlay selection (§11/§12).** Inline edit for simple reversible fields; dialog/sheet for focused creation and small multi-field edits; a dedicated page/workspace for complex or consequential work. Reach for the lightest surface the job allows — a modal is rarely the first answer.

**Motion (§14).** Expressive in memorable moments (page/section entrance via `Rise`, Peek, palette, Quick Create, drag/drop, meaningful completion); quiet and fast in repeated operations (table edit, filter/sort, bulk review, notification processing). *The first interaction may delight; the fiftieth must not irritate.* Repeated chrome (`PageShell`, `PageHeader`) carries no motion of its own — the page entrance owns it. Every animation honors `prefers-reduced-motion`.

**Mobile (§12).** Same capability semantics, different presentation: task-adaptive lists and focused sheets, not desktop card stacks or horizontally scrolled tables. Generous touch targets; one task at a time; defer complex authoring until a real mobile job justifies it.

**Color (§15/§18).** Calm neutral canvas. Semantic palettes (`--warmth-*`, `--chart-*`, success/warning/destructive, `--rank-*`) carry unambiguous meaning — never a raw hex or px, never color alone.

### Review checklist (every new page / redesign / cross-surface pattern)
- [ ] Content is inside `PageShell` at the correct `tier`; no hand-rolled wrapper, gutter, or `gap-*` (full-bleed, marketing/docs, and full-height editor shells excepted).
- [ ] Any `loading.tsx` uses the same wrapper and tier as its page.
- [ ] Title is a `PageHeader` (canon size) with actions in the cluster slot; no bare `<h1>`.
- [ ] Loading, empty, error, and permission states all present and use the shared components.
- [ ] Cards only for genuine semantic units; open sections otherwise; no card-in-card.
- [ ] Comfortable spacing by default; any density is an explicit, reversible work mode.
- [ ] Overlay weight matches edit complexity (inline → dialog/sheet → page).
- [ ] Motion restrained in repeated operations; reduced-motion path verified.
- [ ] Only design tokens for color/spacing; verified in light + dark.
- [ ] EN + JA keys added for every new string; verified at representative desktop and mobile widths.

## Conventions

- **Comments:** JSDoc/TSDoc on exported functions/components/types only. **No inline comments.**
- **Types:** no `any`, no unchecked casts. Derive types from data; validate at boundaries.
- **Components:** keep presentational components free of data fetching / business logic. Server Components by default; `"use client"` only when needed.
- **Theming:** support light/dark via tokens + `next-themes`; don't assume one theme.
- **Icons:** use **Heroicons** (`@heroicons/react`) by default. Only fall back to `lucide-react` when Heroicons has no suitable icon. Don't mix both for the same icon set.
- **Heavy data viz** (recharts / `d3` / `@xyflow/react`, relationship graphs, maps): these pages are performance-sensitive. Memoize derived series and node/edge data, virtualize long lists, and don't re-render charts on unrelated state changes.
- **Env vars:** any `NEXT_PUBLIC_*` variable is bundled into the browser build — never put a secret behind that prefix. Server-only secrets stay unprefixed and server-side.

## API contract & integration

- **The backend is reached at `/api/*`.** `next.config.ts` rewrites `/api/:path*` → `http://localhost:8080/api/:path*`, so the backend must be running (`./gradlew bootRun`) for the app to work. Uploads, logos, avatars, and business-card images go directly to authenticated backend multipart endpoints through the shared API client; never store them in Next.js `public/` or add a frontend filesystem upload route.
- **Use the shared API client** in `app/lib/api.ts`; types in `app/lib/types.ts` must match backend DTOs. Don't scatter raw `fetch` calls.
- **Auth/session is cookie-based** (`JSESSIONID` + `connex_workspace`), enforced in `proxy.ts` (route protection, login/onboarding/invite redirects). When adding protected routes, update the prefixes there.
- **Slow AI generation uses bounded server handles and identity-bound polling.** The shared client initiates once, keys in-flight work by the opaque authenticated-session generation from `/api/auth/csrf`, workspace, and locale, then polls `/api/ai/generations/{handle}`; never recover a lost response by issuing another generation POST. Auth/workspace transitions must abort polling and notify other tabs without persisting user or session identifiers. Keep accepted/running, failed, timed-out, and resolved UI states distinct.
- **Creation duplicate checks are canonical and fail closed.** Person/company create forms, staged contacts, and OCR-populated contact/company review use `useDuplicatePreflight` with the complete current identity fields and the same `RequestInit` scope as the mutation. The debounce is only an early warning: every submit calls `reviewNow()`, acknowledgement is bound to the exact request workspace and complete candidate-response token, and truncated results cannot be acknowledged. Person, company, and deal CSV previews return a one-use `duplicateReviewProof`; retain only the proof for the exact workspace/rows/mapping/action/links snapshot and include it in that commit. Keep business-card submission on its dedicated import API so OCR provenance is retained.
- **Interaction-history imports are review-bound and inert.** The Settings → Data wizard imports one historical person participant per activity, note, or task. Retain its one-use `duplicateReviewProof` only for the exact workspace/kind/rows/mapping/links snapshot, never auto-link shared or ambiguous candidates, and never add client-side automation or notification side effects to the backfill path.
- **Surface errors as toasts** via `app/lib/toast.ts` (`sonner`) — don't swallow failures or leave dead UI. Handle loading and error states explicitly.

## Internationalization (EN + JA)

- Connex is **bilingual: English and Japanese.** Every user-facing string goes through `next-intl` — add keys to **both** `messages/en` and `messages/ja`. Never hardcode copy.
- Supported locales and the default live in `i18n/config.ts`; request message loading lives in `i18n/request.ts`. Use the Japanese font (`--font-noto-sans-jp`) where JA renders; don't assume Latin-only text widths in layouts.

## Accessibility

- Build on the Base UI / Radix primitives in `components/ui` — they ship correct roles, focus management, and keyboard nav. Don't re-implement interactive primitives without it.
- Semantic HTML first; ARIA only to fill gaps. Everything interactive must be keyboard-operable with a visible focus state.
- Color and contrast come from tokens (which carry the contrast guarantees) — don't hand-pick colors that break it.
- Respect `prefers-reduced-motion` for animations (the `motion` lib + `review-animations` skill cover this).

## Delegated frontend work

Follow the root delegation tiers and budget.

- The orchestrator writes the plan for Tier 1 and most Tier 2 frontend changes; do not dispatch a separate Plan agent to restate settled page/component contracts.
- Use one mutating frontend owner. Split a second mutating lane only when the API contract is fixed and the file sets do not overlap.
- Give every frontend agent exact routes, components, reference pages, state/URL contracts, i18n keys, responsive states, and browser-verification steps.
- Reuse one context packet and one running browser session for related flows. Do not send several agents to rediscover the same reference page or repeat the same Playwright journey.
- Standard frontend work needs one independent adversarial review. Add a second review only for a distinct Tier 3 concern such as auth/session, permission visibility, cross-workspace state, destructive bulk behavior, or a release-critical migration.

Any subagent dispatched to implement frontend work must receive the approved plan before editing: components/files to touch, design-system pieces reused, state/URL contracts, i18n keys, and browser-verification steps. Discovery, review, and verification agents are exempt.

## Definition of Done (frontend)

1. `pnpm lint` and `pnpm exec tsc --noEmit` clean.
2. **Run the test harness.** `pnpm test` (vitest, pure logic under `test/unit/`) must pass, and if your change touches one of the covered flows, `pnpm e2e` (Playwright, `test/e2e/`) against a running stack must too — see [`docs/FRONTEND_TESTING.md`](../docs/FRONTEND_TESTING.md). Add or update unit tests when you change pure logic that already has coverage.
3. **Verify in a real browser — this is the frontend test gate.** Run `pnpm dev`, then use the **Playwright MCP** to open the implemented page and confirm it renders and the flow completes with no console errors. Interactive browser verification is mandatory, not optional — the e2e suite covers only eight core flows. (Requires the Playwright MCP server connected — if absent, say so, don't skip.)
   - **Run the Playwright MCP in `--isolated` mode.** Several agents share this one clone, and Chrome lets only one process hold a profile at a time — the default shared profile serializes browsing to a single agent and fails the rest with `Browser is already in use for …/mcp-chrome-… use --isolated`. With `--isolated`, each agent gets its own fresh browser profile/context, so concurrent verification doesn't collide. Configure it where the server is registered, e.g. `npx @playwright/mcp@latest --isolated` (add `--headless` for CI/headless runs). Trade-off: an isolated profile starts logged-out, so make session login part of the verification flow (hit `/auth/login`, authenticate, then drive the page) rather than relying on a persisted session.
   - Cover every materially changed path in one coherent verification pass where practical; duplicate browser runs are not a substitute for broader state coverage.
4. Self-review the diff, then run **one independent adversarial review**. A matching `/code-review` satisfies this requirement. Auth, invite, sharing, cross-workspace state, or permissions UI changes additionally get `/security-review`; add a second reviewer only when the root Tier 3 rules require a distinct charter.

## Commands

This repo uses **pnpm** — don't run `npm install` or reintroduce `package-lock.json`.

- Install: `pnpm install`
- Add a package: `pnpm add <pkg>` — **then always `pnpm audit`** and resolve/flag findings before continuing.
- Dev: `pnpm dev`
- Build: `pnpm build`
- Verify a production build's assets: `node ci/verify_build_chunks.mjs .next`
- Lint: `pnpm lint`
- Typecheck: `pnpm exec tsc --noEmit`
- Unit tests: `pnpm test` (vitest; `pnpm test:watch` for watch mode)
- E2E tests: `pnpm e2e` (Playwright; needs the full stack running — see [`docs/FRONTEND_TESTING.md`](../docs/FRONTEND_TESTING.md))

Unit tests cover pure logic only; the browser verification in the Definition of Done remains the gate for everything the harness doesn't cover.

### The build-asset gate

`ci/verify_build_chunks.mjs` reads every route's `*_client-reference-manifest.js` under `<dist>/server/app` and asserts that each JS chunk and each non-inlined `entryCSSFiles` stylesheet the route declares was actually emitted. A route that names an asset the build never wrote serves fine until a browser requests it — the page then 404s a chunk or renders unstyled — so no amount of fetching one page catches it. That is how #972 shipped.

It runs in three places, and **any change to how the frontend is built must keep all three fed a real production build directory**:

1. after `next build` in CI's e2e job, against `.next`;
2. inside the deployable image in `ci/smoke_image.sh`, against `/app/.next`;
3. against the exact release candidate digest in `release.yml`, via the same smoke script.

It takes the build directory as its one argument and defaults to `.next`. Point it at `NEXT_DIST_DIR` instead if you build elsewhere. It needs `server/app` **and** `static/` present: in a standalone image those arrive from two different `COPY` layers, so verify against the assembled `/app/.next`, not the `standalone/` subtree alone.
