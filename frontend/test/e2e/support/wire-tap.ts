import {
    createServer,
    request as forwardRequest,
    type IncomingHttpHeaders,
    type IncomingMessage,
    type OutgoingHttpHeaders,
    type ServerResponse,
} from "node:http";
import type { Socket } from "node:net";
import { brotliDecompressSync, gunzipSync, inflateSync } from "node:zlib";

/** One request the tap relayed to the app, and the response the app answered it with. */
export type WireExchange = {
    method: string;
    url: URL;
    requestHeaders: IncomingHttpHeaders;
    status: number;
    body: string;
};

/** A running tap: the proxy a browser context points at, and what has crossed it so far. */
export type WireTap = {
    /** The `server` value for a Playwright context's `proxy` option. */
    proxyServer: string;
    /** Every exchange with the app origin completed so far, oldest first. */
    exchanges: () => readonly WireExchange[];
    /** Stops the proxy and drops any connection still open. */
    close: () => Promise<void>;
};

/** What the tap serves and where it relays. */
export type WireTapOptions = {
    /** Origin of the app under test; the only origin the tap relays to. */
    app: string;
    /** HTML the tap answers itself for each foreign origin, so those origins need no DNS or server. */
    pages: Readonly<Record<string, string>>;
};

const HOP_BY_HOP_HEADERS = new Set([
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "proxy-connection",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
]);

function endToEndHeaders(headers: IncomingHttpHeaders): OutgoingHttpHeaders {
    const forwarded: OutgoingHttpHeaders = {};
    for (const [name, value] of Object.entries(headers)) {
        if (value !== undefined && !HOP_BY_HOP_HEADERS.has(name)) forwarded[name] = value;
    }
    return forwarded;
}

function decodedBody(body: Buffer, encoding: string | string[] | undefined): string {
    const coding = typeof encoding === "string" ? encoding.trim().toLowerCase() : "";
    if (coding === "" || coding === "identity") return body.toString("utf8");
    if (coding === "gzip") return gunzipSync(body).toString("utf8");
    if (coding === "br") return brotliDecompressSync(body).toString("utf8");
    if (coding === "deflate") return inflateSync(body).toString("utf8");
    return `[wire tap cannot decode content-encoding ${coding}]`;
}

function refuse(response: ServerResponse): void {
    response.writeHead(502, { "content-type": "text/plain; charset=utf-8" });
    response.end("The wire tap relays only to the app under test");
}

function relay(
    request: IncomingMessage,
    response: ServerResponse,
    target: URL,
    exchanges: WireExchange[],
): void {
    const method = request.method ?? "GET";
    const upstream = forwardRequest(
        {
            protocol: target.protocol,
            hostname: target.hostname,
            port: target.port,
            method,
            path: `${target.pathname}${target.search}`,
            headers: endToEndHeaders(request.headers),
        },
        (answer) => {
            const chunks: Buffer[] = [];
            answer.on("data", (chunk: Buffer) => chunks.push(chunk));
            answer.on("end", () => {
                exchanges.push({
                    method,
                    url: target,
                    requestHeaders: request.headers,
                    status: answer.statusCode ?? 0,
                    body: decodedBody(Buffer.concat(chunks), answer.headers["content-encoding"]),
                });
            });
            response.writeHead(answer.statusCode ?? 502, endToEndHeaders(answer.headers));
            answer.pipe(response);
        },
    );
    upstream.on("error", () => {
        if (!response.headersSent) response.writeHead(502);
        response.end();
    });
    request.pipe(upstream);
}

/**
 * Starts a plaintext forward proxy that sits between a browser context and the app and records each
 * exchange exactly as it crossed the wire.
 *
 * A browser deliberately hides a cross-origin opaque response from the page that caused it, and
 * Chromium's Opaque Response Blocking discards the body of a `no-cors` or `<img>` response that is
 * not an image before Playwright or DevTools can read it. The only vantage point that still sees
 * the status and body the server returned is the connection itself, which is where this sits.
 *
 * Foreign origins are served from `pages` by the tap itself. Chromium refuses a page fulfilled
 * through Playwright's `route` any request to a loopback app before it is sent, consistent with
 * Local Network Access treating the fulfilled page as public. Behind this loopback proxy both ends
 * share one address space, so the browser sends the requests and computes `Sec-Fetch-*` exactly as
 * for a real site.
 *
 * Request headers are relayed unchanged apart from hop-by-hop headers, so what reaches the app is
 * what the browser sent. Point a context at it with `proxy: { server, bypass: "<-loopback>" }`:
 * Chromium bypasses every proxy for loopback hosts unless told not to.
 *
 * @param options the app origin to relay to and the foreign pages to serve
 * @returns the running tap; close it when the test is done
 */
export async function startWireTap(options: WireTapOptions): Promise<WireTap> {
    const app = new URL(options.app);
    if (app.protocol !== "http:") {
        throw new Error(`The wire tap reads plaintext HTTP and cannot observe ${app.origin}`);
    }
    const exchanges: WireExchange[] = [];
    const sockets = new Set<Socket>();
    const server = createServer((request, response) => {
        let target: URL;
        try {
            target = new URL(request.url ?? "");
        } catch {
            refuse(response);
            return;
        }
        const page = options.pages[target.origin];
        if (page !== undefined) {
            response.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" });
            response.end(page);
            return;
        }
        if (target.origin !== app.origin) {
            refuse(response);
            return;
        }
        relay(request, response, target, exchanges);
    });
    server.on("connection", (socket) => {
        sockets.add(socket);
        socket.once("close", () => sockets.delete(socket));
    });
    server.on("connect", (_request, socket) => socket.destroy());
    await new Promise<void>((resolve, reject) => {
        server.once("error", reject);
        server.listen(0, "127.0.0.1", resolve);
    });
    const address = server.address();
    if (address === null || typeof address === "string") {
        server.close();
        throw new Error("The wire tap did not bind to a TCP port");
    }
    return {
        proxyServer: `http://127.0.0.1:${address.port}`,
        exchanges: () => [...exchanges],
        close: async () => {
            for (const socket of sockets) socket.destroy();
            if (!server.listening) return;
            await new Promise<void>((resolve, reject) => {
                server.close((error) => (error ? reject(error) : resolve()));
            });
        },
    };
}
