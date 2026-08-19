<!-- BEGIN:nextjs-agent-rules -->
# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` before writing any code. Heed deprecation notices.
<!-- END:nextjs-agent-rules -->

# Frontend — Agent Guide

The root [`/AGENTS.md`](../AGENTS.md) applies here in full. This file adds frontend-specific rules. The Golden Rules — Explore→Plan→Question→Act, match existing patterns, docs-only comments, strict types, scoped skills, and independent risk-tiered review — are not optional here.

**Before writing user-facing copy, naming anything, or designing a flow, read [`docs/PRODUCT.md`](../docs/PRODUCT.md).** It is the product source of truth: positioning, the canonical EN/JA vocabulary (glossary and banned terms), voice and tone, the standard interaction moments (create/edit/confirm/empty/loading/error), and the target IA. Labels, titles, and buttons use its glossary verbatim; when it and an existing screen disagree, the guide wins.

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

1. **Reuse `components/ui` first.** The shadcn/Base UI primitives there already cover most needs (button, split button, icon button, segmented control, dialog, input, select, combobox, chart, sheet, …). Never hand-roll a primitive that exists. Extend via variants (`class-variance-authority`) and `cn`.
2. **Tokens only — `app/globals.css`.** Use the CSS variables / `@theme` tokens. No arbitrary hex or px. This includes domain tokens:
   - `--warmth-hot` / `--warmth-warm` / `--warmth-cool` / `--warmth-cold` for relationship temperature.
   - `--chart-*` (`chart-1..5`, `chart-won/lost/open`, `chart-grid/axis/stroke`) for data viz — use these with recharts/d3, not raw colors.
   - `--sidebar-*`, semantic `--background/--foreground/--muted/--accent/--destructive`, etc.
