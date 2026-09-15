import { default as handler } from "./.open-next/worker.js";

export { SignupLimiter } from "./signup-limiter";

/**
 * Worker entrypoint.
 *
 * OpenNext's generated worker exports only a fetch handler, and a Durable Object class has to be
 * exported from the same script as the binding that names it, so this wraps the generated handler.
 *
 * `www.` hosts are a redirect-only alias. Every request to one is answered with a 308 to the apex
 * before any application code runs, including `/api/*`, so the alias never reaches the signup
 * limiter or Resend and needs no hostname-scoped edge rules of its own.
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
