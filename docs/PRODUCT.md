# Connex Product Guide

This is the product source of truth for Connex. Anyone — human or AI agent — writing UI copy, naming a feature, designing a flow, or deciding what a user should see reads this document first. It answers "what is this product, who is it for, what do we call things, and how do we speak" so those decisions are made once, here, instead of being re-invented per surface. [`../frontend/AGENTS.md`](../frontend/AGENTS.md) points here and makes this guide required reading before any copy, naming, or flow work.

It complements, and never duplicates, the engineering guides:

- [`../AGENTS.md`](../AGENTS.md) and the per-package guides ([`../frontend/AGENTS.md`](../frontend/AGENTS.md), [`../backend/AGENTS.md`](../backend/AGENTS.md)) own engineering rules — architecture, tenancy, review — and all mechanism-level pattern law: which components, hooks, and files implement the behaviors this guide describes. This guide stays at the level of what the user experiences.
- The tenancy architecture itself lives in [`MULTITENANCY_PLAN.md`](MULTITENANCY_PLAN.md).

When this guide and an existing screen disagree, this guide wins; fix the screen.

---

## 1. What Connex is

**Connex is a modern CRM built around trustworthy relationship intelligence.** That is the fixed positioning. It is a CRM first — companies, contacts, deals, tasks, notes — that happens to understand relationships better than any other CRM: how warm they are, when they are cooling, who can introduce whom, and why a deal is at risk. "Relationship operating system" is internal vocabulary only; it never appears on a product surface or in marketing aimed at buyers.

The product is one loop, and every feature must place itself somewhere on it:

**capture trustworthy evidence → understand the relationship → know why it matters → act safely → preserve the history → learn and automate without losing control.**

A feature that shows a signal but offers no action breaks the loop. A feature that acts but leaves no history breaks the loop. When evaluating a design, ask which step it serves and whether it hands the user to the next one.

**What Connex is not:**

- **Not a generic database.** Screens are opinionated about relationships and next actions, not neutral tables of rows. If a surface could belong to any record-keeping tool, it is underdesigned for Connex.
- **Not a compliance console.** Connex is rigorously compliant under the hood (APPI, tenant isolation, auditability), but compliance is plumbing the everyday user should feel as safety, not read as statute. Legal machinery surfaces only on the pages built for it (see principle 3).
- **Not a dense admin shell.** Configuration exists to serve selling and relationship work, not the other way around. Admin surfaces are visited occasionally by a minority of users; they never set the tone of the product.
- **Not an autonomous actor. AI proposes; the user applies.** Every AI output in Connex — briefs, risk rationales, intro suggestions, assistant answers — is a proposal a person can inspect, question, and ignore. When the assistant offers to change an existing record, it prepares a validated proposal and shows exactly what would change; nothing is changed until the user explicitly applies it. The one deliberate exception: while answering a request, the assistant may immediately add something small — log an activity, create a task or note, add a tag — and always says so; activities, tasks, and notes offer a short undo window, and a tag is removed the same way any tag is. Automation (workflows) acts only within rules a person explicitly built, reviewed, and turned on. We never claim "AI never touches records" — the truthful promise is that every AI write is announced, and either confirmed by the user first or plainly reversible on the record.

## 2. Who it's for

Connex serves Japanese enterprises and international teams, in English and Japanese as equal first-class languages. The cast:

**Relationship-driven professionals** — salespeople, business developers, partnership managers whose asset is who they know and how warm those ties are. They need to feel that Connex *gets it*: that logging an interaction pays them back with insight, and that the product surfaces the relationship work worth doing today. They are busy; repeated operations must be quiet and fast.

**First-time and occasional CRM users** — many Connex users have never used a CRM, or open it once a week. They need to feel *safe and oriented*: every screen explains itself, every empty state teaches, nothing punishes exploration, and no word on screen requires prior CRM literacy or a glossary. This person is the calibration target for all product copy. If a label would confuse them, it is wrong — no matter how precise it is internally.

