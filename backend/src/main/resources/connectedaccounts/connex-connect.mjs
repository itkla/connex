#!/usr/bin/env node

// Zero-dependency Node 18+ helper for managed loopback authorization.

import { spawn } from 'node:child_process';
import http from 'node:http';
import { createInterface } from 'node:readline/promises';

const TIMEOUT_MILLIS = 10 * 60 * 1000;
const AUTHORIZE_HOSTS = new Set([
  'accounts.google.com',
  'login.microsoftonline.com',
]);
const PLAINTEXT_INSTANCE_HOSTS = new Set([
  'localhost',
  '127.0.0.1',
  '[::1]',
]);

let activeServer;

function argumentsFrom(commandLine) {
  let instance;
  let pairingCode;
  let yes = false;
  for (let index = 0; index < commandLine.length; index += 1) {
    const argument = commandLine[index];
    if (argument === '--instance' && index + 1 < commandLine.length) {
      instance = commandLine[index + 1];
      index += 1;
    } else if (argument === '--pairing-code' && index + 1 < commandLine.length) {
      pairingCode = commandLine[index + 1];
      index += 1;
    } else if (argument === '--yes') {
      yes = true;
    } else {
      throw new Error('Usage: node connex-connect.mjs --instance <url> [--pairing-code <code>] [--yes]');
    }
  }
  if (!instance) {
    throw new Error('Usage: node connex-connect.mjs --instance <url> [--pairing-code <code>] [--yes]');
  }
  const instanceUrl = new URL(instance);
  const secureTransport = instanceUrl.protocol === 'https:'
    || (instanceUrl.protocol === 'http:'
      && PLAINTEXT_INSTANCE_HOSTS.has(instanceUrl.hostname));
  if (!secureTransport
      || instanceUrl.username
      || instanceUrl.password
      || instanceUrl.pathname !== '/'
      || instanceUrl.search
      || instanceUrl.hash) {
    throw new Error('Instance must be an HTTPS origin, or an HTTP loopback origin for local use');
  }
  return { instanceUrl, pairingCode, yes };
}

function endpoint(instanceUrl, path) {
  return new URL(path, instanceUrl.origin).toString();
}

async function postJson(url, body, signal) {
  const response = await fetch(url, {
    method: 'POST',
    redirect: 'error',
    signal,
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw new Error(`The local Connex instance rejected the request with HTTP ${response.status}`);
  }
  try {
    return await response.json();
  } catch {
    throw new Error('The local Connex instance returned an invalid response');
  }
}

function callbackServer() {
  let acceptCallback;
  let rejectCallback;
  const callback = new Promise((resolve, reject) => {
    acceptCallback = resolve;
    rejectCallback = reject;
  });
  let handled = false;
  let expectedState;
  const server = http.createServer((request, response) => {
    const requestTarget = request.url ?? '';
    const exactCallbackTarget = requestTarget === '/callback'
      || requestTarget.startsWith('/callback?');
    if (request.method !== 'GET' || !exactCallbackTarget) {
      response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      response.end('Not found');
      return;
    }
    let requestUrl;
    try {
      requestUrl = new URL(requestTarget, 'http://127.0.0.1');
    } catch {
      response.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
      response.end('Bad request');
      return;
    }
    const state = requestUrl.searchParams.get('state');
    if (expectedState === undefined || state !== expectedState) {
      response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      response.end('Not found');
      return;
    }
    if (handled) {
      response.writeHead(409, { 'Content-Type': 'text/plain; charset=utf-8' });
      response.end('Authorization callback already received');
      return;
    }
    handled = true;
    response.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
    });
    response.end('<!doctype html><html><head><meta charset="utf-8"><title>Authorization received</title></head><body><p>Authorization received. You can close this tab.</p></body></html>');
    const code = requestUrl.searchParams.get('code');
    if (requestUrl.searchParams.has('error') || !code || !state) {
      rejectCallback(new Error('Provider authorization was not completed'));
      return;
    }
    acceptCallback({ code, state });
  });
  server.on('error', () => rejectCallback(new Error('Could not start the loopback callback server')));
  return {
    server,
    callback,
    expectState(state) {
      expectedState = state;
    },
  };
}

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once('error', () => reject(new Error('Could not bind the loopback callback server')));
    server.listen(0, '127.0.0.1', () => resolve());
  });
}

function close(server) {
  return new Promise((resolve) => {
    if (!server.listening) {
      resolve();
      return;
    }
    server.close(() => resolve());
    if (typeof server.closeAllConnections === 'function') {
      server.closeAllConnections();
    }
  });
}

