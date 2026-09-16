import { default as handler } from "./.open-next/worker.js";

export { SignupLimiter } from "./signup-limiter";

/**
 * Worker entrypoint.
 *
 * OpenNext's generated worker exports only a fetch handler, and a Durable Object class has to be
 * exported from the same script as the binding that names it, so this wraps the generated handler.
 *
 * `www.` hosts are a redirect-only alias. Every request that reaches the Worker on one, which is every
 * page and every `/api/*` call, is answered with a 308 to the apex before any application code runs,
 * so the alias never renders a page or reaches the signup limiter or Resend. Build output that matches
 * the assets binding is served before the Worker runs; those are the same public, immutable files the
 * apex serves, and routing every asset through the Worker would spend an invocation per file.
 */
export default {
    async fetch(request, env, ctx) {
        const url = new URL(request.url);
        if (url.hostname.startsWith("www.")) {
            url.hostname = url.hostname.slice("www.".length);
            url.protocol = "https:";
            return Response.redirect(url.toString(), 308);
        }
        return handler.fetch(request, env, ctx);
    },
} satisfies ExportedHandler<CloudflareEnv>;