**Workspace admins** — a team lead who also configures members, fields, and workflows. They need to feel *in control without being an IT department*: one obvious place for each setting, plain explanations of consequences, and confidence that they can't silently break their team's data.

**Organization administrators and compliance owners** — the people who answer for data across workspaces: access, retention, privacy requests, AI policy. They are the one audience allowed precise legal and operational language, on the surfaces built for them. They need to feel that Connex makes their obligations *executable*: every duty has a page, a record, and an evidence trail.

**Japanese enterprise users specifically** — expect polished, courteous business Japanese (です・ます), native vocabulary over katakana loanwords, and zero bureaucratic or statutory register in daily work. Japanese is written as its own language, never as literal translation of English engineering prose.

## 3. Product principles

Numbered and testable. Cite them by number in reviews.

1. **The user never reads our org chart.** No internal, architectural, or implementation vocabulary on any product surface, in any locale, on any path — including errors, toasts, badges, and empty states. Tenant, canonical, runtime, deterministic, predicate, slug, token budget: these are how we build, not what users read. Backend messages are diagnostics, not display copy. *Test: grep the diff for the banned list in §4; would a non-engineer understand every visible string?*

2. **One name per concept, in both languages.** Every product concept has exactly one canonical English name and one canonical Japanese name (§4), used verbatim in the sidebar, command palette, page titles, breadcrumbs, buttons, docs, and notifications. Synonyms are drift, not style. *Test: does the label match the glossary row exactly, in both locales?*

3. **Legal language lives on legal pages — compliance surfaces get plain-first phrasing.** Statutory precision belongs on the surfaces built for it: the organization data-requests admin tooling, admin compliance settings, and the public legal and privacy pages ("compliance surfaces" throughout this guide). Everywhere else, state the human consequence in plain words ("This person asked to limit how their data is used") and let the statute live in a secondary line or the admin surface. Even on compliance surfaces, lead with the plain phrasing and let the statutory term follow — a human explaining an obligation, not a machine reciting one. *Test: does any statutory phrase appear outside the compliance surfaces? On those surfaces, could a new admin act on the message without a lawyer?*

4. **Every signal explains itself and offers one action.** Anything Connex asserts — a warmth reading, a risk flag, a cooling prediction, an intro suggestion — shows its evidence and presents exactly one obvious next step, right there (log an interaction, schedule a follow-up, ask for the intro), everywhere the signal appears. Records the signal cites are links, never bare ids; every cross-surface link lands with the referenced item in view; nothing dead-ends. A readout with no action is an unfinished feature. *Test: from this signal, can the user reach both "why?" and "do something about it" in one click?*

5. **Errors say what happened, what it means, and what to do.** Every failure message, in the user's language: plainly what didn't happen, whether their data is safe, and one next action. Server errors carry a support reference ("Reference: {id}"). Never a raw status code, exception text, or internal message. A signed-out session is not an error: the product takes the user to sign in and returns them to where they were. *Test: does the error answer all three questions, in the current locale, without engineering vocabulary?*

6. **Same operation, same container.** Each kind of operation always arrives in the same kind of surface: creating happens in a centered dialog, editing in a side drawer with context visible, inspecting in a drawer or popover with a path to the full page, confirming in a small dialog, authoring in a dedicated full page (§6). Per-surface adaptations are documented decisions, not drift. *Test: name the archetype this surface uses; if it deviates, point to the documented reason.*

7. **Motion responds, never delays.** Input is acknowledged within a tenth of a second; motion accompanies the result, never precedes it; nothing blocking runs long; exits are faster than entrances; reduced-motion preferences are honored; motion is never a loading strategy. Playfulness for what responds to the user's hand, calm for state changes, celebration rarely. *Test: would this animation still feel good the fiftieth time today? Does removing it lose meaning or just decoration?*

8. **The first hour teaches the product.** Every empty state says what this place is, why it's empty, and offers one inviting action; first-run and no-filter-matches are distinct states; a new workspace gets a guided journey from empty to first insight. "Nothing to show here." is banned. *Test: dropped onto this screen with no data and no training, does a first-time user know what it's for and what to do next?*

