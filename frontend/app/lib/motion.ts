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
 */

/** Feedback speed (150ms): hover, toggle, press, menu pop-in. Mirrors `--motion-micro`. */
export const durationMicro = 0.15;

/** Surface speed (250ms): overlays, entrances, surfaces taking or releasing focus. Mirrors `--motion-standard`. */
export const durationStandard = 0.25;

/** The rare, memorable speed (400ms): page/section arrival, celebration. Mirrors `--motion-expressive`. */
export const durationExpressive = 0.4;

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
