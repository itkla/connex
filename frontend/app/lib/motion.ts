import type { Transition } from 'motion/react';

/**
 * Shared motion presets — the design system's motion vocabulary. Prefer these named springs and easings
 * over ad-hoc `{ type: 'spring', ... }` literals so motion feels consistent across surfaces. Always pair
 * with a reduced-motion fallback (use {@link instant} when `useReducedMotion()` is true).
 */

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

/** An instant, motion-free transition for `prefers-reduced-motion` code paths. */
export const instant: Transition = { duration: 0 };