9. **Customize the business, not the software.** Configuration models the customer's world — their pipeline stages, fields, lifecycle, workflows — never the mechanics of Connex itself. Users tune what they sell and how, not runtimes, engines, or internals. *Test: does this setting map to a business decision the customer already makes?*

## 4. Vocabulary

**This section is the canonical glossary — the single source of truth for product terms.** There is no other glossary file. The CI banned-terms lint over the product's message catalogs (EN and JA patterns) is **generated from this section**: edit here, and the gate follows. (The generator is `frontend/lint/vocabulary.mjs`, which writes the checked-in pattern set; the gate is the `frontend/test/unit/vocabularyLint.test.ts` suite, which scans both message catalogs against it in error mode over a baseline that only shrinks. Editing this section without regenerating fails the generator test.) Labels, columns, titles, and buttons use these terms verbatim; prose may vary naturally around them. "Never say" applies to product surfaces — code, schema, logs, and engineering docs keep their own names.

| Concept | EN | JA | Never say (on product surfaces) |
|---|---|---|---|
| The relationship metric (the differentiator) | **Warmth**; bands: **warmth band** (hot / warm / cool / cold); trend: "warmth is cooling" | **温度感**; bands: **温度帯**（ホット／ウォーム／クール／コールド） | relationship temperature, temperature band, relationship score; 温度バンド, 関係の温度, 関係性の温度, 関係スコア, 温度 alone as the metric name |
| The signals triage surface | **Radar** ("Relationship Radar" only as an occasional full name in onboarding/marketing prose) | **レーダー** | Relationship Radar as the everyday label |
| The personal work page | **My Work** — your tasks, your relationships, your follow-ups | **マイワーク** | Profile (for this page); マイページ |
| Company record | **Company** | **会社** | account (for the record); アカウント, 取引先 |
| Person record | **Contact** (labels, columns, counts, search groups); "person/people" only inside explanatory prose | **連絡先**; 文中の「人」は可 | People (as a label for contact lists) |
| Deal record | **Deal** | **案件** | opportunity as a countable UI noun (intro suggestions are "suggested intros"); 商談 except as an example stage name |
| Where records live and are shared | **workspace** | **ワークスペース** | organization (for workspace scope), team (as a scope; fine informally for the humans), tenant (legal pages: allowed) |
| The admin level above workspaces | **Organization** (as a destination or section name, only on organization-admin surfaces; fine in prose stating a genuinely organization-scoped fact — "your organization's AI provider") | **組織** | tenant (legal pages: allowed); テナント (legal pages: allowed) |
| A person in a workspace | **member** | **メンバー** | user (except auth/sign-in/session contexts), teammate (as a label; fine in informal prose) |
| Org-level admin | **organization administrator** | **組織管理者** | — |
| The automation object | **workflow**; **automation** only as the category noun; **recipe** for a pre-built template | **ワークフロー**／**自動化**（総称）／**レシピ** | rule (except as "legacy automations" inside the one migration screen while it exists), canonical, runtime, legacy runtime |
| The act/record of introducing | **introduction** ("intro" in tight buttons) | **紹介** | — |
| The route through the network | **intro path** | **紹介ルート** | warm path (as a label); ウォームパス, 温かい経路 |
| Standalone linked note record | **Note** | **メモ** | ノート |
| Discussion on a record | **Comment** | **コメント** | note (for comments) |
| Free-text activity logging | Log it as an activity of type **Other** — the only two writing surfaces are Notes (records) and Comments (discussion). The shipped activity type "Note" folds into **Other**: it leaves the composer pickers and filter rows, and existing "Note" activities display as "Other" — a read-time fold; history preserved, nothing deleted | 種別は**その他** | Note (as an activity type) |
| Task and its kinds | **task**; **follow-up task** as a qualified kind of task; **To do** only as a kanban column name | **タスク**／**フォローアップタスク**；列名は現行訳（「未着手」等）を維持 | to-do / todo as a standalone noun |
| Task ownership | **Assignee** | **担当者** | Assigned to |
| Record ownership | **Owner** (qualify roles: "Workspace owner", "Organization owner") | **担当者**（ロールは**オーナー**） | — |
| Personal saved list state | **saved view** (an ad-hoc **filter** becomes one when saved; pinning is a state of a saved view) | **保存ビュー**（ピン留めは保存ビューの状態）／**フィルター** | — |
| Rule-based live membership | **smart segment** | **スマートセグメント** | — |
| Recoverable hide | **Archive** | **アーカイブ** | delete (when the record is recoverable) |
| Permanent removal | **Delete** — always with "This can't be undone." | records: **削除**「この操作は取り消せません。」; erasing captured data (e.g. imported provider data on disconnect): **消去** — both sanctioned, each bound to its context | purge, teardown, tear down; パージ |
| Detach from a relationship | **Remove** (never destroys the record) | **解除** | — |
| APPI use-limitation state | **Privacy hold** — badges: "On hold — privacy request" / "Sharing stopped — privacy request"; plain gloss "This person asked to limit how their data is used."; statutory terms only as a secondary admin hint and on compliance surfaces | **プライバシー保護のため利用停止中**（法令用語の「利用停止」「第三者提供停止」は補足・コンプライアンス画面のみ） | processing suspended, provision ceased, processing restrictions, restricted (unglossed) as headline copy |
| Marketing exclusion states | **Opted out**, **Do not contact**, **Privacy hold** | **配信停止（オプトアウト）**, **連絡不可** | consent and suppression, consent access; 抑制 |
| Support reference on failures | **Reference: {id}** | **参照コード: {id}** | correlation ID |
| Home / metrics / documents split | **Dashboard** (your workspace today) · **Analytics** (metrics over time) · **Reports** (generated documents) | ダッシュボード／分析／レポート | "at a glance" on more than one surface; Overview as a page name |

