import { default as handler } from "./.open-next/worker.js";

export { SignupLimiter } from "./signup-limiter";

/**
 * Worker entrypoint.
 *
 * OpenNext's generated worker exports only a fetch handler, and a Durable Object class has to be
 * exported from the same script as the binding that names it, so this wraps the generated handler.
 */
export default {
    fetch: handler.fetch,
} satisfies ExportedHandler<CloudflareEnv>;
