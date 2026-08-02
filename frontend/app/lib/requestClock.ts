import { cache } from "react";

/**
 * The single timestamp for the current server request.
 *
 * Server components must not read `Date.now()` inline: an impure call in a render body can
 * produce a different value each time the tree is re-rendered, and the app shell publishes this
 * value to the client as the clock that hydration must agree with. Wrapping the read in React's
 * per-request `cache` makes it idempotent within a request — every caller in one render pass
 * sees the same instant — while still advancing on the next request.
 *
 * @returns the request's reference time in milliseconds since the epoch
 */
export const requestNow = cache((): number => Date.now());