**Banned on all product surfaces** (allowed in code, logs, and engineering docs; allowed on compliance surfaces — the organization data-requests admin tooling, admin compliance settings, and legal pages — only where noted):

`tenant` (legal pages: allowed) · `teardown` · `deterministic` · `canonical` / `legacy` (as runtime taxonomy) · `predicate` · `node` / `graph` / `traversal` · `slug` · `ESP` · `RBAC` / permission constants (`RULE_MANAGE` etc.) · `epoch` · `data subject` (compliance surfaces: allowed) · `cease of use` / `third-party provision` (compliance surfaces: allowed) · `suppression` · `purge` · `token budget` / `context space` · `turn` (assistant: say "answer") · `egress` / `provider egress` · `projected` / `projection` · `admitted` · `opaque identifier` · `preflight` · `idempotency` · `hash` · `invocation` · `mutation` · `register` (as a noun for a list) · `demask` / `rewrap` · `correlation ID` (say "Reference") · raw ids in copy (`contact #42`) · raw enum/code/camelCase fallbacks (`Retention rule: {code}`) · HTTP status codes shown to users (`Request failed (403)`)

Generator note (this paragraph names terms in prose rather than in backticks: the generator fails closed on a term stated in backticks below the list, where it could never read it): where a banned term is a substring of a canonical term (温度 alone inside 温度感／温度帯), the generated pattern must except the canonical usages rather than dropping the ban. Where a banned word is also part of a proper noun — the Node.js runtime behind the banned engineering term node — the pattern excepts the proper noun and keeps the ban on every other use. The gate reads rendered copy: the command palette's search aliases, the Actions.keywords entries, are never shown to anyone and exist to catch what a member types, retired words included, so they are not scanned. A banned item is the concept and not one way of setting it: a multi-word term is read with a hyphen as readily as with a space, and the statutory states are caught stated verb-first as well as noun-first.

## 5. Voice & tone

Connex sounds like a competent, calm colleague: plain, direct, specific, never bureaucratic and never cute. Both languages are written natively — Japanese is composed as Japanese business prose, not translated word-for-word from English.

