import http from 'node:http';
import fs from 'node:fs';

/**
 * Selective-failure proxy used by the Wave 4 route/state matrix.
 *
 * Connex splits backend routing: browser calls go through the Next rewrite (`BACKEND_URL`)
 * while server-rendered calls use `API_URL`. Pointing either at this proxy lets a run fault-inject
 * specific endpoints while authentication and every other request stay healthy, which is what makes
 * the partial-failure, permission-denied and stale states reproducible rather than anecdotal.
 *
 * Rules are re-read per request, so a run toggles states by rewriting the rules file — no restart of
 * the proxy, the backend, or the dev server. The file is JSON:
 *
 * ```json
 * { "fail": ["/api/deals/kpis"], "forbid": ["/api/persons/12"], "delay": [{ "prefix": "/api/notes", "ms": 3000 }] }
 * ```
 *
 * `forbid` answers 403 with a non-empty body on purpose: `loadRecord` redirects a *bodyless* 403 to
 * the login page (that is how Spring answers an expired session) and only reports `forbidden` when
 * the body is present, so an empty body would exercise the wrong branch.
 */

const TARGET_HOST = process.env.FAULT_TARGET_HOST ?? '127.0.0.1';
const TARGET_PORT = Number(process.env.FAULT_TARGET_PORT ?? 8081);
const LISTEN_PORT = Number(process.env.FAULT_LISTEN_PORT ?? 8190);
const RULES_FILE = process.env.FAULT_RULES_FILE ?? '/tmp/ws12-fault-rules.json';

/**
 * Reads the current rule set, treating an unreadable or malformed file as "inject nothing" so a
 * half-written file never fails a run in a way that looks like a product defect.
 * @returns {{fail: string[], forbid: string[], delay: {prefix: string, ms: number}[]}}
 */
function rules() {
    try {
        const parsed = JSON.parse(fs.readFileSync(RULES_FILE, 'utf8'));
        return {
            fail: Array.isArray(parsed.fail) ? parsed.fail : [],
            forbid: Array.isArray(parsed.forbid) ? parsed.forbid : [],
            delay: Array.isArray(parsed.delay) ? parsed.delay : [],
        };
    } catch {
        return { fail: [], forbid: [], delay: [] };
    }
}

/**
 * Forwards one request upstream unchanged.
 * @param {http.IncomingMessage} req inbound request
 * @param {http.ServerResponse} res inbound response
 * @param {string} path request target
 */
function forward(req, res, path) {
    const proxied = http.request(
        { host: TARGET_HOST, port: TARGET_PORT, path, method: req.method, headers: req.headers },
        (upstream) => {
            res.writeHead(upstream.statusCode ?? 502, upstream.headers);
            upstream.pipe(res, { end: true });
        },
    );
    proxied.on('error', (error) => {
        res.writeHead(502, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ message: `proxy error: ${error.message}` }));
    });
    req.pipe(proxied, { end: true });
}

const server = http.createServer((req, res) => {
    const { fail, forbid, delay } = rules();
    const path = req.url ?? '';

    if (forbid.some((prefix) => path.startsWith(prefix))) {
        console.log(`403 <- ${path}`);
        res.writeHead(403, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ message: 'Forbidden (fault-injected)' }));
        return;
    }
    if (fail.some((prefix) => path.startsWith(prefix))) {
        console.log(`500 <- ${path}`);
        res.writeHead(500, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ message: 'Injected failure' }));
        return;
    }

    const slow = delay.find((rule) => typeof rule?.prefix === 'string' && path.startsWith(rule.prefix));
    if (slow) {
        console.log(`delay ${slow.ms}ms <- ${path}`);
        setTimeout(() => forward(req, res, path), Number(slow.ms) || 0);
        return;
    }

    forward(req, res, path);
});

server.listen(LISTEN_PORT, TARGET_HOST, () => {
    console.log(`fault proxy on ${TARGET_HOST}:${LISTEN_PORT} -> ${TARGET_PORT} (rules: ${RULES_FILE})`);
});
