# Frontend test harness

The frontend has two test layers, both living in `frontend/`:

- **Unit tests** — [vitest](https://vitest.dev), `frontend/test/unit/`, covering pure logic (analytics bucketing, URL list-state helpers, formatters/parsers, segment validation, shortcut normalization, locale resolution). Node environment, no DOM, no snapshots — behavioral assertions only.
- **E2E tests** — [`@playwright/test`](https://playwright.dev), `frontend/test/e2e/`, driving eight critical flows through a real browser against a running full stack.

## Running locally

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

First run only: `node_modules/.bin/playwright install chromium`.

## How auth bootstrap works

There are no seeded credentials. The `setup` project (`frontend/test/e2e/global.setup.ts`) provisions an isolated tenant per run:

1. `POST /api/auth/register` with a unique per-run username. Under the dev profile this single call **registers, logs in, creates a default workspace, and marks the email verified**, returning `JSESSIONID` (HttpOnly session) and `connex_workspace` (tenant selector) cookies.
2. The API request context's cookies are persisted as Playwright **storage state** (`test/e2e/.artifacts/storage-state.json`, gitignored); every browser context in the `chromium` project starts from it — no UI login per test.
3. Seed data (a company, four contacts, a pipeline/stage, three deals) is created through the API. Writes need the CSRF token from `GET /api/auth/csrf` (register/login are exempt) and are pinned to the tenant with `X-Workspace-Id`.
4. Seeded ids/names go into `test/e2e/.artifacts/run.json`, which specs read via `test/e2e/support/fixtures.ts`.

Because every run registers a **fresh user and workspace**, runs are tenant-isolated, rerunnable, and safe to execute in parallel against a shared dev database. Throwaway users accumulate in the dev DB; that is accepted (tenant-isolated).

## The eight flows

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

Known scope cut: the notifications flow asserts the inbox/read-state surface but does not exercise *mark as read on a real notification* — generating one deterministically requires a second workspace member (mention flow), which is deferred until the volume-seeder workstream lands. Documented here so nobody mistakes it for coverage.

## Flake policy

- **No sleeps.** Waits are Playwright auto-retrying assertions (`toBeVisible`, `toHaveURL`, …). Animations are neutralized with `reducedMotion: "reduce"`; locale/timezone are pinned (`en-US`/UTC).
- Specs only touch **records seeded for that spec** (distinct contacts per flow), so the default 2 workers never race each other.
- CI runs with `retries: 2` and uploads **traces + screenshots + the HTML report** on failure; locally retries are 0 so flake is visible, not hidden.
- A test that flakes repeatedly gets fixed or quarantined with `test.fixme()` plus a linked issue — never a lengthened timeout as a "fix", and never deleted silently.
- The CI job (`Frontend — unit & e2e` in `.github/workflows/ci.yml`) is **not a required check** until it has run green for a week (#853); after that it graduates into branch protection.