**Rules, with the before → after pattern each enforces:**

1. **Lead with the consequence, not the mechanism or statute.** Say what it means for the user first; the why is a secondary line at most.
   - ✗ "Third-party provision has been ceased for this contact" → ✓ "This contact asked not to be shared outside this workspace, so new shares are blocked."
   - ✗ 「配信前に許諾と抑制が適用されます」 → ✓ 「配信停止（オプトアウト）や連絡不可の方は、配信前に自動的に除外されます」

2. **Say what the software does in the user's verbs, not the system's.**
   - ✗ "The prepared scope no longer matches its confirmation hash." → ✓ "The selection changed since you reviewed it. Review it again before running."
   - ✗ "This read-only activity was projected from provider data admitted by the effective capture policy." → ✓ "This was captured automatically from your connected {provider} account, following your capture settings."

3. **One error dialect.** "Couldn't \<verb\> the \<object\>" — contraction, sentence case. Toast **titles** are short fragments with no trailing period ("Couldn't save the contact"); toast **descriptions** are full sentences with normal punctuation ("Nothing was lost — try again."). Retryable operations end the description with a try-again invitation. JA: 「〜を保存できませんでした」＋「もう一度お試しください。」
   - ✗ "Failed to save" → ✓ "Couldn't save the contact" + "Nothing was lost — try again."
   - Never "Failed to X", never "Could not X".

4. **Numbers, dates, and limits in human terms.**
   - ✗ "The organization daily AI token budget is exhausted." → ✓ "Your organization's daily AI limit has been reached. Try again later."

5. **Warm precision in empty and quiet states — explain, then invite.** Never a bare "nothing here"; never a machine enumerating its own rules.
   - ✗ "No current relationship signals meet the deterministic detector rules." → ✓ "Radar is clear — nothing in your logged activity needs attention right now."

6. **Transparency without the policy register.** Users deserve the truth about data in one short human sentence, not a clause.
   - ✗ "…your sessions are retained and become accessible to workspace administrators." → ✓ "Chats here belong to this workspace — its admins can see them, even after you leave."

7. **JA register: 丁寧だが役所的でない.** です・ます throughout product copy; native words over katakana loans wherever a native word reads naturally (紹介ルート not ウォームパス; メモ not ノート; 削除・消去 not パージ) — the glossary's katakana entries (ワークスペース, メンバー, レーダーなど) are the settled exceptions; statutory compounds (利用停止, 第三者提供停止) only on compliance surfaces or in parentheses after the plain phrasing. Use 削除 for deleting records and 消去 for erasing captured data — both are sanctioned, in their own contexts. Translate meaning, not words: JA is re-authored from the meaning of the final English, never patched word-by-word — and if the EN source is engineering prose, fix the EN first, then write the JA fresh.

8. **Create verbs are positional.** "New \<object\>" for page-level creation, "Add \<object\>" for attaching to the current record. JA: 「新規〜」／「〜を追加」. Settings panels save with "Save changes"; dialogs with "Save"; settings toasts say "\<Object\> saved", record toasts "\<Object\> created/deleted".

## 6. Standard moments

The canonical behavior for each recurring moment. **This section is normative target state**: it describes the product Connex is converging on; where a shipped screen disagrees, the screen is the bug. Which components and files implement these behaviors is defined in [`../frontend/AGENTS.md`](../frontend/AGENTS.md).

**Creating things.** Creating is always a centered dialog — one canonical composer per object (task, activity, note…), the same one everywhere it opens (quick create, record pages, peek), with context pre-filled. Builder artifacts — workflows, reports, document templates, and **campaigns** — use instant-create: name it, then land in the dedicated full-page builder. Nothing else about creation is full-page; nothing about authoring is a dialog.

