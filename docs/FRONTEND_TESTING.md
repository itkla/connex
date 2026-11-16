# Frontend test harness

The frontend has two test layers, both living in `frontend/`:

- **Unit tests** — [vitest](https://vitest.dev), `frontend/test/unit/`, covering pure logic (analytics bucketing, URL list-state helpers, formatters/parsers, segment validation, shortcut normalization, locale resolution) plus the toolchain's declared Node floor. Node environment, no DOM, no snapshots — behavioral assertions only.
- **E2E tests** — [`@playwright/test`](https://playwright.dev), `frontend/test/e2e/`, driving sixteen critical flows through a real browser against a running full stack. The harness provides project-isolated desktop/phone tenants and EN/JA locale control; individual specs opt into the additional quadrants they prove.

The frontend CI job uses Node 22. Reproduce runtime-sensitive failures on that major before relying
on a passing Node 24 run. Response-body readiness assertions should await an observable decision or
request completion: a fixed number of `Promise.resolve()` turns is not portable across Node's
response decoders. Keep delayed replies under test control so ordering assertions still exercise
an older response arriving after the newer decision.

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
2. Backend — from `backend/`: `SPRING_PROFILES_ACTIVE=dev CONNEX_DB_URL=... CONNEX_DB_USERNAME=... CONNEX_DB_PASSWORD=... bash gradlew bootRun`. The **dev profile is required**: it disables the `Secure` cookie flags (so cookies work over plain-HTTP localhost) and allows self-service workspace creation, which the auth bootstrap depends on. A fresh schema takes several minutes of Flyway migrations before `/api/version` responds. `ask-connex-trajectory.spec.ts` needs this boot extended with the `ai-scripted-provider` profile and four `CONNEX_AI_*` settings, the AI master switch among them; they are listed once, in [the browser-stack recipe](backend/AI_SECURITY.md#the-browser-stack-recipe), exactly as CI's backend boot step sets them.
3. Frontend — from `frontend/`: `pnpm dev` (or `next build && next start`).

First run only: `node_modules/.bin/playwright install chromium` — Chromium is the only browser the suite needs, desktop and phone projects alike.

Useful selections: `playwright test --project=mobile-chromium` (phone viewport only), `playwright test --grep @mobile`, `playwright test locale-ja.spec.ts`.

CSV contacts and interaction-history imports share `app/lib/import.ts`: decode bytes with fatal
UTF-8 first (including BOM), then fatal Shift-JIS using the browser's Windows-932 mapping.
Reject replacement characters before mapping or review. Both dialogs show the detected encoding
before commit. Successful fallback does not identify the source encoding: on Shift-JIS fallback,
also refuse a CJK unified ideograph flanked by ASCII letters or halfwidth katakana adjacent to
an ASCII letter as suspected Western mojibake. This heuristic can refuse genuine mixed-script
Shift-JIS and cannot identify every unsupported encoding; the refusal directs users to CSV UTF-8.
`test/unit/csvEncoding.test.ts` covers real bytes, including Windows-only ㈱, ordinary Japanese
business names, Japanese headers, and rejected Western input. CI runs
`test/e2e/csv-encoding.spec.ts` to exercise the upload flow and refusal without writes.

`connected-capture.spec.ts` can run without the backend or auth bootstrap. Its test-only fixture
starts the real Next.js app against a stub API; a per-test `e2e_capture` cookie switches the
server-rendered capability on or off. Browser route mocks own the capture responses and validate
mutation bodies. The fixture requires a production frontend build and fails before starting Next.js
when `BUILD_ID` is missing.

```bash
pnpm build
pnpm exec playwright test connected-capture.spec.ts --no-deps
```

The screenshot matrix is excluded from the default e2e configuration. Its chart-readiness regression
spec uses browser-rendered SVG fixtures and needs no stack or seeded identities:

```bash
pnpm exec playwright test --config=playwright.matrix.config.ts chart-readiness.spec.ts --project=matrix --no-deps
```

The full matrix runs with `pnpm exec playwright test --config=playwright.matrix.config.ts` and requires
the production frontend, seeded backend, and fault proxy described by `test/e2e/matrix/matrix.setup.ts`.
Set `PLAYWRIGHT_BROWSERS_PATH` when Chromium is installed outside Playwright's default cache.

## How auth bootstrap works

The core flow specs do not rely on pre-seeded credentials. The `setup-desktop` and `setup-mobile` projects (`frontend/test/e2e/global.setup.ts`) each provision an isolated tenant:

1. `POST /api/auth/register` with a unique per-run username. Under the dev profile this single call **registers, logs in, creates a default workspace, and marks the email verified**, returning `JSESSIONID` (HttpOnly session) and `connex_workspace` (tenant selector) cookies.
2. The API request context's cookies are persisted as project-scoped Playwright **storage state** (`test/e2e/.artifacts/{desktop,mobile}/storage-state.json`, gitignored), so desktop and phone runs never share an authenticated tenant.
3. Seed data (a company, five isolated flow contacts, separate EN/JA two-contact exact-match collisions, a pipeline/stage, and three deals) is created through the API. Writes need the CSRF token from `GET /api/auth/csrf` (register/login are exempt) and are pinned to the tenant with `X-Workspace-Id`.
4. Seeded ids/names go into `test/e2e/.artifacts/{desktop,mobile}/run.json`, which specs select from `testInfo.project.name` via `test/e2e/support/fixtures.ts`.

Because each browser project registers a **fresh user and workspace**, desktop and phone runs are tenant-isolated, rerunnable, and safe to execute in parallel against a shared dev database. Throwaway users accumulate in the dev DB; that is accepted (tenant-isolated).

The workspace seeder checks HTTP success before decoding a response and reports the path, status
and bounded response body on failure. Use `seed.post` for JSON object responses and
`seed.postEmpty` for void endpoints, including adding a contact to a deal. An empty successful
response must not be parsed as JSON; creation endpoints must still return their record object.

`campaign-metrics.spec.ts` registers a separate user and workspace for each case with empty storage
state. Its extra contacts must stay outside the shared project workspace: adding contacts there
can move another spec's seeded contact off its sorted first page, even when the new contacts have
unique names. The campaign flow asserts that its active workspace differs from the project fixture.

Playwright applies the active project's `use` options when a spec calls `browser.newContext()` through the built-in `browser` fixture. A spec that provisions a different identity must therefore pass `storageState: { cookies: [], origins: [] }`. Its `context.request` shares the context's cookies, so omitting that override would reuse the project JSESSIONID and replace the authenticated principal for every spec sharing that saved session.

## Projects and viewports

`playwright.config.ts` defines four projects:

| Project | Device | Runs |
| --- | --- | --- |
| `setup-desktop` | — | `global.setup.ts` only; provisions the desktop tenant and artifacts |
| `setup-mobile` | — | `global.setup.ts` only; provisions the phone tenant and artifacts |
| `chromium` | Desktop Chrome | every test **except** those tagged `@mobile-only`; depends on `setup-desktop` |
| `mobile-chromium` | Pixel 7 (412×839, `isMobile`, `hasTouch`, Chromium) | only tests tagged `@mobile`; depends on `setup-mobile` |

Opt in by putting a tag in the **test title**:

- **no tag** — desktop only. This is the default, so none of the pre-existing specs were duplicated onto the phone viewport.
- **`@mobile`** — runs on desktop *and* the phone viewport against separate project-owned tenants. Use it for a flow that must hold at both widths.
- **`@mobile-only`** — runs on the phone viewport *only*. Use it for surfaces that do not exist on desktop (the bottom bar, mobile drawers). `@mobile-only` contains `@mobile`, so one `grep` on the mobile project and one `grepInvert` on the desktop project express both rules.

The phone project is deliberately **Chromium, not Mobile Safari**: CI installs a single browser (`playwright install --with-deps chromium`), and adding WebKit would download and boot a second engine on every run of a required check for no coverage we can act on — Connex ships no WebKit-specific code paths. A Chromium device descriptor still gives the real mobile viewport, touch, and `isMobile` media behaviour that the responsive shell branches on.

## Running in Japanese

Locale is **not a URL segment**. `i18n/request.ts` honors an explicit next-intl locale first,
then a supported `NEXT_LOCALE` cookie, then the signed-in profile language, then
`Accept-Language`, and finally English. The profile and time zone share one cached request
resolver; an absent, unsupported or failed profile preference falls through to browser matching.
Requests without a session do not fetch a profile. Browser language matching accepts
English/Japanese regional tags, uses positive quality weights in
descending order, and preserves header order for ties. Unsupported ranges, wildcard ranges,
zero-quality ranges and malformed entries are ignored. An absent or unsupported cookie does not
prevent browser-language matching. Browser inference never writes a preference cookie.

API requests use the same effective UI language through `app/lib/apiLocale.ts`: the browser reads
the server-rendered `html[lang]`, and server fetches read next-intl's request locale. JSON, multipart,
public-link, CSV, report and upload requests share this choice, including the locale in AI/request
identity keys. Cookie changes take effect after the rendered language changes. Outside a rendered
page or next-intl request context, the client retains the cookie/English fallback.

The profile bootstrap explicitly carries the cookie/browser language available before the saved
profile is known. Workspace-zone fallback honors an explicit cookie before the profile language. These
preference reads bypass next-intl resolution to avoid recursively requesting their own unfinished
configuration; later data requests use the resolved request language. Neither bootstrap adds a
second profile lookup. `apiLocalePropagation.test.ts` exercises this loop with the real request
configuration and API helpers, including workspace fallback and concurrent request isolation.

The saved profile language applies to the first server render, including public routes without
`PreferenceActionsBridge`. To pin a Japanese run regardless of its saved profile or browser
language, set the cookie itself:

```ts
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

await useLocale(page, "ja");           // before the navigation you want translated
await page.goto("/auth/login");
await expect(page.getByRole("heading", { name: message("ja", "auth", "AuthLogin.title") })).toBeVisible();
```

`useLocale(target, locale)` accepts a `Page` or a `BrowserContext`. `message(locale, namespace, key)` reads `messages/<locale>/<namespace>.json`, so assertions track the shipped catalogue instead of a literal that keeps passing after the copy changes.

`public-entry-locale.spec.ts` instead starts with empty storage and a Japanese browser locale.
It covers sign-in, registration, recovery, email/passkey confirmation, signed-out invitation
redirects and unsubscribe entry on desktop and phone, plus explicit-cookie precedence,
authenticated English profiles with Japanese browsers, and unsupported-browser fallback.
The public-page cases also compare API `Accept-Language` with the rendered language for saved
English/Japanese profiles, explicit cookies and signed-out browser inference.
`publicEntryLocaleMessages.test.ts` checks the spec's literal and table-driven catalogue/key
references in both languages before browser execution, including invitation copy in `workspace.json`.
`i18nRequest.test.ts` checks locale precedence, profile-read failure and a single shared profile
read for language and time zone. Document acceptance retains its separate document-locale contract
in [ESIGNATURE.md](ESIGNATURE.md).

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

## The sixteen flows

| Spec | Flow |
| --- | --- |
| `auth.spec.ts` | protected-route redirect, UI register → dashboard, login → dashboard, wrong-password error |
| `quick-create.spec.ts` | Quick Create launcher → new contact → visible in the records browser |
| `records-peek.spec.ts` | list row → peek drawer → full detail → browser back restores list context (`?q=`) |
| `record-edit.spec.ts` | inline table edit (Title) persists across reload |
| `activity-compose.spec.ts` | log an activity on a contact detail → appears in the timeline |
| `notifications.spec.ts` | inbox renders controls/read state; bell popover links to the inbox |
| `analytics.spec.ts` | range/granularity switching updates URL, pressed state, offered grains, and panels (#866 surface) |
| `workflow-canvas.spec.ts` | valid/invalid handle dragging, zoom-aware context insertion, canvas affordances, and the outline alternative |
| `search.spec.ts` | toolbar search finds a seeded contact and opens its record |
| `import-preflight.spec.ts` | CSV preview exposes exact and ambiguous duplicate-review decisions before commit |
| `interaction-history-import.spec.ts` | EN/JA desktop/phone historical activity import and replay suppression |
| `wave2-evaluator.spec.ts` | EN/JA desktop/phone CSV ambiguity review → evidence-backed insight → safe action, with three identical submissions |
| `archive-records.spec.ts` | contact/company archive visibility and restore round trips through the record browser |
| `mobile-record-lists.spec.ts` | Pixel-width contacts/companies/deals/tasks use list rows, mobile sheets filter/sort, and the stored desktop mode survives the forced phone presentation |
| `ask-connex.spec.ts` | EN/JA drawer open/close, page-context correction and pinning, drawer width states, drawer → full workspace handoff, session rail banding/search, deep links, and the phone bottom-bar entry |
| `ask-connex-trajectory.spec.ts` | a self-registered tenant, pointed at the scripted AI provider, sends a question from the composer; the answer streams to the requester's own socket, the read tool calls reach the turn's progress rows, and the settled answer renders a citation chip that opens the cited contact |

Five further specs exist to keep the harness, or a shipped security control, honest rather than to cover a product flow:

| Spec | Proves |
| --- | --- |
| `locale-ja.spec.ts` | the `NEXT_LOCALE` helper actually switches server-rendered copy, logged out and signed in, and that the same page stays English without it (one test tagged `@mobile`) |
| `mobile-shell.spec.ts` | the phone-viewport project really is mobile: the bottom bar and the sidebar toggle that `md:hidden` removes on desktop are both rendered (`@mobile-only`) |
| `seeded-persona.spec.ts` | the identities derived from the seeder log are real: the Japanese persona signs in with the seeded password |
| `assistant-admission-filter.spec.ts` | `AiAssistantNavigationAdmissionFilter` actually closes through the Next.js `/api` rewrite: a page on `http://attacker.example` reading an audited Ask Connex route as an `<img>`, an opaque `no-cors` fetch or a top-level navigation gets the filter's `403` body, including with the session cookies re-issued `SameSite=None` so they ride cross-site; its credentialed `cors` fetch gets the CORS layer's `403`; the same markup on a page under the app origin has its navigation refused by the destination check alone and its opaque fetch by the mode check alone, while its `cors` fetch reads `200`; a metadata-free read on the same session, the workspace's own reads and an in-page same-origin fetch all answer `200`. Responses are read at the wire through `support/wire-tap.ts`, a forward proxy that also serves the probe pages, because Opaque Response Blocking hides cross-origin opaque bodies from Playwright and a `route`-fulfilled page was observed never to send its requests to a loopback app. Background in [`backend/AI_SECURITY.md`](backend/AI_SECURITY.md#cross-site-admission-of-audited-assistant-reads) |
| `csp-enforcement.spec.ts` | the shipped Content Security Policy is actually *enforced*: authenticated dashboard/analytics/records/library/Ask Connex journeys, an upload and its same-origin preview, the dark theme, a passkey enrollment plus passkey sign-in through a CDP virtual authenticator, and the logged-out login/register/document-acceptance pages all raise zero `securitypolicyviolation` events, while an injected inline event handler (`<img onerror>`, the markup-injection shape `'strict-dynamic'` does not trust) is blocked with `script-src-attr` and its `report-uri` report is delivered to `/api/csp-reports` as a 204 |

The suite runs on `http://localhost:3000`, so the proxy emits `report-uri` without `report-to`/`Reporting-Endpoints` (Chromium accepts only HTTPS reporting endpoints) and the violation report is a synchronous, page-initiated POST that `page.waitForRequest` can observe; a script inserted with `document.createElement` is *not* a usable probe because `'strict-dynamic'` deliberately trusts non-parser-inserted scripts.

Three specs pin how the app router and the address bar stay in step, the contract behind the one-time-link strip and the app-shell "Try again" (#1781):

| Spec | Proves |
| --- | --- |
| `router-canonical-url.spec.ts` | measured against the installed Next: a shallow write that hands the history patch a state carrying `__NA` is never heard by the router, so a refresh republishes the stale URL; a state without it is heard, keeps `__NA` on the entry, and survives the refresh |
| `owned-url-params.spec.ts` | each of the seven `writeOwnedParamsToUrl` browsers (files, documents, activities, tasks, Radar, analytics, pipelines), signed in: a deep link with an owned param, its owned-param writes and peek drawer, a router refresh, a reload and Back/Forward keep the params, no write drives a non-prefetch RSC request and every write lets the router settle, and no segment falls back to its error boundary. Each browser's router refresh starts from an address only a heard write could have given the router — its owned params differ from the deep link the document loaded at, which the helper asserts — so the refresh fails if that browser's writes go unheard again. Radar's client feed refresh is rewritten to report one family unavailable, because an empty tenant hides Radar's filters |
| `error-boundary-retry.spec.ts` | a render-phase throw inside the `(app)` shell, forced by answering the Files library's page request once with a malformed body, falls back to the `library` boundary; "Try again" issues exactly one RSC re-fetch, of the URL the page holds including its shallow-written search and sort, and the page recovers with both intact |

Ask Connex coverage splits by tenant, because provider readiness is per organization. The seeded project tenants have no `ai_provider_config` row, so `AiFeatureGate` refuses every turn for them — but only at *turn start*. Session create/list/read, presence, and participants need `AI_USE` and workspace membership alone, which the registered owner has. `ask-connex.spec.ts` and `ask-connex-command-center.spec.ts` therefore exercise surfaces, context, navigation, width, rail, deep links and locale against those tenants and never ask a question. The dual-surface spec overrides the chat-readiness hint for its interaction-only cases. `background-permission-noise.spec.ts` keeps real provider-less readiness, proves the unavailable state accepts no question, and covers first-load member/confinement noise; its confinement fixture fixes standing because CI deliberately disables enforcement for the wider suite.

`ask-connex-trajectory.spec.ts` is the one spec that asks. CI boots the backend with the `ai-scripted-provider` test seam, which replaces the `openai_compatible` adapter with a server-side, fixture-driven provider reading `frontend/test/e2e/fixtures/ai-scripted/`. The spec registers its own tenant in its own browser context, enrols a passkey (saving a provider needs recent authentication), saves that organization's provider row through `PUT /api/ai/provider`, and drives send → tools → answer. It never touches a project tenant, so the seeded tenants keep refusing, and the provider-less assertions in the other two specs passing in the same run is this mode's merge gate. The seam's activation gates, fixture format, and settings are in [`backend/AI_SECURITY.md`](backend/AI_SECURITY.md#scripted-provider-test-seam); how the browser mode relates to the backend trajectory goldens is in [`AI_EVALUATION.md`](AI_EVALUATION.md#the-browser-mode).

Known scope cut: evidence peek and write-tool approval are still not driven from a browser. Extend the scripted fixture for them rather than faking a provider client-side, because the masking and citation behaviour under test *is* the server round trip.

`note-persistence.spec.ts` covers contact mentions in both note editors after typing and on
an immediate save, saved link preservation, response-driven header links, explicit removal
through the dialog selectors, and unchanged body bytes during metadata edits. Its notification
case registers two isolated accounts, completes the owner's passkey step-up, invites and accepts
the second workspace member, then verifies a real `note.mention` notification after saving a
member mention. The ordinary notifications spec still does not mark a real notification as read.

`recentRecordsHydration.test.tsx` pauses a consumer's Suspense hydration while the real recents
provider loads stored history or records a view. Consumers must hydrate from the stable empty
server snapshot, then display the scoped cache without a recoverable hydration error. Scope-switch
cases retain the previous `record` callback and reject its writes; `staleAccountStorageWriters.test.tsx`
keeps account revocation synchronous and checks failed-logout recovery. The browser note cases keep
the contact-page entry point and their zero-page-error assertion. In the note edit drawer, Tab leaves
a cleared selector; Escape requests dismissal and correctly invokes the unsaved-changes guard.

The document-acceptance spec creates frozen documents both without and with a real deal line item,
then sends their deliveries with the authenticated request context. CI configures the backend's
instance SMTP transport on loopback port 2525, where the spec's dependency-free SMTP capture
obtains the `/document-acceptance#token=…` fragment link `InAppAcceptanceProvider` emits. Playwright
opens those links in a new context with no storage state and asserts the cutover contract: the address
bar settles on the bare `/document-acceptance`, no request URL ever contains the bearer, exactly one
`POST /api/document-acceptance/exchange` carries it in its body, and the resulting
`connex_document_acceptance_flow` cookie is `HttpOnly`, `SameSite=Strict`, and scoped to
`/api/document-acceptance`. It then drives preview, viewed, and accept through the running frontend
and backend without interception, verifies the committed delivery receipt, and re-opens the same link
in a fresh context to prove a decided link can no longer be exchanged. CI enables signature delivery,
permits the loopback SMTP destination, and trusts only loopback as the sanitizing proxy for this
dev-profile test process. The presentation cases in the same file route the CSRF bootstrap, exchange,
and the three token-free endpoints in the browser, so viewer presentation, themes, responsive layout,
reduced motion, and Japanese-document rendering no longer need a second Next server; they do not stand
in for persistence proof.

## Flake policy

`fullPageEditorGuards.test.tsx` mounts the report and document-template editors with the shared discard guard. It covers meaningful and reverted edits, configuration/body changes, pending/failed/successful saves, new edits after a successful save, confirmed departure during a save, return destinations, focus, and link/shell navigation. `full-page-editor-guards.spec.ts` exercises all three builders (including workflows) in EN/JA on desktop and phone, native leave warnings, and real Back/Forward history. `workflow-canvas.spec.ts` also confirms departure while initial creation is pending.

`documentTemplateInitialization.test.tsx` uses the installed Tiptap editor to distinguish initialization/editability from user changes and normalize empty bodies. `workflowDepartureGuard.test.tsx` keeps the routed editor surface mounted while creation finishes after Back, sidebar, shell, browser, and unindexed-history departures. Ask Connex's provider tests cover both workspace-opening entry points; the real workspace provider is exercised before selection side effects in `fullPageEditorGuards.test.tsx`. `dealDraftResume.test.tsx` preserves the mobile deal selector after confirmed Back, while the shared hook tests distinguish the latest dismissal callback from a captured navigation destination.

`editorNavigationInvariants.test.tsx` generically checks that Discard disposes content after accepted query-only, hash-only, no-op and async actions, and that fresh edits remain protected. Exact pathname/search requests skip confirmation and preserve the draft. Its router harness models installed Next's refusal to render null-state `popstate` destinations. Null, unknown, legacy and Next state shapes must restore the rendered URL before confirmation and explicitly route after Discard. Another case moves the address bar before an editor render to ensure restoration uses rendered route context. The real shell skip link creates native fragment entries: traversal both to and from them checks content, URL, Keep editing and Discard. Query and hash traversal retain protection for a subsequent Discard.

Clean Back/Forward cases, with and without native entry positions, require the guard to leave propagation, history and routing untouched so Next preserves custom `connexRecordReturn` state. A separate case becomes clean during an outstanding guarded restoration and must still finish its replay. The browser Back/Forward case first returns from an untouched report to its list and Forward to the empty form before exercising dirty exits.

Commit-boundary regressions dispatch native `popstate`, shell navigation and `beforeunload` from a layout effect immediately after the dirty edit commits, before passive effects. This catches stale clean subscriptions that ordinary `act`-flushed tests hide. Browser cases assert visible content, focus and URLs without event logging or wrapping native event methods. Native listener priority is covered by the ordering fixture below; URL/DOM outcomes alone do not establish which event handler ran first.

Legacy-template coverage verifies that Tiptap really adds default attributes without publishing an edit. Current-workflow search cases exercise the real workflow hook and `openResult`, checking no-op routing and confirmed query changes preserve later discard choices and native leave warnings. Browser counterparts cover native fragment traversal in both directions and Discard after removing `?runKey=canonical-1` from a workflow after exiting inspection. Native known-delta coverage repeats Forward after Keep editing and verifies the original filtered destination survives. Its browser counterpart uses a real Deals search and checks both its URL and restored search field. Unknown-distance tests retain the safe replacement-slot fallback; their subsequent Back/Forward leg creates fresh history.

Report/template save regressions hold an existing draft's request pending, accept a query-only history change, then finish the save. They check that Discard remounts a clean form from server props, the old response cannot redirect or change it, and new edits become dirty again. New-record regressions for all three editors confirm that pending creation cannot leave a locked form or a retained draft that creates duplicates. Notification regressions use the real workspace provider and shared guard to hold workspace publication, before-open callbacks, routing and cross-workspace actions until Discard; Keep editing cancels without effects or an error result. Separate cases reject late success callbacks after the draft id or rendered workspace changes.

The public-docs browser case selects a category and then an article explicitly; the first sidebar link is a category, not an article. It enters the app with document navigation and checks the native leave warning on the jump back to the article. It does not depend on the docs header offering “Open app”, which is intentionally absent in prelaunch mode. Same-document null/unknown destinations remain covered by the invariant matrix and native fragment browser cases.

- Specs that register their own accounts must start with empty `storageState` or a fresh context.
  Registering through a project-seeded session replaces that session on the server and signs out
  every test that inherited it. The shared `registerUser` helper refuses an authenticated or
  indeterminate context before posting; `e2eRegistrationIsolation.test.ts` covers this boundary.
- **No sleeps.** Waits are Playwright auto-retrying assertions (`toBeVisible`, `toHaveURL`, …). Animations are neutralized with `reducedMotion: "reduce"`; locale/timezone are pinned (`en-US`/UTC).
- Specs only touch **records seeded for that spec** (distinct contacts per flow), and desktop/phone projects own separate tenants and fixture files, so the default 2 workers never race each other.
- CI runs with `retries: 2` and uploads **traces + screenshots + the HTML report** on failure; locally retries are 0 so flake is visible, not hidden.
- A test that flakes repeatedly gets fixed or quarantined with `test.fixme()` plus a linked issue — never a lengthened timeout as a "fix", and never deleted silently.
- The CI job (`Frontend — unit & e2e` in `.github/workflows/ci.yml`) is a **required check** on `main` (#853, graduated in 735d885a). A failure there blocks merges, so every step in that job has to stay deterministic.

## Ownership-offer confinement parity

Slice 4o changes only the shared API confinement gate in this backend lane. Run `pnpm exec vitest run test/unit/passkeyApprovalApi.test.ts` for exact server/client method/path allowlist parity. Authenticated `GET /api/ownership-offers` and self-only `POST /api/ownership-offers/{id}/decline` remain reachable while confined; acceptance, creation and revocation require the normal privileged gate. The frontend implementation owns card/member-panel and browser coverage against [the actual backend contract](backend/OWNERSHIP_OFFERS.md). Shared/default-catalog inbox tests retain ordinary notification behavior; the self-offer card is authoritative on every catalog layout.

`invitation-privacy.spec.ts` drives email invitations in English and Japanese. Pending entries stay
address-only and outside the active member count, including after the recipient exchanges the link
but before final acceptance. After acceptance the collaborator appears normally. The test also
covers replacement invitations and revocation; membership fixtures redeem the same purpose-bound
email invitation through `support/invitations.ts`.

`editorNativeHistoryOrdering.test.tsx` models Chromium native Window dispatch at AT_TARGET in registration order, including a Next listener that synchronously restores the route and unmounts the editor. JSDOM's ordinary dispatch instead visits capture listeners first and cannot expose this race. The test imports the real synchronous client instrumentation before mounting Next, then checks unknown Back, indexed Back, a native-fragment source, and known Forward with its original search. Keep editing retains the draft and native warning; repeated traversal followed by Discard renders the captured destination. Removing only startup initialization must fail the regression because Next restores first. The hook's layout registration remains necessary to publish committed edits; it does not establish listener priority.

`listStateNavigation.test.tsx` holds outgoing list hooks mounted after location has moved to a report editor, then fires pending view/filter effects, query/state writers and a late peek callback. The destination URL, fragment and Next history state must remain unchanged. A separate case verifies that a clean Deals visit intentionally canonicalizes its selected table view without adding an entry. The browser guard cases wait for that canonical state instead of racing an exact bare-path assertion. The known-Forward setup awaits the initial editor URL and visible form before opening Deals, ensuring its source history entry was committed. It still goes Back immediately after the query reaches the URL, asserts the editor URL before editing, and checks the complete captured query context after Discard independent of parameter ordering. This keeps the outgoing-writer race exercised; retries and extra sleeps are not substitutes for the pathname fence.
