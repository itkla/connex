# Frontend test harness

The frontend has two test layers, both living in `frontend/`:

- **Unit tests** — [vitest](https://vitest.dev), `frontend/test/unit/`, covering pure logic (analytics bucketing, URL list-state helpers, formatters/parsers, segment validation, shortcut normalization, locale resolution) plus the toolchain's declared Node floor. Node environment, no DOM, no snapshots — behavioral assertions only.
- **E2E tests** — [`@playwright/test`](https://playwright.dev), `frontend/test/e2e/`, driving ten critical flows through a real browser against a running full stack, at desktop and phone viewports and in English or Japanese.

## Running locally

**Node floor:** `^22.13.0 || >=24.0.0`, declared in `frontend/package.json` `engines` — the intersection of pinned pnpm 11.9's range (`>=22.13`), the locked Vite 8/rolldown range (`^20.19.0 || >=22.12.0`), Vitest 4's (`^20.0.0 || ^22.0.0 || >=24.0.0`), and the lint toolchain's `eslint-visitor-keys` range (`^20.19.0 || ^22.13.0 || >=24`). Node 20.x, 21.x, 22.0–22.12 and 23.x fall outside it. The `engines` field documents the floor; `frontend/test/unit/engines.test.ts` enforces the pinned package-manager version and fails when a dependency bump moves the locked floors away from the declared range. When it does, `frontend/package.json`, [`AGENTS.md`](../AGENTS.md) and this file have to be updated together.

```bash
cd frontend

# Unit tests (fast, no stack needed)
pnpm test              # or: node_modules/.bin/vitest run
pnpm test:watch

# E2E (needs the stack below)
pnpm e2e               # or: node_modules/.bin/playwright test
node_modules/.bin/playwright test --ui        # headed debugging
node_modules/.bin/playwright show-report      # inspect the last failure's traces
```

> In this dev environment `pnpm test`/`pnpm e2e` can abort with `ERR_PNPM_IGNORED_BUILDS` before running; call the `node_modules/.bin/*` binaries directly instead.

The e2e suite expects the frontend at `http://localhost:3000` (override with `E2E_BASE_URL`) with its `/api` proxy reaching a backend on `:8080`:

1. MySQL — `sudo docker compose -f backend/docker-compose.yml up -d db` (or any MySQL 8.4 with a fresh schema).
2. Backend — from `backend/`: `SPRING_PROFILES_ACTIVE=dev CONNEX_DB_URL=... CONNEX_DB_USERNAME=... CONNEX_DB_PASSWORD=... bash gradlew bootRun`. The **dev profile is required**: it disables the `Secure` cookie flags (so cookies work over plain-HTTP localhost) and allows self-service workspace creation, which the auth bootstrap depends on. A fresh schema takes several minutes of Flyway migrations before `/api/version` responds.
3. Frontend — from `frontend/`: `pnpm dev` (or `next build && next start`).

First run only: `node_modules/.bin/playwright install chromium` — Chromium is the only browser the suite needs, desktop and phone projects alike.

Useful selections: `playwright test --project=mobile-chromium` (phone viewport only), `playwright test --grep @mobile`, `playwright test locale-ja.spec.ts`.

## How auth bootstrap works

There are no seeded credentials. The `setup` project (`frontend/test/e2e/global.setup.ts`) provisions an isolated tenant per run:

1. `POST /api/auth/register` with a unique per-run username. Under the dev profile this single call **registers, logs in, creates a default workspace, and marks the email verified**, returning `JSESSIONID` (HttpOnly session) and `connex_workspace` (tenant selector) cookies.
2. The API request context's cookies are persisted as Playwright **storage state** (`test/e2e/.artifacts/storage-state.json`, gitignored); every browser context in the `chromium` project starts from it — no UI login per test.
3. Seed data (a company, four contacts, a pipeline/stage, three deals) is created through the API. Writes need the CSRF token from `GET /api/auth/csrf` (register/login are exempt) and are pinned to the tenant with `X-Workspace-Id`.
4. Seeded ids/names go into `test/e2e/.artifacts/run.json`, which specs read via `test/e2e/support/fixtures.ts`.

Because every run registers a **fresh user and workspace**, runs are tenant-isolated, rerunnable, and safe to execute in parallel against a shared dev database. Throwaway users accumulate in the dev DB; that is accepted (tenant-isolated).

## Projects and viewports

`playwright.config.ts` defines three projects:

| Project | Device | Runs |
| --- | --- | --- |
| `setup` | — | `global.setup.ts` only; the other two depend on it and reuse its storage state |
| `chromium` | Desktop Chrome | every test **except** those tagged `@mobile-only` |
| `mobile-chromium` | Pixel 7 (412×839, `isMobile`, `hasTouch`, Chromium) | only tests tagged `@mobile` |

Opt in by putting a tag in the **test title**:

- **no tag** — desktop only. This is the default, so none of the pre-existing specs were duplicated onto the phone viewport.
- **`@mobile`** — runs on desktop *and* the phone viewport. Use it for a flow that must hold at both widths.
- **`@mobile-only`** — runs on the phone viewport *only*. Use it for surfaces that do not exist on desktop (the bottom bar, mobile drawers). `@mobile-only` contains `@mobile`, so one `grep` on the mobile project and one `grepInvert` on the desktop project express both rules.

The phone project is deliberately **Chromium, not Mobile Safari**: CI installs a single browser (`playwright install --with-deps chromium`), and adding WebKit would download and boot a second engine on every run of a required check for no coverage we can act on — Connex ships no WebKit-specific code paths. A Chromium device descriptor still gives the real mobile viewport, touch, and `isMobile` media behaviour that the responsive shell branches on.

## Running in Japanese

Locale is **not a URL segment**. `i18n/request.ts` resolves it server-side from the `NEXT_LOCALE` cookie (`i18n/config.ts`). A user's `locale` database column does not by itself drive the UI: the only thing that copies it into the cookie is `PreferenceActionsBridge`, a client effect that deliberately does nothing when the context already carries an explicit preference. So a Japanese run sets the cookie itself:

```ts
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

await useLocale(page, "ja");           // before the navigation you want translated
await page.goto("/auth/login");
await expect(page.getByRole("heading", { name: message("ja", "auth", "AuthLogin.title") })).toBeVisible();
```

`useLocale(target, locale)` accepts a `Page` or a `BrowserContext`. `message(locale, namespace, key)` reads `messages/<locale>/<namespace>.json`, so assertions track the shipped catalogue instead of a literal that keeps passing after the copy changes.

## Seeded identities (deterministic volume seeder)

`frontend-tests` runs the [volume seeder](VOLUME_SEEDER.md) against its fresh `connex_seed_e2e` schema **before** booting the backend — `SeederGuard` allows only a non-web one-shot process, so it can never run inside the serving app. The step tees the Gradle console into `test/e2e/.artifacts/seeder.log`.

Specs read identities from that log via `test/e2e/support/seed.ts`:

```ts
const persona = seedFixture().workspaces[0].japaneseUser;   // 佐藤 美咲, locale ja, Asia/Tokyo
await page.getByLabel("Username or email").fill(persona.username);
await page.getByLabel("Password", { exact: true }).fill(seedFixture().password);
```

Usernames are `seed-<key>-w<N>-u<M>`, where `key` is a SplitMix64-derived hash of the seed and workspace index — not reproducible from `-PseederSeed` by string concatenation. The same `key` appears in the workspace slug that `SeedDataRunner` logs (`Seeder summary workspace=1 slug=seed-workspace-<key>-1 rowCounts={…}`), so the log is parsed rather than the hash re-implemented in TypeScript, keeping one derivation in Java. `parseSeedLog` is covered by `test/unit/seedLog.test.ts` and throws instead of returning an empty fixture.

`seedFixtureAvailable()` is false when the log is absent, and seeded specs `test.skip()` on it — a local stack you did not seed skips those tests instead of failing. In CI the seeder step runs unconditionally and a seeder failure fails the job, so the skip can never hide a broken seeder.

To seed a local stack, use a **disposable** schema (never `connex_pub`, never your dev database) and seed it before starting the backend:

```bash
cd backend
mkdir -p ../frontend/test/e2e/.artifacts
CONNEX_DB_URL='jdbc:mysql://127.0.0.1:3313/connex_seed_e2e?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&sslMode=DISABLED' \
CONNEX_DB_USERNAME=connexuser CONNEX_DB_PASSWORD=connexpass \
bash gradlew seedData -PseederProfile=small -PseederSeed=853 -PseederWorkspaces=1 -PseederAnchorDate=2026-01-15 \
  | tee ../frontend/test/e2e/.artifacts/seeder.log
```

Then boot the backend against that same schema. Every seeded user's password is `seeder-password`; treat any schema the seeder touched as compromised for authentication.

## The ten flows

| Spec | Flow |
| --- | --- |
| `auth.spec.ts` | protected-route redirect, UI register → dashboard, login → dashboard, wrong-password error |
| `quick-create.spec.ts` | Quick Create launcher → new contact → visible in the records browser |
| `records-peek.spec.ts` | list row → peek drawer → full detail → browser back restores list context (`?q=`) |
| `record-edit.spec.ts` | inline table edit (Title) persists across reload |
| `activity-compose.spec.ts` | log an activity on a contact detail → appears in the timeline |
| `notifications.spec.ts` | inbox renders controls/read state; bell popover links to the inbox |
| `analytics.spec.ts` | range/granularity switching updates URL, pressed state, offered grains, and panels (#866 surface) |
| `search.spec.ts` | toolbar search finds a seeded contact and opens its record |
| `import-preflight.spec.ts` | CSV preview exposes exact and ambiguous duplicate-review decisions before commit |
| `archive-records.spec.ts` | contact/company archive visibility and restore round trips through the record browser |

Three further specs exist to keep the harness itself honest rather than to cover a product flow:

| Spec | Proves |
| --- | --- |
| `locale-ja.spec.ts` | the `NEXT_LOCALE` helper actually switches server-rendered copy, logged out and signed in, and that the same page stays English without it (one test tagged `@mobile`) |
| `mobile-shell.spec.ts` | the phone-viewport project really is mobile: the bottom bar and the sidebar toggle that `md:hidden` removes on desktop are both rendered (`@mobile-only`) |
| `seeded-persona.spec.ts` | the identities derived from the seeder log are real: the Japanese persona signs in with the seeded password |

Known scope cut: the notifications flow asserts the inbox/read-state surface but does not exercise *mark as read on a real notification* — generating one deterministically requires a second workspace member (mention flow), which is deferred until the volume-seeder workstream lands. Documented here so nobody mistakes it for coverage.

## Flake policy

- **No sleeps.** Waits are Playwright auto-retrying assertions (`toBeVisible`, `toHaveURL`, …). Animations are neutralized with `reducedMotion: "reduce"`; locale/timezone are pinned (`en-US`/UTC).
- Specs only touch **records seeded for that spec** (distinct contacts per flow), so the default 2 workers never race each other.
- CI runs with `retries: 2` and uploads **traces + screenshots + the HTML report** on failure; locally retries are 0 so flake is visible, not hidden.
- A test that flakes repeatedly gets fixed or quarantined with `test.fixme()` plus a linked issue — never a lengthened timeout as a "fix", and never deleted silently.
- The CI job (`Frontend — unit & e2e` in `.github/workflows/ci.yml`) is a **required check** on `main` (#853, graduated in 735d885a). A failure there blocks merges, so every step in that job has to stay deterministic.
