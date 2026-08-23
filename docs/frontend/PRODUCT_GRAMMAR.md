# Frontend Product Grammar

This document is the implementation companion to `docs/PRODUCT.md`. `PRODUCT.md` owns product principles, vocabulary, interaction semantics, and target IA; this document records the shared frontend primitives and signed-off implementation adaptations that keep those principles consistent across surfaces.

Read this before creating a new routed page, redesigning a page, or establishing a cross-surface interaction pattern.

## Page shell and width

Normal routed surfaces inside the app shell use `frontend/app/components/PageShell.tsx`.

- App pages span the available content area; do not reintroduce a page-level `max-w-*` cap or hand-rolled centered shell.
- `PageShell` owns normal page gutter and vertical section rhythm; pages should not re-declare them.
- Readable editors/forms may constrain the **content block** (for example prose measure) while page chrome remains full width.
- Record details may use their established clamped left rail while the main column absorbs remaining width.
- Full-bleed surfaces, marketing/docs shells, and full-height editors may own a purpose-built shell when `PageShell` would conflict with their height/rhythm.
- A `loading.tsx` skeleton uses the same shell family as the page it replaces to avoid layout jumps.

The static loading-state tests enforce common cap/wrapper mistakes; do not bypass them through computed class names.

## Page headers

Normal page titles use `PageHeader` rather than a bare page-level `<h1>`.

- Actions belong in the header action cluster.
- Use compact/secondary treatment only for genuinely secondary pages.
- Domain identity heroes (record identity, Me, report/document hero surfaces) may keep their bespoke identity header while still following the page-shell grammar.

## Record details

Contact, Company, and Deal detail pages share a long-form grammar rather than one rigid template.

Use `PageShell`, `RecordStickyContext`, domain action menus, and `frontend/app/components/records/recordDetailGrammar.ts`/`RecordDetailSection` as the ordering contract. Prefer the established left-rail/main-canvas pattern: stable reference/settings information in the rail; decision metrics, relationship intelligence, evidence, and wider interactive content in the main column.

Do not turn record details into dense multi-panel consoles or promote aggregate engagement charts/evidence above the decision-oriented content without a product decision.

## Loading, empty, error, and permission states

Every collection/list surface distinguishes all four states:

- Loading: shape-matched `loading.tsx` skeletons, not a centered spinner over content.
- Empty: shared `EmptyState`; use an encouraging first-run treatment only when it is truly a first-run empty, and a neutral treatment for no-results/filtered empties.
- Error: shared `ErrorState`, normally within `SectionBoundary` so a section failure does not blank the whole page.
- Permission: `AccessDenied`/localized page equivalent. A 403 must never masquerade as an empty result.

## Cards, density, and mobile

Cards represent genuine semantic units: an actionable insight, record preview, grouped metric, self-contained widget, or distinct status. Content already belonging to the page should usually use open sections, typography, dividers, and whitespace. Avoid card-in-card unless the hierarchy genuinely requires it.

Comfortable density is the default. Compactness is explicit, scoped, reversible, and persistent only for bounded work modes such as tables, bulk cleanup, imports, or pipeline review. Do not globally compact dashboards, forms, detail pages, or mobile.

Mobile keeps the same capability semantics but uses task-adaptive lists and focused sheets rather than horizontally scrolled desktop tables or indiscriminate desktop-card stacks. Prefer one focused task at a time and generous touch targets.

## Operation → surface mapping

Follow `docs/PRODUCT.md` for the product law. The normal implementation mapping is:

- Simple reversible field: inline edit where appropriate.
- Create: short centered dialog; builder-style artifacts may instant-create and continue on a dedicated page.
- Edit: right-side drawer/sheet for focused edits.
- Inspect/peek: drawer or anchored popover with a path to the full page.
- Confirm: small dialog.
- Complex authoring: dedicated full page/editor.
- Mobile: desktop dialogs/drawers adapt to focused bottom sheets through shared responsive primitives.

Per-surface deviations are product decisions, not precedent for inventing a new mapping.

## Signed-off adaptations

### Campaigns

Campaign creation asks for the minimum facts required by the backend (name and type) before entering the campaign builder. Objective/status/budget/window belong in the builder rather than an oversized create dialog. Campaign settings use the established quick-edit sheet shell.

### Calendar peek

