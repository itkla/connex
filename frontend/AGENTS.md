<!-- BEGIN:nextjs-agent-rules -->
# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` before writing any code. Heed deprecation notices.
<!-- END:nextjs-agent-rules -->

# Frontend — Agent Guide

The root [`/AGENTS.md`](../AGENTS.md) applies here in full. This file adds frontend-specific rules. The Golden Rules — Explore→Plan→Question→Act, match existing patterns, docs-only comments, strict types, mandatory skills, mandatory review — are not optional here.

## Stack

Next.js 16 (App Router, RSC) · React 19 · TypeScript strict · Tailwind v4 · shadcn/ui on Base UI + Radix (`radix-vega` style) · `motion` · `recharts` / `d3` / `@xyflow/react` · `next-intl` · `next-themes` · `sonner` · `@heroicons/react` (Lucide fallback).

Path alias: `@/*` → project root. Utils at `@/lib/utils` (`cn`), UI at `@/components/ui`, hooks at `@/hooks`.

## Skills are mandatory for UI work

Which skills you run depends on the scope of the change. Always run them **before** building, not after.

### Full pipeline — new pages, redesigns, net-new components

Run all three **in this order** — broad to specific, each pass building on the last:

1. **`impeccable`** — first. Audit-first and broadest: UX, information architecture, visual hierarchy, the design system. Sets structural direction before any pixels.
2. **`design-taste-frontend`** — next. With structure settled, lock the visual direction and anti-generic design system, then build the interface.
3. **`emil-design-eng`** (Emil Kowalski) — last. The polish pass: component feel, motion, micro-interactions, the invisible details. Pair with **`review-animations`** whenever you add or change motion.

Don't reorder: polish (step 3) belongs on top of a settled structure (step 1), never before it.

### Small in-place edits — tweaks to existing UI

For minor changes to existing components/pages (spacing, copy, a single prop, a small style fix), run **`emil-design-eng`** only — skip `impeccable` and `design-taste-frontend`. If the "small" edit turns out to touch hierarchy, layout, or IA, stop and escalate to the full pipeline.

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
- **Slow AI request de-duplication is identity-bound.** The shared client keys in-flight AI mutations by the opaque server-issued authenticated-session generation from `/api/auth/csrf`, workspace, and locale; auth/workspace transitions must invalidate locally and notify other tabs without persisting user or session identifiers.
- **Surface errors as toasts** via `app/lib/toast.ts` (`sonner`) — don't swallow failures or leave dead UI. Handle loading and error states explicitly.

## Internationalization (EN + JA)

- Connex is **bilingual: English and Japanese.** Every user-facing string goes through `next-intl` — add keys to **both** `messages/en` and `messages/ja`. Never hardcode copy.
- Supported locales and the default live in `i18n/config.ts`; request message loading lives in `i18n/request.ts`. Use the Japanese font (`--font-noto-sans-jp`) where JA renders; don't assume Latin-only text widths in layouts.

## Accessibility

- Build on the Base UI / Radix primitives in `components/ui` — they ship correct roles, focus management, and keyboard nav. Don't re-implement interactive primitives without it.
- Semantic HTML first; ARIA only to fill gaps. Everything interactive must be keyboard-operable with a visible focus state.
- Color and contrast come from tokens (which carry the contrast guarantees) — don't hand-pick colors that break it.
- Respect `prefers-reduced-motion` for animations (the `motion` lib + `review-animations` skill cover this).

## Delegated work is plan-first

Any subagent dispatched to implement frontend work must first return a short plan — components/files to touch, design-system pieces reused, state/URL contracts, i18n keys, and the browser-verification steps — and get it reviewed by the orchestrator before editing code (see the root `AGENTS.md` → *Plan-first dispatch*). Discovery, review, and verification agents are exempt.

## Definition of Done (frontend)

1. `pnpm lint` and `pnpm exec tsc --noEmit` clean.
2. **Verify in a real browser — this is the frontend test gate.** Run `pnpm dev`, then use the **Playwright MCP** to open the implemented page and confirm it renders and the flow completes with no console errors. There is no unit-test runner, so exercising the real UI is mandatory, not optional. (Requires the Playwright MCP server connected — if absent, say so, don't skip.)
   - **Run the Playwright MCP in `--isolated` mode.** Several agents share this one clone, and Chrome lets only one process hold a profile at a time — the default shared profile serializes browsing to a single agent and fails the rest with `Browser is already in use for …/mcp-chrome-… use --isolated`. With `--isolated`, each agent gets its own fresh browser profile/context, so concurrent verification doesn't collide. Configure it where the server is registered, e.g. `npx @playwright/mcp@latest --isolated` (add `--headless` for CI/headless runs). Trade-off: an isolated profile starts logged-out, so make session login part of the verification flow (hit `/auth/login`, authenticate, then drive the page) rather than relying on a persisted session.
3. `/code-review` **and** adversarial multi-agent review; address findings. Auth, invite, sharing, or permissions UI changes also get `/security-review`.

## Commands

This repo uses **pnpm** — don't run `npm install` or reintroduce `package-lock.json`.

- Install: `pnpm install`
- Add a package: `pnpm add <pkg>` — **then always `pnpm audit`** and resolve/flag findings before continuing.
- Dev: `pnpm dev`
- Build: `pnpm build`
- Lint: `pnpm lint`
- Typecheck: `pnpm exec tsc --noEmit`

No test runner is configured — the browser verification in the Definition of Done is the test gate.
