import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { test as base } from '@playwright/test';

/** Runs the shipped server-rendered capture surface against a capability-controlled stub API. */
export const test = base.extend<{ captureEnabled: boolean }, { captureAppURL: string }>({
    captureEnabled: [true, { option: true }],
    captureAppURL: [async ({}, provide) => {
        const directory = path.resolve(__dirname, '../../..');
        const distDir = process.env.NEXT_DIST_DIR ?? '.next';
        if (!existsSync(path.join(directory, distDir, 'BUILD_ID'))) {
            throw new Error(`Connected-capture e2e tests require a production frontend build (${distDir}/BUILD_ID missing). Run pnpm build in frontend/ first.`);
        }
        const child = spawn(process.execPath, ['--experimental-strip-types', path.join(__dirname, 'capture-app.ts')], {
            cwd: directory,
            env: {
                ...process.env,
                NODE_ENV: 'production',
                NEXT_DIST_DIR: distDir,
            },
            stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
        });
        let output = '';
        let startupTimer: ReturnType<typeof setTimeout> | undefined;
        child.stdout?.on('data', (chunk: Buffer) => { output += chunk.toString(); });
        child.stderr?.on('data', (chunk: Buffer) => { output += chunk.toString(); });
        try {
            const baseURL = await new Promise<string>((resolve, reject) => {
                startupTimer = setTimeout(() => reject(new Error(`Capture app startup timed out: ${output}`)), 110_000);
                child.once('error', reject);
                child.once('exit', (code) => reject(new Error(`Capture app exited (${code}): ${output}`)));
                child.once('message', (message: unknown) => {
                    if (typeof message === 'object' && message !== null
                        && 'baseURL' in message && typeof message.baseURL === 'string') {
                        resolve(message.baseURL);
                    } else {
                        reject(new Error('Capture app did not return its address'));
                    }
                });
            });
            clearTimeout(startupTimer);
            await provide(baseURL);
        } finally {
            clearTimeout(startupTimer);
            if (child.exitCode === null && child.signalCode === null) {
                const exited = once(child, 'exit');
                child.kill('SIGTERM');
                await exited;
            }
        }
    }, { scope: 'worker', timeout: 120_000 }],
    baseURL: async ({ captureAppURL }, provide) => { await provide(captureAppURL); },
    storageState: async ({ captureAppURL, captureEnabled }, provide) => {
        await provide({
            cookies: ['JSESSIONID=capture-e2e', 'connex_workspace=1', `e2e_capture=${captureEnabled ? 'on' : 'off'}`]
                .map((cookie) => {
                    const [name, value] = cookie.split('=');
                    return {
                        name, value, domain: new URL(captureAppURL).hostname, path: '/',
                        expires: -1, httpOnly: false, secure: false, sameSite: 'Lax' as const,
                    };
                }),
            origins: [],
        });
    },
});