**Editing and inspecting.** Editing opens a right-hand drawer so the context stays visible. Inspecting/peeking opens a right drawer or an anchored popover, always with a path that expands into the full page. A calendar event click opens an anchored popover — time, attendees, linked record, one action — and "open" expands it into the right drawer. On mobile, these surfaces become bottom sheets. Per-surface adaptations are allowed when content demands them, but they are documented decisions, not drift.

**Abandoning work.** Any dialog or drawer that has accumulated input asks before discarding it — an outside click or Escape never silently destroys work.

**Confirming destructive actions.** A small centered dialog, one grammar: title "Delete \<object\>?", body names the specific object and ends "This can't be undone." (JA 「この操作は取り消せません。」), confirm button "Delete" in the destructive style. "Archive" needs no undo warning — it is recoverable and says so. "Delete permanently" is reserved for organization-level permanent deletion, which additionally requires typed confirmation.

**Success and failure feedback.** Every failure speaks the §5 error dialect, localized, selected from the error's meaning — raw backend or exception text never reaches the user. Server errors append "Reference: {id}" so support can find the incident. (References render on server-error toasts, full-page error screens, and the admin diagnostics panels — the panels still say "Reference ID {id}" and need aligning to "Reference: {id}". The toast copy, the error screens, and the support-identifier table in [`INTERNAL_OPERATIONS_RUNBOOK.md`](INTERNAL_OPERATIONS_RUNBOOK.md) are one contract: a change to any of them updates the others in the same PR.) A signed-out or expired session is never an error toast: the product takes the user to sign in and returns them to where they were.

**Empty states.** The shared empty-state component, always with three parts: what this place is, why it's empty right now, and one inviting action — "No contacts yet. Contacts are the people your team knows — add your first one or import a CSV." First-run and no-filter-matches are distinct states with distinct copy; filtered-empty offers "Clear filters". A brand-new workspace gets a guided journey: import a CSV, scan business cards, or add a first contact — and on to the first warmth insight.

**Loading.** First loads show skeletons that mirror the destination's real layout — header, tiles, table rows — never generic shapes, spinners, or "searching…" text. Skeletons are for first load only: refetches keep existing content in place, with no flash back to bones. In-dialog async work shows a clear busy state over the dialog, not a frozen form. Motion is never a loading strategy.

**Motion.** Three speeds: quick for hover and toggle feedback, standard for overlays and entrances, expressive reserved for rare memorable moments. Anything responding to the user's hand — hovers, presses, menus popping in — answers with springy playfulness; state changes ease calmly; celebration is rare and earned. Input is acknowledged within a tenth of a second, motion accompanies the result rather than preceding it, exits are faster than entrances, and reduced-motion preferences are always honored. Elements that continue across surfaces (a launcher becoming a dialog, a peek becoming a page) move as one continuous shape — that continuity is the app's signature.

**Buttons.** Pill-shaped, one consistent height per context (page header, toolbar, inline, dialog footer). A button that opens a menu always shows a chevron — an action-looking button never surprises with a menu. A split button is one capsule: primary verb plus a chevron menu behind a divider. Icon-only buttons are circular and always tooltipped. Mode and view switching uses a segmented control, never a row of toggle buttons. Each view region has exactly one primary action.

**Permission denied.** Page-level: a calm explanation with a way back and, where useful, who to ask. Inline/toast: "You don't have permission to do that here. Ask a workspace admin." Never a permission constant, never a bare 403. If a whole surface is unavailable to a role, prefer not rendering the entry point over showing a locked door.

**AI moments.** AI proposes; the user applies: assistant-prepared changes to existing records are validated proposals that show exactly what would change and wait for explicit confirmation; small additions made while answering (an activity, task, note, or tag) apply immediately and are announced — activities, tasks, and notes with a short undo window, a tag removed the same way it was added. Every AI surface names its evidence and how fresh it is — or plainly says it can't. Transparency stays in plain register: the assistant discloses "Chats here belong to this workspace — its admins can see them, even after you leave." once per session list (not per chat); card scanning tells the truth about where reading happens and what is kept — "Cards are read on Connex's own servers when possible; otherwise the image is sent to your organization's AI provider. A copy of the card is saved with the contact — check the details before saving." Deeper guarantees live in docs and admin AI settings, not in everyday copy.

