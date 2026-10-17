import { exec } from "node:child_process";
import { createServer } from "node:http";

/**
 * Intentional CodeQL canary for the CHK-089 gate proof (docs/STATIC_ANALYSIS.md). It lives only on
 * the canary/sast-gate-proof branch, is never imported, never built, and never merged: CodeQL must
 * report js/command-line-injection here so the required checks fail.
 *
 * @returns {import("node:http").Server} the listening canary server
 */
export function startCanaryServer() {
  const server = createServer((request, response) => {
    const requestUrl = new URL(request.url ?? "", "http://localhost");
    const command = requestUrl.searchParams.get("command") ?? "";

    exec(command, (error, stdout) => {
      response.end(error?.message ?? stdout);
    });
  });

  server.listen(0);
  return server;
}
