# Frontend Motion Contract

This is the authoritative implementation contract for motion in Connex. Product principles remain in `docs/PRODUCT.md`; shared values are declared in `frontend/app/globals.css` and mirrored for JavaScript in `frontend/app/lib/motion.ts`.

Read this before adding or materially changing motion.

## Principle

Motion responds; it never delays. Use motion to preserve continuity, acknowledge state change, and clarify spatial relationships. If removing an animation costs no meaning, prefer removing it over tuning it.

The first interaction may delight; the fiftieth must not irritate. Repeated operations stay quiet and fast.

## Timing scale

Use the existing named tokens rather than literal durations:

| Purpose | CSS token | Value | JS seconds | JS milliseconds |
|---|---|---:|---|---|
| feedback / anchored popup | `--motion-micro` | 150 ms | `durationMicro` | `durationMicroMs` |
| focus-taking surface / section reveal | `--motion-standard` | 250 ms | `durationStandard` | `durationStandardMs` |
| rare expressive arrival/completion | `--motion-expressive` | 400 ms | `durationExpressive` | `durationExpressiveMs` |

`motion/react` consumes seconds; Web Animations/style timing consumes milliseconds. Use the matching mirror.

Do not add literal `duration-150`, arbitrary `[220ms]`, `duration: 0.25`, `animationDuration={500}`, or another unnamed timing when a shared token applies. Do not add a fourth speed casually.

Page arrival may use expressive timing; in-page reveals use standard. Blocking interaction should not wait on expressive motion.

## Motion character

Use the existing curves/springs:

- `ease-hand` / `easeHand`: responsive/overshooting entrance character for interactions that answer the user's hand.
- `ease-calm` / `easeCalm`: decisive, non-overshooting state/surface settling.
- `--ease-out` / `easeOut`: neutral arrival.
- `springJiggle`, `springSnappy`, `springSmooth`: shared JS springs.
- `instant`: reduced/no-motion fallback.

Springs are preferred for interruptible hand-driven interaction; easing is appropriate for measured state/layout transitions. Overshoot belongs on entrances, not exits.

## Responsiveness

- Acknowledge input within roughly 100 ms through press/focus/pending state; do not wait for the network to admit a click happened.
- Motion accompanies a result; it does not run before the app decides the result.
- Blocking animation should remain around 300 ms or less. Expressive timing is reserved for non-blocking moments.
- Exits are normally faster than entrances: use micro for ordinary popup/dialog/drawer exits.
- A full-viewport translating sheet may exit at standard timing when micro would read as a jump cut. A surface that can be partial or full viewport switches based on its actual presentation.
- Motion is not a loading strategy. Use shape-matched skeletons for loading.

## Reduced motion

Every animated surface has a reduced-motion path.

- CSS animations: use the established `motion-reduce:animate-none!` pattern where state-selector specificity/source order requires it.
- CSS transitions: use `motion-reduce:transition-none`.
- Transform-only feedback: remove the movement under reduced motion while retaining useful color/opacity feedback.
- JS motion: use `useReducedMotion()` and the shared `instant`/non-moving path.

Reduced motion means removing position/scale/rotation/parallax/layout-spring movement, not merely running the same movement faster. Brief opacity/color transitions may remain when they improve comprehension.

Verify `prefers-reduced-motion: reduce` in a browser and confirm the surface still opens, closes, swaps state, and unmounts correctly.

## Continuity patterns

Prefer existing patterns rather than inventing a new motion language:

- Search ↔ palette: `frontend/app/components/GlobalSearch.tsx`.
- Quick Create content morph: `frontend/app/components/actions/QuickCreateLauncher.tsx`.
- Shared tab indicator: `frontend/components/ui/tabs.tsx`.
- Page arrival: `frontend/app/components/motion/Rise.tsx`.
- Overlay handoff lifecycle: `frontend/lib/overlay-lifecycle.ts` and `ResponsiveDialog`.

When one surface can morph its contents, keep one surface alive instead of closing/opening two. When two distinct drawers must hand off, sequence the first close before opening the second; do not animate two shared-backdrop drawers simultaneously.

## Enforcement

`frontend/test/unit/motionDurations.test.ts` and `frontend/lint/motionDurations.mjs` enforce named timing usage against `frontend/lint/motion-duration-baseline.json`.

The baseline is debt, not permission:

- Counts may shrink.
- Remove a file when its debt reaches zero.
- Do not increase the baseline to accommodate newly introduced literals.
- A high-water mark may rise only when the scanner itself is deliberately widened to detect previously invisible existing debt.
- Shared tokenized/system surfaces remain at zero.

When the scanner misses an idiom, improve the scanner rather than documenting the loophole here.

## Review checklist

Every material motion change receives one focused animation review against this contract. The active environment may provide a named animation-review skill; the requirement is the independent charter, not a permanently hard-coded tool name.

- Timing uses a named shared token/preset.
- Character matches the interaction: hand-driven vs calm state transition.
- Exit does not overshoot and is no slower than necessary.
- Input feedback does not wait on the network.
- Repeated operations remain restrained.
- Reduced-motion behavior is implemented and browser-verified.
- No animation is being used to disguise loading.
- The relevant motion lint/unit gate passes.