Fine-pointer calendar interaction may use an anchored event peek followed by the detail sheet; coarse pointers may go directly to the sheet. This is a sequenced handoff between different overlay primitives, not a shared-element morph. Keep header continuity and clear an anchored peek when its owning calendar period/view/filter changes.

### Products

Products are managed in-place through their existing dialog rather than a separate detail route. Do not fake unsupported record-browser capabilities. Export copy must state backend filtering limitations rather than promise filters that are not applied.

### Ask Connex workspace

`/ask-connex` and `/ask-connex/[sessionId]` are the one routed exception to `PageShell`/`PageHeader`. They are a full-bleed conversational surface, not a page of content: `ContentShell` deliberately suppresses the toolbar, breadcrumb, and mobile bar for them, and the session rail plus transcript plus composer own the full viewport height. Their `page.tsx` therefore renders only the container the persistent Ask Connex controller mounts into, and the breadcrumb registry classifies both routes as `owned`.

This exists so the drawer and the routed workspace stay **one controller with two mounts**. Neither route may create its own session store, socket, or streaming; the route registers its container through the Ask Connex mount context and the controller portals into it, so a session survives moving between the drawer, `/ask-connex`, and a deep link. Both routes share one `loading.tsx` skeleton because they paint the same shell.

Do not read this as licence for other full-height surfaces to skip the page shell: it is granted by the surface being a second mount of an app-shell-level controller, not by being tall.

### Timeline comments

Timeline comment rows are chronology/read-only representations. `CommentsSection` owns composition/thread actions. Do not wrap `NoteContent` itself in a link because its parsed content may contain anchors; use a separate thread handoff.

## One composer per object

Reuse the canonical task/activity composers (`TaskDialog`, `ActivityDialog`) through record adapters such as `RecordComposers`. Pass defaults and explicit create callbacks rather than introducing another composer for the same domain object.

The same principle applies generally: extend an existing shared composer/control before creating a parallel implementation.

## Unsaved work and destructive actions

Any dialog/drawer that accumulates meaningful input participates in the shared unsaved-changes/discard-confirm pattern. Quick-edit shells inherit this through their existing dirty-state contract. Keep the draft-guard inventory/gate current when adding an eligible surface; do not increase a baseline to hide a missing guard.

Use the shared record delete confirmation for ordinary record deletion. Organization-level permanent deletion may use stronger typed confirmation. A surface contributes its object label and consequence details; it does not rewrite the common destructive grammar.

## Buttons and controls

`frontend/components/ui` owns control shape and behavior.

- Use `Button` with the established context size tier for page actions, dialog/sheet footers, toolbars, or inline row/card actions.
- Menu-looking actions expose the shared menu/chevron behavior; ellipsis overflow is the glyph-level exception.
- `IconButton` is the standard icon-only action so accessible name and tooltip remain coupled.
- `SplitButton` is the shared primary-action-plus-menu capsule.
- `SegmentedControl` is the standard mode/view switch.
- Prefer one primary action per view region.

The ad-hoc-button lint baseline represents legacy debt. It may shrink; do not raise it to accommodate new ad-hoc controls. Semantic rules that a static scanner cannot prove remain review responsibilities.

## Validation

Create and edit surfaces validate fixable field problems inline. Controls expose invalid/described-by state and render the field error next to the field. Toasts report request outcomes; they are not a substitute for field validation the user can fix in place.

## Motion and color

Motion follows `docs/frontend/MOTION.md`. Repeated chrome such as `PageShell` and `PageHeader` does not animate independently when page arrival already owns the transition.

Use semantic design tokens for color. Relationship warmth, charts, success/warning/destructive, ranking, and other semantic palettes communicate meaning consistently. Never rely on color alone.

## New page/redesign checklist

- Uses the correct shell; no accidental page-level width cap or duplicate gutter/rhythm.
- Loading shell matches the page.
- Uses `PageHeader` or a justified domain identity header.
- Loading, empty, error, and permission states are explicit and shared.
- Cards are semantic rather than default wrappers.
- Shared buttons/controls/composers are reused; no parallel primitive was invented.
- Density is comfortable unless the user entered an explicit bounded work mode.
- Overlay/page choice matches operation complexity.
- Motion follows the motion contract and reduced-motion behavior is verified.
- Colors/spacing use tokens and the surface works in light/dark.
- New copy uses canonical vocabulary and EN/JA keys.
- Representative desktop and mobile behavior is browser-verified.
