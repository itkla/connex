import type { Transition } from 'motion/react';

/**
 * Shared motion presets — the design system's motion vocabulary. Prefer these named springs, easings,
 * and durations over ad-hoc `{ type: 'spring', ... }` or numeric literals so motion feels consistent
 * across surfaces. Always pair with a reduced-motion fallback (use {@link instant} when
 * `useReducedMotion()` is true).
 *
 * The durations and easings here mirror the CSS tokens in `app/globals.css` (`--motion-micro`,
 * `--motion-standard`, `--motion-expressive`, `--motion-ease-hand`, `--motion-ease-calm`) so a
 * JS-driven surface and its CSS neighbor agree. Changing one without the other is drift; the
 * contract is `frontend/AGENTS.md` §14.
 *
 * Each speed ships in **two units, because the two JS animation APIs disagree**: `motion/react`
 * transitions take seconds, while the Web Animations API (`Element.animate`) and React style
 * objects take milliseconds. Handing one the other's number animates a surface a thousand times
 * too fast or too slow, so pick by the API you are calling, never by which name is shorter. The
 * millisecond values are canonical — the seconds are derived by exact division, which keeps them
 * free of the float error `0.15 * 1000` would introduce.
 */

/** Feedback speed for `Element.animate` and style objects (ms): hover, toggle, press, menu pop-in. */
export const durationMicroMs = 150;

/** Surface speed for `Element.animate` and style objects (ms): overlays and entrances. */
export const durationStandardMs = 250;

/** The rare, memorable speed for `Element.animate` and style objects (ms): page arrival, celebration. */
export const durationExpressiveMs = 400;

/** Feedback speed for `motion/react` (seconds): hover, toggle, press, menu pop-in. Mirrors `--motion-micro`. */
export const durationMicro = durationMicroMs / 1000;

/** Surface speed for `motion/react` (seconds): overlays, entrances, surfaces taking or releasing focus. Mirrors `--motion-standard`. */
export const durationStandard = durationStandardMs / 1000;

/** The rare, memorable speed for `motion/react` (seconds): page arrival, celebration. Mirrors `--motion-expressive`. */
export const durationExpressive = durationExpressiveMs / 1000;

/**
 * The house "jiggle": a lively, bouncy spring that overshoots then settles, giving elements a tactile,
 * boing-y pop. Use for playful entrances, hover reveals, staggered lists, and press feedback.
 */
export const springJiggle: Transition = { type: 'spring', stiffness: 300, damping: 15, mass: 0.85 };

/** A crisp, minimal-overshoot spring for morphs that should feel precise (position/layout moves). */
export const springSnappy: Transition = { type: 'spring', stiffness: 440, damping: 38, mass: 0.9 };

/** A smooth, gentle spring for slides and panels that should glide into place rather than pop. */
export const springSmooth: Transition = { type: 'spring', stiffness: 360, damping: 30, mass: 0.8 };

/** Strong ease-out cubic-bezier; the built-in CSS/JS easings are too weak to feel intentional. */
export const easeOut: [number, number, number, number] = [0.23, 1, 0.32, 1];

/**
 * Spring-flavored curve that overshoots then settles — the CSS-side stand-in for {@link springJiggle}
 * on anything answering the user's hand (press, hover pop, menu entrance). Mirrors `--motion-ease-hand`.
 */
export const easeHand: [number, number, number, number] = [0.34, 1.56, 0.64, 1];

/**
 * Calm, decisive deceleration with no overshoot, for state changes and surfaces that slide into place.
 * Mirrors `--motion-ease-calm`.
 */
export const easeCalm: [number, number, number, number] = [0.32, 0.72, 0, 1];

/** An instant, motion-free transition for `prefers-reduced-motion` code paths. */
export const instant: Transition = { duration: 0 };