function openBrowser(authorizeUrl) {
  let child;
  try {
    if (process.platform === 'darwin') {
      child = spawn('open', [authorizeUrl], { detached: true, stdio: 'ignore' });
    } else if (process.platform === 'win32') {
      // Deliberately not cmd.exe: it expands %VAR% even inside quotes, and every authorize URL
      // carries percent-encoded fields (redirect_uri=http%3A%2F%2F...), which cmd would mangle.
      // rundll32 receives the URL as a single argv entry, with no shell parsing.
      child = spawn(
        'rundll32.exe',
        ['url.dll,FileProtocolHandler', authorizeUrl],
        { detached: true, stdio: 'ignore', windowsHide: true },
      );
    } else {
      child = spawn('xdg-open', [authorizeUrl], { detached: true, stdio: 'ignore' });
    }
    child.on('error', () => {});
    child.unref();
  } catch {
    return;
  }
}

async function run(signal) {
  const { instanceUrl, pairingCode: argumentPairingCode, yes } =
    argumentsFrom(process.argv.slice(2));
  const terminal = !argumentPairingCode || !yes
    ? createInterface({ input: process.stdin, output: process.stdout })
    : undefined;
  const callbackHandler = callbackServer();
  const { server, callback } = callbackHandler;
  let prepared;
  let authorizeUrl;
  try {
    const pairingCode = argumentPairingCode
      ?? (await terminal.question('Pairing code: ', { signal })).trim();
    if (!pairingCode) {
      throw new Error('Pairing code is required');
    }
    activeServer = server;
    await listen(server);
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Could not determine the loopback callback port');
    }
    const redirectUri = `http://127.0.0.1:${address.port}/callback`;
    prepared = await postJson(
      endpoint(instanceUrl, '/api/account/connections/native/prepare'),
      { pairingCode, redirectUri },
      signal,
    );
    if (typeof prepared.authorizeUrl !== 'string'
        || typeof prepared.handoffTicket !== 'string'
        || typeof prepared.accountLabel !== 'string') {
      throw new Error('The local Connex instance returned an invalid prepare response');
    }
    authorizeUrl = new URL(prepared.authorizeUrl);
    if (authorizeUrl.protocol !== 'https:' || !AUTHORIZE_HOSTS.has(authorizeUrl.hostname)) {
      throw new Error('The local Connex instance returned an unexpected provider authorization URL');
    }
    const expectedState = authorizeUrl.searchParams.get('state');
    if (!expectedState) {
      throw new Error('The local Connex instance returned an invalid provider authorization URL');
    }
    callbackHandler.expectState(expectedState);
    const accountLabel = prepared.accountLabel
      .replace(/[\u0000-\u001f\u007f-\u009f]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
    if (!accountLabel) {
      throw new Error('The local Connex instance returned an invalid account label');
    }
    console.log(`This will connect a mailbox to the Connex account: ${accountLabel}`);
    console.log('If that is not your own account, press Ctrl-C now.');
    if (!yes) {
      const answer = (await terminal.question('Continue? [y/N] ', { signal }))
        .trim()
        .toLowerCase();
      if (answer !== 'y' && answer !== 'yes') {
        throw new Error('Authorization cancelled');
      }
    }
  } finally {
    terminal?.close();
  }
  console.log('Open this authorization URL if a browser does not open automatically:');
  console.log(authorizeUrl.toString());
  openBrowser(authorizeUrl.toString());
  const providerCallback = await callback;
  await close(server);
  const completed = await postJson(
    endpoint(instanceUrl, '/api/account/connections/native/complete'),
    {
      handoffTicket: prepared.handoffTicket,
      code: providerCallback.code,
      state: providerCallback.state,
    },
    signal,
  );
  if (completed.status !== 'connected') {
    throw new Error('The local Connex instance did not confirm the connection');
  }
  console.log('Connected successfully.');
}

let timeout;
let timedOut = false;
const deadlineController = new AbortController();
try {
  await Promise.race([
    run(deadlineController.signal),
    new Promise((_, reject) => {
      timeout = setTimeout(
        () => {
          timedOut = true;
          deadlineController.abort();
          reject(new Error('Authorization timed out after 10 minutes'));
        },
        TIMEOUT_MILLIS,
      );
    }),
  ]);
  clearTimeout(timeout);
} catch (error) {
  clearTimeout(timeout);
  if (activeServer) {
    await close(activeServer);
  }
  const message = timedOut
    ? 'Authorization timed out after 10 minutes'
    : (error instanceof Error ? error.message : 'Authorization failed');
  console.error(message);
  process.exitCode = 1;
}