3. **Motion tokens — `app/globals.css` and `app/lib/motion.ts`.** Timings and curves are tokens like every other design value: three speeds (`--motion-micro`, `--motion-standard`, `--motion-expressive`) and two characters (`ease-hand`, `ease-calm`) beside the neutral `--ease-out`, mirrored for JS by `durationMicro/Standard/Expressive` (seconds, for `motion/react`), `durationMicroMs/StandardMs/ExpressiveMs` (milliseconds, for `Element.animate` and style objects), and `easeHand`/`easeCalm`/`easeOut`. Use the shared named springs (`springJiggle` — the house bouncy "jiggle", `springSnappy`, `springSmooth`, `easeOut`, `instant`) instead of ad-hoc `{ type: 'spring', … }` literals. Always pair with a `useReducedMotion()` fallback (`instant`). The full contract is [§14 Motion](#motion--the-d3-contract-14).
4. **`emil-design-eng`** for the feel and the invisible details.
5. **Live reference pages** — match these in look and behavior for any new UI:
   - `app/(app)/overview/analytics`
   - `app/(app)/dashboard`
   - `app/(app)/records/*` (contacts, companies, deals, pipelines)
   - `app/(app)/library/*` (files, tags)

When in doubt, open a reference page and mirror it.

## Motion — the D3 contract (§14)

[`docs/PRODUCT.md`](../docs/PRODUCT.md) §3 principle 7 and §6 "Motion" are the product law — *motion responds, never delays*. This section is the mechanism that makes it enforceable: the tokens, when each is spent, the character rules, the responsiveness contract, and the technique catalog. Motion that removing would cost no meaning is decoration; delete it instead of tuning it.

### The three speeds

Timings are tokens, exactly like color. They are declared once in `app/globals.css` and mirrored in `app/lib/motion.ts` so a CSS surface and a `motion` surface can never disagree.

**Each speed ships in two units, and they are not interchangeable.** `motion/react` transitions take seconds (`durationMicro` = 0.15); the Web Animations API (`Element.animate`) and React style objects take milliseconds (`durationMicroMs` = 150). Pick by the API you are calling — passing one the other's number animates the surface a thousand times too fast or too slow. The millisecond values are canonical and the seconds derive from them by exact division, so the pair can never drift.

| Token | JS mirror | Value | Spend it on |
|---|---|---|---|
| `--motion-micro` | `durationMicro` | 150ms | Feedback: hover, toggle, press, focus rings, chips, and anchored popups appearing under the cursor — menus, popovers, tooltips, selects, comboboxes, hover cards. |
| `--motion-standard` | `durationStandard` | 250ms | Surfaces that take or release focus: dialogs, drawers, bottom sheets, backdrops, and in-page section reveals. |
| `--motion-expressive` | `durationExpressive` | 400ms | Rare and memorable: page arrival (`Rise`), a completed multi-step flow, genuine celebration. If a screen spends this more than once, it has spent it wrong. |

Page arrival is expressive; a section revealing *within* an already-arrived page is standard. Only `Rise` spends expressive by default.

Durations are consumed as `duration-(--motion-micro)`, not `duration-150`. Tailwind v4.3 *does* have a `--transition-duration-*` theme namespace that would give shorter `duration-micro` utilities, and that is a legitimate alternative — this file deliberately does not use it. Plain `:root` variables keep the whole motion family under one `--motion-*` prefix (durations and easings alike), keep one name per token instead of a Tailwind-facing `--transition-duration-micro` beside a JS-facing `durationMicro`, and are always emitted: `@theme` variables are tree-shaken when no generated utility references them, which would silently drop a token that raw CSS or `getComputedStyle` reads. The easings *are* registered in Tailwind's `--ease-*` namespace through `@theme inline`, mirroring how `--warmth-*` is exposed as `--color-warmth-*`. A literal `duration-0` is not a timing choice — it is the reduced-motion escape hatch — and stays legal.

**Exits are faster than entrances, and the scale encodes it:** a surface that enters at `standard` leaves at `micro`. Which variant carries it depends on the *exit mechanism*, not on the library: a keyframe `animate-out` exit is retimed with `data-closed:duration-(--motion-micro)` (Radix menus and Base UI popups both use `data-closed`), while a CSS-transition exit is retimed with `data-ending-style:duration-(--motion-micro)` (the Base UI drawer). `micro` is the floor — feedback-scale motion needs no exit variant.

**One exception, by product decision: a sheet in its full-viewport presentation exits at `standard`.** A surface translating the height of the phone reads as a jump-cut at 150ms. Both expandable mobile sheets — Quick Create (`QuickCreateLauncher.tsx`) and the note editor (`NoteDialog.tsx`) — open collapsed and expand on a pull, so the override is **conditional on that expanded state**, sitting in the `expanded` branch of the class expression rather than on the base string: `data-ending-style:duration-(--motion-standard)`. Collapsed, the same sheet is a partial-height surface and inherits the micro exit. Side sheets and partial-height drawers (the workflow editor, record peek, the shared `ResponsiveDialog`) always keep it. The rule: exits are micro, except a surface presenting at full viewport, which exits at standard — and a surface that can be both must switch with its presentation.

### The two characters

| Token | Utility | JS mirror | Character |
|---|---|---|---|
| `--motion-ease-hand` | `ease-hand` | `easeHand` | `cubic-bezier(0.34, 1.56, 0.64, 1)` — overshoots, then settles. The CSS stand-in for `springJiggle` on anything answering the user's hand: press, hover pop, menu entrance, stagger. |
| `--motion-ease-calm` | `ease-calm` | `easeCalm` | `cubic-bezier(0.32, 0.72, 0, 1)` — decisive deceleration, no overshoot. State changes and surfaces sliding into place: dialogs, drawers, backdrops, layout settling. |

A third curve predates these two and stays: **`--ease-out` / `easeOut`** (`cubic-bezier(0.23, 1, 0.32, 1)`), the house override of Tailwind's weak default `ease-out`. It is the neutral arrival curve — `Rise`, the range calendar, scroll affordances — and sits between the two characters: stronger than a browser default, without `ease-hand`'s overshoot. Reach for `ease-hand` or `ease-calm` when the motion has a character to state; `ease-out` when it simply needs to arrive well. Do not add a fourth.

Springs are for the hand; easing is for state. In JS, reach for the springs first (`springJiggle` playful, `springSnappy` precise, `springSmooth` gliding) — a spring reacts to interruption, an easing curve does not, and anything the user can grab, drag, or spam should react. Use `easeHand`/`easeCalm` where CSS owns the transition or where a spring would fight a measured layout. **Overshoot belongs on entrances only** — an exit that grows before it leaves reads as a glitch, so scope `ease-hand` to the open state (`data-open:ease-hand`) and let exits run calm.

Celebration is rare and earned. Confetti-grade motion needs a reason a user would name.

### The responsiveness contract

Non-negotiable, and reviewable line by line:

1. **Acknowledge input within 100ms.** The press state, the focus ring, the pending row — something changes before any request resolves. Nothing may wait on the network to admit it was clicked.
2. **Motion accompanies the result; it never precedes it.** No animation plays *while* the app decides. The content arrives, and the motion carries it in.
3. **Nothing blocking runs past ~300ms.** `expressive` is 400ms because it is never blocking. If the user must wait for it, it is the wrong speed.
4. **Exits are faster than entrances** — micro, except full-viewport translates, which exit at standard (above). Leaving is not a performance; closing is a decision the user already made.
5. **`prefers-reduced-motion` is honored everywhere.** See below — it is a hard gate, not a nice-to-have.
6. **Motion is never a loading strategy.** A spinner masquerading as personality is still a spinner. Skeletons that mirror the destination's real layout carry loading (PRODUCT.md §6); motion carries arrival.

### Reduced motion

Every animation degrades. The conventions already in the codebase, applied consistently:

- **CSS animations** (`animate-in` / `animate-out` on Radix and Base UI popups) — add **`motion-reduce:animate-none!`**, with the important modifier. The plain form does not work. `data-open:animate-in` compiles to `.cls:where([data-state="open"])` — the same (0,1,0) as `.cls` inside the media query, and Tailwind emits the `motion-reduce` rule **first**, so the animation wins on source order. The raw-Radix idiom `data-[state=open]:animate-in` is worse: `.cls[data-state="open"]` is (0,2,0) and outranks the guard outright. `!` settles both cases. Both libraries read the computed animation and unmount immediately when it is `none`, so the overlay still closes.
- **CSS transitions** — add `motion-reduce:transition-none`. This one needs no `!`: Tailwind sorts variant-carrying utilities after bare `transition-*`.
- **Transform-only feedback** (press dips, `active:scale-*`) — keep the color/opacity change and drop the movement: `motion-safe:active:translate-y-px`, or a `motion-reduce:active:scale-100` counterpart.
- **JS motion** — `const reduce = useReducedMotion() ?? false`, then the `instant` preset (or a plain wrapper, as `Rise` does).

What "degrades" means: **drop movement and position changes — translate, scale, rotate, parallax, layout springs — and keep brief opacity or color where it still aids comprehension.** A crossfade tells the user the content changed; a slide is what makes them ill. `QuickCreateLauncher`'s reduced-motion path is the reference: no slide, no blur, no height spring, but a 0.12s opacity crossfade so the view swap is still legible. What is never acceptable is running the *same* movement faster — reduced motion is not a speed setting.

Verify it, don't assert it: emulate `prefers-reduced-motion: reduce` in the browser and confirm the surface still opens, closes, and unmounts.

### The technique catalog

**The signature technique is the morph** — an element that continues across surfaces keeps its identity instead of being replaced. The shipped examples are the reference implementations; copy their shape rather than inventing a fourth:

- **Search ↔ palette morph** — `app/components/GlobalSearch.tsx`. The inline search pill and the centered command palette share `layoutId="global-search-pill"` with `springSnappy`, so the pill *becomes* the palette. Reduced motion swaps the transition for `instant`.
- **Quick Create morph** — `app/components/actions/QuickCreateLauncher.tsx` (`MorphingBody`). The type selector and the create form crossfade and slide while the drawer's height springs to the measured height of the active view, so the swap reads as one surface reshaping rather than two panels appearing. The first sizing is instant so it never competes with the drawer's own entrance.
- **Drawer-to-drawer** — the rule that keeps the two above honest: **two drawers never transition at once.** On mobile the Quick Create selector and every create dialog are Base UI drawers, so a hand-off waits for the first to finish closing before the second opens — overlapping sheets desynchronize the shared backdrop and flick the new dialog straight back down. The mechanism is `lib/overlay-lifecycle.ts` (`createCloseCompletionGate`, wired through `ResponsiveDialog`'s `onCloseComplete`, with `OVERLAY_MAX_EXIT_DURATION_MS` as the backstop for surfaces that never report). When a hand-off *doesn't* need a different drawer, don't sequence — keep one drawer open and morph its contents.
- **Shared-indicator morph** — `components/ui/tabs.tsx`. One `layoutId` pill travels between triggers on `springSnappy` instead of fading in and out per tab.

Beyond the morphs: `Rise` (`app/components/motion/Rise.tsx`) owns page arrival at the expressive speed with a ~60ms stagger — repeated chrome adds none of its own. Navigation between routes belongs to the page entrance, not to per-component animation.

### The rule, and the gate

**New or changed motion on a surface you touch uses the tokens.** No `duration-150`, no `duration-[220ms]`, no `transition-duration: 300ms`, no `transition: transform 0.7s`, no `animationDuration={500}`, no `duration: 0.25` in a `motion` transition — name a token. Where a shipped value is genuinely off-scale, retime it to the nearest token and say so in the PR; do not add a fourth speed.

`test/unit/motionDurations.test.ts` (over `lint/motionDurations.mjs`) enforces this. It reads every `.css`, `.js`, `.jsx`, `.ts`, and `.tsx` file under `app/`, `components/`, and `lib/`, and reports four idioms — the Tailwind `duration-*` utility; any `transition`/`animation` declaration, shorthand or `-duration` longhand, counted **once per time literal** so a three-part shorthand reports three; a `duration:` field in a `motion` or Web Animations options object; and a camelCase `animationDuration`/`transitionDuration` prop. Every finding names the mirror its idiom needs, because seconds and milliseconds are not interchangeable. It fails on any file outside the committed ledger in `lint/motion-duration-baseline.json`.

Each of those rules exists because its absence hid real debt: the first version saw only the longhand, so a stylesheet with a dozen shorthand timings satisfied the `TOKENIZED_SURFACES` zero-assertion vacuously; the second read no `.js`/`.jsx` and scored a three-timing declaration as one. When you find a hole, widen the scanner and take the number the wider rule reports.

The ledger is a **burndown**: counts may only fall, a file that reaches zero is deleted from it, and `BASELINE_HIGH_WATER_MARK` follows the resulting total. It rises only in the same commit that widens what the scanner catches — never to accommodate new debt — and if that commit also pays debt down, the mark falls with it. `TOKENIZED_SURFACES` is the opposite list — the shared primitives that carry the system for every overlay, menu, and press in the product. They must stay at zero and must never reappear in the ledger.

Pair every motion change with the **`review-animations`** skill (mandatory with `emil-design-eng`) and record the result on the PR.

## Product grammar

The signed-off 1.0 interaction contract (issue #842). A working checklist, not prose. The shared components named here are the enforcement surface — reuse them; do not hand-roll a wrapper, header, or state that one of them already owns.

**Page shell & full width.** Every routed surface that renders inside the app shell `<main>` wraps its content in `PageShell` (`app/components/PageShell.tsx`) — never a hand-rolled `min-h-full … mx-auto max-w-* flex flex-col gap-*` div. `PageShell` takes no width prop, because **there is no page-width cap**: every page spans the full content area at every screen size, so no surface pays for a dead gutter. Do not reintroduce a cap — not through `className`, not through an `mx-auto max-w-*` wrapper sitting immediately inside the shell, and not by hand-rolling a capped column in a board component. `test/unit/loadingStates.test.ts` fails any `<PageShell>` that carries a `max-w-*`.

Width is then spent from the inside out, and the surface class decides how:
- **Data surfaces** — browsers, dashboards, analytics, library, settings, admin — stretch. Their tables, `auto-fill` card grids, and charts are already fluid, and the width is the whole point. A grid with a fixed column count may gain a `2xl:` step when it holds more items than columns; a grid whose item count equals its column count gains nothing and stays as it is.
- **Record detail** keeps the left-rail grammar below: the rail stays clamped at `minmax(16rem,20rem)` and the main column absorbs the rest.
- **Editors, forms, and readers** span *as pages* — header, toolbar, background, and dividers reach the full width, the way a document editor's chrome does — while the content inside keeps its own measure: a readable measure on the **text block** (`max-w-3xl`, `max-w-[70ch]`, `max-w-prose`) and field grids that flow responsively. A measure on the block is right; the same value on the page is the cap coming back through the back door.
- **Full-bleed surfaces** (the relationship map), marketing/docs shells, and **full-height editor shells** (the workflow editor, the document template builder, and their `loading.tsx`) do not use `PageShell` — they own their own height and vertical rhythm, and the shell's `gap-10` would fight it.

Section rhythm (`gap-10` between stacked children) and the page gutter come from the shell — do not re-declare them, and do not override the gap per page: uniform rhythm is the point. The gutter is `px-2` stepping to `2xl:px-6`, on top of `<main>`'s `p-6`, so a full-width page breathes on a large display instead of sitting against the frame; a hand-rolled full-height shell matches that pair. A `loading.tsx` skeleton renders the same `PageShell` tag as the page it stands in for — a skeleton and its page must never disagree about the wrapper, or the page visibly jumps when data arrives.

**Page header.** The page title is a `PageHeader` (`app/components/PageHeader.tsx`), never a bare `<h1>`. Canon: `text-4xl font-extrabold tracking-tight`; the `compact` variant only for a genuinely secondary page. Optional `description` sits under the title; `actions` is the right-aligned cluster (the caller composes primary + secondary). Domain identity headers (record detail with a dynamic name/avatar, the Me and report-document heroes) are §17 domain expression and keep their bespoke header — they still adopt `PageShell`.

**Record detail (#843).** Contact, Company, and Deal detail pages share a long-form grammar — not one rigid layout. Use `PageShell`, `RecordStickyContext`, and domain action menus; keep Peek triage-only. Prefer the Deal left-rail canvas (`xl:grid-cols-[minmax(16rem,20rem)_minmax(0,1fr)]`): reference details and settings in the rail, decision metrics and intelligence in the main column. Section order is defined in `app/components/records/recordDetailGrammar.ts` and marked with `RecordDetailSection`: identity → actions → notifications → profile → metrics → relationship intelligence/evidence → activity/work → related records → notes/files → history. Domain adapters place compact stakeholder lists in the rail when they fit, and keep interactive people grids/warm-path controls in the main column when they need width. Do not put Relationship Evidence as the first dominant block after the header, do not put aggregate engagement charts above decision bands, and do not convert these pages into a multi-panel console.

**States.** Every list/collection ships all four:
- Loading → `loading.tsx` skeletons (shape-matched), never a centered spinner over content.
- Empty → `EmptyState` (`app/components/EmptyState.tsx`); `tone="brand"` teaches/encourages on a true first-run empty, `tone="muted"` for a neutral/no-results empty. Not "nothing here". Record browsers wire it through `RecordsRenderView`'s `emptyState` prop.
- Error → `ErrorState` inside a `SectionBoundary` so one failed section never blanks the page.
- Permission (403) → `AccessDenied` (`app/components/AccessDenied.tsx`): `variant="page"` for a route-level refusal (localized via `AccessDeniedPage`), `variant="inline"` for an in-panel refusal (settings/organization tabs). Never a swallowed 403 that renders as empty — an empty security surface implies "nothing happened" when the answer is "you may not view this."

**Cards vs open sections (§13/§16).** Cards are semantic containers — an actionable insight, a record preview, a grouped metric, a self-contained widget, a distinct status. Content that already belongs to the surrounding page uses open sections, dividers, typography, and whitespace. No card-in-card unless hierarchy demands it.

**Density & work modes (§2).** Comfortable is the baseline. Compactness is user-selected, scoped, reversible, and persistent — only in explicit bounded work modes (tables, bulk cleanup, imports, pipeline review). Cards, forms, Home, dashboards, detail pages, and mobile never inherit table density.

**Overlay selection (§11/§12).** The operation → surface mapping is product law in [`../docs/PRODUCT.md`](../docs/PRODUCT.md) §6: create = centered dialog (builder artifacts via instant-create), edit = right drawer, inspect/peek = drawer or anchored popover with a path to the full page, confirm = small dialog, author = dedicated full page; on mobile these render as bottom sheets per the responsive-dialog rule. Inline edit remains right for simple reversible fields; per-surface deviations are documented decisions, not drift.

**One composer per object (D19).** `app/components/activity/tasks/TaskDialog.tsx` and `app/components/activity/activities/ActivityDialog.tsx` are the only task and activity composers in the product. A record surface opens them through `app/components/records/RecordComposers.tsx` (`RecordTaskComposer` / `RecordActivityComposer`), which pre-links the anchor record, scopes the other side of the link to the anchor's company, and files the contact onto the deal when both are chosen. Do not add a second create dialog for an object that already has one — pass a `defaultPerson` / `defaultDeal` and, when a surface needs a side effect on create, a `createRequest`.

**Abandoning work.** Every dialog or drawer that accumulates input wires `useUnsavedChangesGuard` + `ConfirmDiscardDialog` (the quick-edit drawers inherit both from `QuickEditSheetShell` — give it a `dirtySnapshot`). The committed denominator is `lint/draft-guard-inventory.json`, enforced by `test/unit/draftGuardInventory.test.ts`: add the surface there in the same commit that adds the surface, with `"guard": "own"` when it wires the guard itself or the guarding file's path when it renders one that does. The list only grows.

**Destructive confirms.** `app/components/records/DeleteRecordDialog.tsx` is the only delete confirmation. It owns the grammar — title "Delete {object}?", a body naming the object and ending "This can't be undone.", destructive "Delete" — from `RecordsDeleteDialog` in `messages/{en,ja}/records.json`. A surface contributes an `entityLabel`, an optional `getDisplayName`, and any surface-specific consequence or preview through `details`; it never restates the grammar. "Delete permanently" plus typed confirmation is reserved for organization-level permanent deletion (`OrganizationLifecyclePanel`).

**Buttons — the D4 system.** [`docs/PRODUCT.md`](../docs/PRODUCT.md) §6 "Buttons" is the law; `components/ui` is the only place it is implemented. Reuse, do not restyle:

- **`Button`** (`components/ui/button.tsx`) is pill-shaped in every standalone size and carries the context height scale on `size`: **`page`** (`h-9`, page-header action cluster), **`dialog`** (`h-9`, dialog and drawer footers), **`toolbar`** (`h-8`, browser toolbars), **`inline`** (`h-6`, inside a row, cell, or card), plus their `icon-` forms. The heights are read off the reference pages, not invented; `page` and `dialog` stay separate names because they are separate laws even where the number agrees today. The legacy `default`/`sm`/`xs`/`lg`/`icon*` names resolve to the same heights so nothing broke when the tiers landed, and the gate below burns them down.
- **Menu triggers are always chevroned.** Pass `menu` — the primitive draws and rotates the chevron. An action-looking button never surprises with a menu. Ellipsis overflow triggers are the exception the glyph already covers, and the filter-pill layer (`pillClass`, `RecordsSortMenu`, `ColumnVisibilityMenu`) is a distinct control family whose convergence is [#509](https://github.com/itkla/connex/issues/509) Phase 3.
- **`IconButton`** (`components/ui/icon-button.tsx`) is the only way to ship an icon-only button: `label` is both the accessible name and the tooltip, so they cannot drift. It forwards every prop, so it composes as the child of a `DropdownMenuTrigger asChild` exactly as `Button` does.
- **`SplitButton`** (`components/ui/split-button.tsx`) is one capsule — primary verb, inset hairline divider, chevron menu — not a `ButtonGroup` assembled by hand. `RecordsActions` is the reference call site.
- **`SegmentedControl`** (`components/ui/segmented-control.tsx`) is the only mode/view switch. Never a row of toggle buttons, never a second hand-rolled track. `SortToggle`, `DensityToggle`, the calendar `ViewSwitcher`, and the analytics `RangeControl` are thin naming layers over it.
- **One primary action per view region.** A page header, an empty state, and a dialog footer are three regions; two `variant="brand"` buttons inside one of them is the bug. **Open product call:** the record-detail action clusters (`Contact`/`Company`/`DealActionsMenu`) currently carry *zero* primaries — every entry is `variant="outline"`. Which action earns the primary there is a product decision, not a shape one, and it is unresolved.

`lint/ad-hoc-button-baseline.json` is the committed denominator, enforced by `test/unit/adHocButtons.test.ts` over `lint/adHocButtons.mjs`. It behaves exactly like the motion gate: the ledger only shrinks, a file that reaches zero leaves it, and `BASELINE_HIGH_WATER_MARK` rises only in a commit that widens the scanner. `SYSTEM_SURFACES` is the permanent opposite list — the primitives that decide the shape, which must never appear in the ledger.

**Be precise about what that gate covers**, because it is not the whole of D4. It **measures** six idioms: a `<button>` or `role="button"` element painting a button surface at a reserved control height; a button-shaped `<a>`/`<Link>`; a hoisted class string doing the same; a `className` re-deciding radius or height; a `size` outside the context tiers; and an icon-only `<Button>` with no tooltip wrapper. It **does not measure** the chevron law or one-primary-action-per-region — whether a button opens a menu, and whether two primaries share a region, are facts about a render tree a text scanner cannot read honestly. Those two stay review-enforced, on the checklist below.

**Validation.** Create and edit paths both validate inline: `useFieldErrors` holds the messages, the control gets `aria-invalid` and `aria-describedby`, and the message renders under the field (`fieldErrorClass` for the ring, `QuickEditField`'s `error` inside a quick-edit drawer). A required field is marked with the quick-edit `text-destructive` asterisk. A toast is for the outcome of a request, never for a field the user can fix in front of them.

**Motion (§14).** Expressive in memorable moments (page/section entrance via `Rise`, Peek, palette, Quick Create, drag/drop, meaningful completion); quiet and fast in repeated operations (table edit, filter/sort, bulk review, notification processing). *The first interaction may delight; the fiftieth must not irritate.* Repeated chrome (`PageShell`, `PageHeader`) carries no motion of its own — the page entrance owns it. Every animation honors `prefers-reduced-motion`. The speeds, characters, techniques, and responsiveness contract are law in [§14 Motion](#motion--the-d3-contract-14).

**Mobile (§12).** Same capability semantics, different presentation: task-adaptive lists and focused sheets, not desktop card stacks or horizontally scrolled tables. Generous touch targets; one task at a time; defer complex authoring until a real mobile job justifies it.

**Color (§15/§18).** Calm neutral canvas. Semantic palettes (`--warmth-*`, `--chart-*`, success/warning/destructive, `--rank-*`) carry unambiguous meaning — never a raw hex or px, never color alone.

### Review checklist (every new page / redesign / cross-surface pattern)
- [ ] Content is inside `PageShell` and spans the full content area; no hand-rolled wrapper, gutter, `gap-*`, or page-width cap (full-bleed, marketing/docs, and full-height editor shells excepted). Any readable measure sits on the text block, never on the page.
- [ ] Any `loading.tsx` renders the same `PageShell` tag as its page.
- [ ] Title is a `PageHeader` (canon size) with actions in the cluster slot; no bare `<h1>`.
- [ ] Loading, empty, error, and permission states all present and use the shared components.
- [ ] Cards only for genuine semantic units; open sections otherwise; no card-in-card.
- [ ] Buttons come from `components/ui` at a context `size` tier; menu triggers carry `menu`; icon-only buttons are `IconButton`s; mode switches are `SegmentedControl`s; one primary action per region.
- [ ] Comfortable spacing by default; any density is an explicit, reversible work mode.
- [ ] Overlay weight matches edit complexity (inline → dialog/sheet → page).
- [ ] Motion restrained in repeated operations; every timing names a `--motion-*` token; reduced-motion path verified in the browser (§14).
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
- **Creation duplicate checks are canonical and fail closed.** Person/company/deal create forms, staged contacts, and OCR-populated contact/company review use `useDuplicatePreflight` with the complete current identity fields and the same `RequestInit` scope as the mutation. The debounce is only an early warning: every submit calls `reviewNow()`, an acknowledged deal proof is passed back for validation without replacement, acknowledgement is bound to the exact request workspace and complete candidate-response token, and truncated results cannot be acknowledged. The deal mutation then performs the locked final recheck and consumes that same one-use proof. Person, company, and deal CSV previews return a one-use `duplicateReviewProof`; retain only the proof for the exact workspace/rows/mapping/action/links snapshot and include it in that commit. Keep business-card submission on its dedicated import API so OCR provenance is retained.
- **Interaction-history imports are review-bound and inert.** The Settings → Data wizard imports one historical person participant per activity, note, or task. Retain its one-use `duplicateReviewProof` only for the exact workspace/kind/rows/mapping/links snapshot, never auto-link shared or ambiguous candidates, and never add client-side automation or notification side effects to the backfill path.
- **Surface errors as toasts** via `app/lib/toast.ts` (`sonner`) — don't swallow failures or leave dead UI. Handle loading and error states explicitly.

## Internationalization (EN + JA)

- Connex is **bilingual: English and Japanese.** Every user-facing string goes through `next-intl` — add keys to **both** `messages/en` and `messages/ja`. Never hardcode copy.
- Supported locales and the default live in `i18n/config.ts`; request message loading lives in `i18n/request.ts`. Use the Japanese font (`--font-noto-sans-jp`) where JA renders; don't assume Latin-only text widths in layouts.
- **The banned-terms gate is generated from [`docs/PRODUCT.md`](../docs/PRODUCT.md) §4, not hand-written.** `lint/vocabulary.mjs` parses the glossary into `lint/vocabulary.generated.json`; `test/unit/vocabularyLint.test.ts` scans every string value in `messages/{en,ja}/*.json` — including the strings inside arrays, which the public docs render through `t.raw()` — against it, in error mode, against the shrinking baseline in `lint/vocabulary-baseline.json`. To fix copy, edit the string **and delete its baseline entry**; the baseline can only shrink, and `BASELINE_HIGH_WATER_MARK` rises only in a commit that widens the rules. To change what is banned, edit §4 and run `node scripts/generate-vocabulary.mjs`; a §4 item whose ban needs meaning analysis must get an explicit ban or skip with a reason in `CURATED_DECISIONS`. Compliance surfaces (`legal.json`, `organization.json` `OrgDataRequests.*`) are exempt only for the terms whose §4 entries carry the compliance carve-out. The workflow namespaces are fully in scope, and `rule`/「ルール」 are additionally banned there (narrowed per §4's legacy-automations allowance).

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
- Production start: `pnpm start`. In ordinary checkouts this runs `next start`; on the staging
  checkout, `.staging/frontend-release` makes the staging launcher execute that sha's sealed
  standalone runtime instead. See `../docs/STAGING_DEPLOY.md`.
- Verify a production build's assets: `node ci/verify_build_chunks.mjs .next`
- Lint: `pnpm lint`
- Typecheck: `pnpm exec tsc --noEmit`
- Unit tests: `pnpm test` (vitest; `pnpm test:watch` for watch mode)
- Regenerate the banned-terms model after editing `docs/PRODUCT.md` §4: `node scripts/generate-vocabulary.mjs`
- E2E tests: `pnpm e2e` (Playwright; needs the full stack running — see [`docs/FRONTEND_TESTING.md`](../docs/FRONTEND_TESTING.md))

Unit tests cover pure logic only; the browser verification in the Definition of Done remains the gate for everything the harness doesn't cover.

### The build-asset gate

`ci/verify_build_chunks.mjs` reads every route's `*_client-reference-manifest.js` under `<dist>/server/app` and asserts that each JS chunk and each non-inlined `entryCSSFiles` stylesheet the route declares was actually emitted. A route that names an asset the build never wrote serves fine until a browser requests it — the page then 404s a chunk or renders unstyled — so no amount of fetching one page catches it. That is how #972 shipped.

It runs in four places, and **any change to how the frontend is built must keep all four fed a real production build directory**:

1. after `next build` in CI's e2e job, against `.next`;
2. inside the deployable image in `ci/smoke_image.sh`, against `/app/.next`;
3. against the exact release candidate digest in `release.yml`, via the same smoke script.
4. inside the staging release builder, against the isolated target commit's `.next-new` before
   its standalone runtime is sealed and before either live service is changed.

It takes the build directory as its one argument and defaults to `.next`. Point it at `NEXT_DIST_DIR` instead if you build elsewhere. It needs `server/app` **and** `static/` present: in a standalone image those arrive from two different `COPY` layers, so verify against the assembled `/app/.next`, not the `standalone/` subtree alone.