## 7. Where things live

The top-level IA contract. **This section is normative target state** — the structure the product is converging on, stated as its destination; where the shipped navigation differs, it is mid-migration, and old paths redirect permanently to the new ones (browser-tab titles follow the new names). New features find their home here first; new top-level sections require amending this document.

**The sidebar:**

1. **Dashboard** — "your workspace today", the one home. No second surface claims "at a glance".
2. **My Work** — your tasks, your relationships, your follow-ups: the personal page beside the workspace home. The user menu offers "My Work" and "Account settings" as distinct entries — neither is called "Profile".
3. **Intelligence** — Radar, Introductions, Map: the differentiator, grouped and celebrated, never buried in a grab-bag. Radar is the signature surface — visual-first exploration that answers what no other page can (portfolio-wide warmth, where attention is bleeding, clusters at risk, unknown paths), with every signal offering one action; the rest of the app quotes its visual vocabulary in miniature, so Radar reads as the source of the intelligence.
4. **Records** — the nouns: Companies, Contacts, Deals, Pipelines, Products. Every Records noun has a working destination: full detail pages for Companies, Contacts, and Deals; Pipelines and Products are managed in place through their edit sheet/dialog and have no detail route. Nothing anywhere links to a route that doesn't exist.
5. **Activity** — the verbs: Activities, Tasks, Notes, Calendar.
6. **Insights** — Analytics, Reports, Goals. Every figure drills through to the records behind it.
7. **Marketing** — Campaigns. Campaigns and contact records see each other: a contact's timeline shows campaign touches, and campaign engagement reaches the contacts behind it.
8. **Library** — Documents, Files, Tags.
9. **Workflows** — the one automation system. The word "rule" appears nowhere in the product; the only exception is the single migration screen, which may say "legacy automations" while it exists. No visible runtime taxonomy, ever.

The "Overview" section is dissolved; "Overview" survives nowhere as a page name. Route prefixes follow the groups (Intelligence, Insights, and so on), with permanent redirects from every old path.

**Settings — one home for every scope** (amended 2026-08-18 by the founder's decision in #1340, superseding the earlier three-shells prescription):

- **One visible Settings experience at `/settings`** holds every settings and administration job, in scope-labeled groups: Personal, Workspace · {workspace name}, and Organization · {organization name}. Account and Organization no longer exist as competing settings shells. Permission and ownership scopes stay distinct and fail closed — the shell only presents them coherently, and a group is visible only when relevant and authorized.
- **Exactly one canonical destination per settings job**, with one stable deep link: add a teammate, roles, invitations, allowed domains per scope, audit per scope, diagnostics per scope, provider connections, notification preferences and defaults, email delivery, approval policies, workflow configuration. Duplicated concepts are always scope-labeled: "Workspace audit log" vs "Organization audit log", and likewise for diagnostics and allowed domains.
- **Capability-managed destinations never silently vanish or teleport**: they stay discoverable and explain their state in place ("Managed by your Connex instance", "Ask a workspace administrator", "Not enabled for this deployment").
- **Contextual links from feature surfaces are shortcuts into Settings**, never additional configuration implementations.
- Desktop uses searchable vertical grouped navigation; mobile uses drill-down navigation; nothing uses a horizontally scrolling destination dump.

**Notifications and email:**

- One notifications inbox, top level, with a visible path to its preferences.
- Preference pages are scope-labeled inside Settings: personal notification preferences under Personal; workspace notification defaults under Workspace.
- Email and Delivery merge into one Workspace → Communications destination with clear sections — sending setup, sender identity, test delivery, deliverability and health — with instance-managed state visible rather than a disappearing tab.

**One name per destination**, verbatim across sidebar, command palette, page title, browser tab, breadcrumb, and docs — and every cross-surface link (notification, calendar entry, stat tile, signal reference) lands on an existing route with the referenced item in view.
