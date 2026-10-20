# AI Security and Provider-Egress Contract

This document is authoritative for Connex's customer-BYOP AI boundary. Read it before changing AI gates, prompt construction, masking, media admission, provider adapters, endpoint validation, budgets, streaming, assistant tools, or collaborative transcripts.

## Fail-closed enablement

AI is disabled unless every applicable gate passes:

- the deployment master switch;
- the feature-specific switch;
- the actor's `Permission.AI_USE`;
- an enabled organization provider configuration;
- the provider adapter supports and can completely resolve the configuration;
- the organization has made the required no-training/ZDR attestation for the exact destination where applicable;
- credentials exist when the selected provider requires them;
- workspace governance, organization budget, and invocation admission permit the call.

`connex.ai.enabled` defaults false. A feature switch cannot override the master switch. Deterministic Connex features continue to function when AI is unavailable; callers receive an explicit unavailable/terminal result rather than a hidden fallback that changes semantics.

Ask Connex (`ASSISTANT_CHAT`) additionally requires a model whose context window is at least `AiAssistantPromptBudget.ASSISTANT_MIN_CONTEXT_TOKENS` (65,536 tokens). A smaller model produces an honest per-turn `context_window_too_small` refusal — asserted at budget derivation and re-asserted before every model step, so a mid-turn provider change to a smaller model refuses identically instead of degrading. Other AI features assemble far smaller envelopes and keep serving smaller models unchanged (the settled decision and rationale live on issue #1420).

Provider credentials remain envelope-encrypted with the existing AI-provider secret purpose. Provider configuration changes remain organization-admin plus recent-authentication/step-up gated.

## Invocation choke point

Every model call goes through `AiInvocationService`. Do not call `AiProvider`, a provider client, or a raw HTTP client directly from a feature.

The invocation boundary owns:

- feature/permission/provider/gov/budget admission;
- credential resolution;
- masking/outbound leak screening;
- provider routing and bounded transport;
- demasking/screening of the final result;
- metadata-only `ai.llm.call` audit for attempt/success/failure/blocked outcomes;
- media admission leases when images are embedded;
- hard wall-clock cancellation in addition to socket/inactivity timeouts.

Prompts, responses, credentials, masked-token maps, source content, and raw provider payloads never enter logs or audit rows.

## Masking and prompt assembly

Features do not build raw prompts.

- Tokenize person/company identifiers through `MaskingEngine.maskField`.
- Route free text through `MaskingEngine.maskFreeText` so special-care screening and identifier substitution are applied.
- Assemble prompts through `PromptAssembly`.
- Keep `MaskedPrompt` construction confined to the masking package; do not widen its constructor/bypass path.
- Email addresses, phone numbers, postal addresses, and other fields excluded by `IdentifierPolicy` do not enter ordinary text prompts.
- `special_care` text is excluded.
- The request-scoped masking map is never persisted. Masking reduces provider exposure but does not change Connex's APPI role because Connex can re-identify the tokens.

Any new feature must preserve the same masking and leak-scan boundary for every provider/protocol path.

### Ask Connex record-identifier prefilter (tenant V230)

`AiIdentifierLookupKeys.fromName` derives literal and conversation-label aliases from
`CanonicalText` and the existing dictionary eligibility rules. Every person/company/deal name
writer binds both aliases through explicit MyBatis parameter handlers receiving the same raw name
parameter as `name`, alongside its exact source snapshot and format version. Ineligible aliases
are empty strings; a missing bundle remains null. Canonical text and snapshots are sensitive
CRM data: they stay in the owning parent row, its tenant export and physical erasure, never logs,
provider metadata or a new authority source.

A bundle is processed only when its version equals `AiIdentifierLookupKeys.VERSION` (version 3),
both keys are non-null, and the snapshot equals the current name byte-for-byte (`CAST(... AS BINARY)`).
Each alias is capped at 512 Unicode code points. Before canonicalization, any unpaired UTF-16
surrogate marks both aliases `UNKEYED`; brace deletion must never join malformed surrogate halves
into a key that differs from the UTF-8-persisted name. Version 3 invalidates all earlier bundles,
including ones derived from malformed source strings. If either alias exceeds the cap, both also
store the explicit NUL `UNKEYED` marker: controls cannot survive as a canonical alias. Such rows use
legacy SQL fallback; aliases are never truncated and coverage is never omitted. Processed markers
are excluded from pending-backfill counts; retries do not reinterpret malformed input or truncate
oversized aliases.

Current keyed rows use binary `LOCATE` against separately bound Java literal/label turn projections.
Authoritative names, snapshots, canonical keys, turn projections and CAS-name operands use explicit UTF-8 byte parameter handlers
and `CONVERT(... USING utf8mb4)` before any binary cast. This avoids both connection-byte mismatch
and lossy client conversion of Japanese/supplementary projections on a Cp1252 JDBC connection.
The Cp1252 regression asserts admission and absence of the raw name in the first provider payload,
including UTF-8-written Japanese/supplementary rows and a representable name whose fold is Greek.
There is no
per-row SQL normalization or blanket Unicode admission in that branch. Every Java-admitted
mention contains its corresponding stored alias, so SQL remains a superset. The existing Java
ASCII-word and original-source boundary checks remain the admission authority. SQL substring
matches such as `Li` in `client` can over-include and are rejected by Java. Many such canonical
substring matches can still require multiple pages even when Java admits none.

Missing bundles, older versions and old-binary renames take the existing SQL normalization and
non-ASCII/bracket fallback. No trigger is needed, and rollback-compatible writers keep working.
The 200-row keyset page is a materialization bound, never a corpus/admission cap: full pages
continue until exhaustion or the existing combined 20 admitted-identifier limit is exceeded.
Visibility stays owned-or-shared within the control-derived organization ceiling; archived and
processing-restricted people and archived companies remain excluded.

`AiIdentifierKeyBackfillRunner` is a fixed-delay background worker, never an application runner or
readiness dependency. A dedicated single-thread scheduler waits 30 seconds initially and between
invocations, then resumes at most four 100-row windows across active workspaces. Each window uses
the owning-workspace/id index and applies its limit before evaluating freshness or processing
eligibility. Every examined row advances the cursor, including current, archived, restricted and
nameless rows. A mostly-current corpus therefore cannot turn one window into a full-table scan.
Workspace discovery is keyset-paged, and the dedicated thread installs each workspace's real
placement before opening its transaction. It rejects placement changes before reusing a cursor.
CAS statements have a five-second query timeout, and each transaction has a ten-second deadline;
contention rolls back the page and retries on a later traversal without delaying other scheduled
maintenance. There are no session-wide timeout changes, domain events or timestamp changes.

Per-page logs report examined, written, skipped and observed pending rows. Workspace completion
logs accumulate those bounded observations; they are not a point-in-time pending count under
concurrent writes. No page performs a full pending count. The optional exact `pendingCount`
diagnostic remains a separate full-corpus query. Failures log only workspace id and exception
class. Restarts safely begin from zero; traversal wraps to revisit races while fallback keeps
coverage. Drain old writers, allow reconciliation to complete and check an exact pending=0
before claiming steady-state retrieval cost. A canonicalization, eligibility or JDK Unicode change
requires a version bump and reconciliation; do not silently reinterpret current stored keys.

The real-MySQL mostly-current regression (2026-10-16) populated 50,200 people with only the last
bundle stale. Two consecutive invocations each examined 400 owned ids in four windows, with 1,204
handler reads and no CAS or full pending-count calls. `EXPLAIN ANALYZE` used the existing
`idx_person_workspace` range before `LIMIT 100`, followed by at most 100 primary-key lookups per
window. The cursor advanced to 400 then 800. These are deterministic row/call bounds; full-sweep
duration still grows with the owned population.

Name-writing CSV inserts and seeder flushes retain their enclosing transactions and generated-id
ordering while using at most 100 rows and a conservative 1 MiB estimated payload per chunk.
`RecordInsertBatches` covers every variable-width insert binding, both capped aliases and the source
snapshot, with UTF-8/escaping/statement headroom. The bulk regression previews and commits 5,000
255-character U+FDFA-heavy names; the long keys use fallback instead of expanding a single packet.

This design bounds rows **returned** and identifier SQL round trips by canonical matches, not
rows examined: containment still scans the visible corpus. Stale and explicitly unkeyed rows retain the legacy
pagination cost (the 50,200-row unbackfilled regression requires 252 mapper calls). The cost
integration fixture exercises 200, 5,000 and 50,200 Japanese names, ASCII/Japanese and maximum-size
turns, real NFKC mentions, production `EXPLAIN ANALYZE`, and resolver plus durable queue duration
inside the established membership/session lock order. A second connection proves contention and release. Timing is
reported, never asserted against shared-host thresholds; deterministic page/row counts are asserted.
Membership and session acquire/hold intervals are reported separately through commit, alongside the
complete queue transaction (real authorization, message/turn writes and scope reads; audit and
realtime delivery use fixture mocks). Acquire timings include lock-query/round-trip latency; queue
and hold timings include the two-connection contention probes.

Measured locally on 2026-10-16 UTC with MySQL 8.4, Java 26 and format 2 on the shared four-CPU,
approximately 9 GB development host. Every observation used one identifier SQL call. Unrelated
turns returned zero rows; one real NFKC mention returned one row, and two records sharing a
canonical name returned two rows. These are fixture observations, not latency targets.

| Japanese-name population | ASCII unrelated resolver ms | Japanese unrelated resolver ms | 16,000-UTF-16-unit Japanese turn resolver ms |
| --- | ---: | ---: | ---: |
| 200 | 10.501 | 6.102 | 24.197 |
| 5,000 | 10.685 | 10.623 | 259.125 |
| 50,200 | 67.280 | 64.655 | 2,511.569 |

At 50,200 names the complete queue transaction and membership/session hold intervals were:

| Unrelated turn | Membership acquire ms | Session acquire ms | Membership hold ms | Session hold ms | Queue ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| ASCII | 0.492 | 0.651 | 195.713 | 192.794 | 199.482 |
| Japanese | 0.370 | 0.593 | 764.525 | 761.902 | 766.992 |
| 16,000 UTF-16 units | 0.514 | 0.578 | 3,324.382 | 3,321.911 | 3,327.455 |

After adding two canonical-equivalent names (50,202 rows), `EXPLAIN ANALYZE` examined all 50,202
person rows in one scan loop and materialized zero unrelated candidates or two mention candidates.
The complete explain executions took 110 ms and 62.6 ms respectively. The two-row resolver took
62.987 ms; queue time was 254.795 ms, with membership/session holds of 252.036/249.547 ms. This
confirms bounded retrieval for this corpus while exposing the remaining linear scan and long-turn
cost. Other distributions, especially many substring matches or unkeyed names, can still paginate.

### Ask Connex member identifiers

The masked assistant namespace includes `MEMBER` (`{{M1}}`) alongside person, company, deal,
email and phone identifiers. A member's nonblank display name is protected; username is protected
only when it supplies the existing blank-display-name fallback. Exact raw values retain the shared
cross-kind deduplication rule. No member resource handle or new model-visible read field is added.
Short symbol-only member labels containing a non-ASCII symbol, including a single emoji, are
registered for irreversible `[redacted]` replacement and matching outbound scanning, never reversible
tokens. Short labels made only of ASCII punctuation or whitespace remain outside the dictionary:
masking a member named `.` or `?` would otherwise destroy ordinary punctuation. This is a bounded
residual; it does not exempt non-ASCII symbol labels.

Before memory preparation or compaction, the loop registers every active member label from the
exact current workspace through `WorkspaceService.getMembers`. Directory labels are match
candidates, not reversible bindings, and the directory is never serialized to a provider. Queue-time
member provenance records only boundary-aware mentions within the combined 20-identifier limit,
without adding resources; record mentions retain their existing canonical mention gate. Directory read failures abort before provider dispatch; the
candidate dictionary is never truncated and a failed masked request is never retried unmasked.
Approved `UNMASKED` disclosure retains its existing posture. Unknown provider placeholders still
produce demasking warnings and fail closed in every disclosure mode.
History and compaction register MEMBER provenance through the same privacy-aware candidate path,
including legacy metadata. They do not issue tokens merely to read provenance. The per-message
masked replay leak check runs only in masked disclosure; universal contact and special-care screens
still apply to unmasked text.

Stored prose can name a member who has renamed themselves or is still pending. Notes, activities,
task descriptions, skill activity reads and personal work-brief commitments therefore register every authored `user:` reference label
from reader-authorized content before assistant screening or truncation, regardless of current name
or membership status. Repeated references to one user and reference-style Markdown retain all
labels, including both authored HTML-entity spellings and decoded labels. Both member provenance
and reader visibility now use the pure `ReferenceContentReader` shared by `ReferenceService`.
It parses original source with pinned CommonMark Java 0.30.1, the GFM tables extension and the
footnotes extension, with block and inline source spans. It does not globally decode the document
before parsing. Resolved links, images and all link-reference definitions (including unused and
duplicate definitions) contribute internal targets; HTML `href` attributes do not, matching
`NoteContent`'s `skipHtml` posture. The closed vocabulary remains `user`, `person`, `deal`, `company`,
`note`, `file`, `task` and `activity`. Nonpositive, invalid or overflowing internal IDs retain an
unsafe signal instead of disappearing from visibility checks.

Member label spans exclude link syntax, reference keys and destinations. UTF-16 source offsets
retain original line endings and container gaps; logical and rendered label readings supplement
the authored spelling. Table-cell offsets are mapped back across escaped pipes because the
extension removes those backslashes before inline parsing. The collector covers the following
constructs. Examples show stored source; `\n`, `\r` and `\t` denote line breaks or tabs.

The package-private compatibility label APIs preserve legacy source-spelling/entity-decoding
readings and their ordering, and add parser-resolved source labels. Their results are a conservative
superset, not an AI admission decision. The completeness-aware AI reading additionally includes rendered labels with code,
emphasis and escape syntax removed, sharing the same authorization target groups.
Legacy definition-key suppression applies only to the source-label view: when the AST instead
resolves that bracket as a shortcut, its label remains in the AI masking view with the same
authorization group. Malformed definitions never remove later valid fallback candidates.

| Covered construct | One-line example |
| --- | --- |
| Canonical inline tokens, including label code spans and backslash escapes | ``[Alice \` `]` Former](user:42)`` |
| Canonical destination separators | `[Alice](user:42)`, `[Alice](user&colon;42)`, `[Alice](user\:42)` |
| Authored and HTML-decoded label spellings | `[Alice &amp; Bob](user:42)` registers both `Alice &amp; Bob` and `Alice & Bob` |
| Full reference links | `[Alice][m]\n\n[m]: user:42` |
| Collapsed reference links | `[Alice][]\n\n[Alice]: user:42` |
| Shortcut reference links | `[Alice]\n\n[Alice]: user:42` |
| Failed-inline and failed-definition shortcut fallbacks | `[Alice](not a link)\n\n[Alice]: user:42`; `[Alice]: welcome back.\n\n[Alice]: user:42` |
| Prose and paragraph-continuation shortcuts followed by a colon | `Hello\n[Alice]: welcome.\n\n[Alice]: user:42` |
| Definitions at document start, after a blank line, or after another definition | `[m]: user:42\n[n]: user:43\n\n[Alice][m]`; `Hello\n\n[m]: user:42\n\n[Alice][m]` |
| Blockquote and list-container definitions | `[Alice][m]\n\n> [m]: <user:42>`; `[Alice][m]\n\n- [m]: user:42` |
| Continued definition keys and destinations, with LF, CR or CRLF | `[Alice][m n]\n\n> [m\n> n]:\r> <user:42>` |
| Titled definitions, without decoding the title for member detection | `[Alice][m]\n\n[m]: user:42 "a&#10;b"` |
| Unicode case-folded and whitespace-collapsed keys | `[Alice][SS]\n\n[ß]: user:42`; `[Alice][m n]\n\n[M\tN]: user:42` |
| Independent table-cell and table-row inline contexts | ``| ` | [Alice][m] | ` |\n| --- | --- | --- |\n\n[m]: user:42`` |
| Footnote-body links | `See [^f].\n\n[^f]: [Alice][m]\n\n[m]: user:42` |
| The shared 999-code-point label bound, checked before copying or normalization | `"[" + "😀".repeat(999) + "](user:42)"` is accepted; 1,000 code points are outside the bound |

The retained conservative supplement uses these paragraph-interruption rules when deciding
whether a following definition suppresses a shortcut reading:

| Paragraph rule | One-line example |
| --- | --- |
| Blank lines end paragraphs | `Hello\n\n[m]: user:42` |
| ATX headings, fenced code openers, thematic breaks, block quotes and setext underlines can interrupt | `Hello\n# Heading`, ``Hello\n```java``, `Hello\n***`, `Hello\n> quote`, `Hello\n===` |
| A new list item can interrupt only if nonempty; an ordered item must start at 1 | `Hello\n* item` and `Hello\n1. item` interrupt; `Hello\n* ` and `Hello\n2. item` continue the paragraph |
| HTML block starts of types 1–6 can interrupt; type 7 cannot | `Hello\n<script>`, `Hello\n<!--`, `Hello\n<?pi?>`, `Hello\n<!DOCTYPE html>`, `Hello\n<![CDATA[`, `Hello\n<div>` interrupt; `Hello\n<custom>` does not |
| Indented code cannot interrupt | `Hello\n    code` continues the paragraph |
| Container paragraphs retain lazy continuations | `> Hello\n[Alice]: welcome.\n\n[Alice]: user:42` keeps the first bracket as a shortcut usage |

The compatibility supplement's code and escape handling use one shared sequential delimiter table following
[CommonMark 0.31.2 §§2.4 and 6.1](https://spec.commonmark.org/0.31.2/#code-spans).
Outside code, a backslash escapes the next ASCII punctuation character. An unescaped backtick run
opens code only when a later raw run has exactly the same length; that whole span is literal,
including its backslashes and brackets. Unmatched runs stay text. The next matching run is
precomputed once, so no opener rescans the source. Code-aware structural readings retain the
listed blank-line and block-start boundaries. Canonical local entries use the same transitions
without depending on preceding block state; the extra no-code reading remains conservative.
Unambiguous explicit reference keys
remain keys; ambiguous bracket readings stay independent. The scans and fixed-number reading merge
remain linear in stored prose length, with the label bound applied before extraction. Member
references retain shared, unmodifiable target sets; ambiguous definitions never expand into a
Cartesian product of labels and ids. Group deduplication and visibility matching use identity so
each shared target set is traversed once per phase rather than once per label. Member
provenance writers and readers use the same code-point bound, including supplementary characters.

The parser closes the definition-layout and shared-reader residuals in
[#2071](https://github.com/itkla/connex/issues/2071) and
[#2072](https://github.com/itkla/connex/issues/2072): zero-indent LF/CR/CRLF continuations,
blockquote/list containers and raw-HTML block termination use actual parser semantics. Tables and
footnotes have separate inline/container contexts; differential fixtures cover backend discovery
and frontend rendering.
The pinned table extension disagrees with GFM on lone-pipe body rows and unequal header/delimiter
column counts. The reader therefore unions links and definitions from a second bounded parse
with footnotes but without tables. A destination census independently locates literal syntax throughout the **full original
body**, including source rejected by parser admission. Only census destinations use backend HTML-entity
decoding followed by the AI screen's shared `CanonicalText` literal projection; both AST parses
still receive unchanged original source. Composed UTF-16 source maps retain each hit's original
occurrence through entity decoding, NFKC, case folding and default-ignorable/delimiter removal.
The census recognizes all eight internal types, backend Unicode decimal digits, literal/escaped
colons and named/decimal/hex entity spellings. Thus `user:４２`, `user:٤٢`, `tasK:42`, full-width
type/colon spellings, `user\&colon;42`, `user&#92;:42` and `u&#115;er:42` cannot hide missed links.

The census visits only literal ASCII destination anchors in the original source: `](` for an
inline link/image, `<` immediately before an autolink destination, and `]:` for a definition.
The `]:` anchor has no physical-line or key-opening requirement. Indented definitions inside
footnotes and other container layouts must still contribute conservative hits when both parses
miss them.
After `](` or `]:`, it accepts spaces/tabs, at most one LF/CR/CRLF continuation with literal
blockquote/list prefixes or indentation, and an optional `<`. Entity decoding and normalization
apply only to the following destination; they cannot create syntax anchors. Cached forward
`indexOf` cursors locate anchors, and disjoint candidate runs keep normalization off ordinary
prose. Thus `Note:2026/10/16`, `Note：2026年10月16日`, `(note:7)`, `Deal:3件`, `Task：①資料作成`
and `activity:12 logged` remain prose. External URL substrings also remain prose, including
`https://example.com/(docs)/:user:42` and `[Help](https://example.com/path]user:42)`; the reader
no longer attempts a separate URL-exclusion grammar. Literal `<` anchors still protect
`https://example.com/<note:42>` and `a://<user:42>`, even inside the table disagreement.
Only an actual link, image or definition destination from either parse explains a census hit, by the
same type, integer ID and original source occurrence; compatibility scans cannot explain one.
An unrelated definition with the same ID cannot establish coverage for a missed table link.
Bare type/ID text in labels, titles and reference keys is not a census hit; literal destination
anchors inside such text remain conservative candidates. Every unexplained hit
joins the visibility inventory, so invisible destinations are still redacted when both parses miss
them. A visible destination retains the existing scoped authorization checks.

Any unexplained `user:` census hit makes the member reading incomplete. Original queued text
is refused at identifier admission; stored AI prose degrades per item as described below. Nonpositive or overflowing census IDs retain an unsafe signal. Context-free
snippets replace the **whole snippet** with the existing unavailable placeholder for unexplained,
unrepresentable or incomplete readings, or `a note` when a complete reading contains a note target.
Canonical tokens in otherwise complete content still flatten individually. Literal destination
syntax in code spans, fenced/indented code, examples, HTML and table cells also counts: this
deliberately redacts some non-rendered references. A malformed
`user:7)` definition followed by a valid `user:42` definition retains its conservative user-42
alias but makes its member reading incomplete because user 7 remains unexplained. `user:(7` is not a destination
token; its valid fallback shortcut remains maskable. The differential fixtures cover both table
disagreements across all eight types, including blockquote/list containers.
Autolink-literal and strikethrough artifacts are deliberately absent: literal autolinks use
HTTP(S)/mailto rather than internal schemes, and strikethrough introduces no destination or
separate inline context. Links within tildes and task lists remain covered by core inline parsing.

CommonMark is authoritative for resolved syntax, with the existing bounded conservative readings
retained additively inside the shared reader. Canonical source readings still apply in code/HTML,
accept `&colon;` and `\:` separators, and preserve oversized non-spec definition-key protection.
Duplicate definitions still retain every possible member ID even when the parser resolves the
first definition to another type. Unclosed HTML and other deliberately conservative readings can
therefore contribute candidates without an AST link. Those candidates can cause extra redaction
or masking; they do not grant authorization. The fixed-number supplement runs over the full text
even when parser admission fails, and shared target sets avoid label/id Cartesian expansion.
The same bounded definition/destination supplement inventories all eight target types, including
indented or container definitions that parser/renderer extension differences could otherwise hide.
Its added definition readings require a digit immediately after the destination separator,
including entity-decoded digits, without a physical-line requirement. Thus `[Owner]: User: Hanako`
and `[メモ]: Note: 来週確認` remain prose; a digit-starting invalid or overflowing ID retains an
unsafe signal. The original definition inventory remains the compatibility floor.
Malformed angle destinations stop at impossible internal-reference syntax rather than repeatedly
searching the remaining body for a closing angle.

Parser admission is capped at **262,144 UTF-16 units per body**, without truncation. Upstream block
and inline nesting limits stay at **100**; an AST reaching saturation is incomplete, including
depths 100 and 101 (99 remains readable). Tables allow at most **256 columns** and **8,192 prospective
cells** across the body. A conservative source scan bounds padded-cell expansion before parsing;
separator rows, edge pipes and pipe-heavy prose/code/HTML also consume that budget, and the AST
checks exact column counts. Parser or source-span failures are incomplete too. These are resource
bounds, not a claim that every possible upstream input has a universal linear-time proof.

A linear preflight also caps prospective bracket work at **16,777,216**: at each raw `]`, it adds
the cumulative number of raw `[` characters seen so far. This intentionally overcounts escaped,
code and balanced brackets. It upper-bounds the pinned parser's repeated traversal of prior
brackets on every successful link; even below the input cap, 131,000 unmatched openers followed
by 26,000 empty links would otherwise cause about 3.4 billion iterations. Exceeding the budget is
incomplete (`bracket_limit`), while the existing 50,000-opener/one-link regression remains admitted.

The pinned footnotes extension has two known renderer differences: its label scanner does not
handle escaped closing brackets, and its continuation indentation is absolute inside containers.
Until upstream supports those layouts, all container footnotes, footnote labels containing a
backslash or opening bracket, and ordinary-definition fallbacks whose key starts with `^` are
explicitly incomplete (`unsupported_footnote`). When this is the only incomplete status and the syntax census finds no internal destination
(including no unrepresentable ID), the reading remains complete and preserves harmless prose.
Bodies with internal destinations keep the whole-body fail-closed behavior. Escaped footnote definition markers are checked in a linear
source pass, independently of the AST: a missed footnote can merge paragraphs into a code span
and leave no definition node to diagnose. Literal destination syntax in source examples may therefore fail closed too.
The escaped-label, paragraph-interruption and nested-blockquote reproductions are
also exercised against the frontend renderer. Normal root footnotes remain supported; the
compatibility inventory still retains targets and member candidates from refused bodies.

Member labels retain the existing **999-code-point** bound, including supplementary characters.
A resolved member link or image whose authored label exceeds that bound makes AI member admission
incomplete instead of silently dropping its label. Destination visibility discovery remains
independent of the label bound; genuinely empty labels remain admitted.

Every incomplete visibility reading replaces the whole body with `(unavailable reference)`, also
on reader-authorized AI projections and context-free snippets. No unchecked prefix or suffix is
returned. Structured references retain their independent existing visibility checks. The
Micrometer counter `connex.reference.content.incomplete` uses the global registry of this static
reader and counts **reads**, not requests, users, items or AI admission decisions. Parser reasons
increment once per incomplete `read()`, including visibility, audit and snippet reads/re-reads.
The bounded `member_provenance` reason increments once when an otherwise complete reading's lazy
member analysis first detects unsafe/unexplained member provenance; visibility/snippet paths do
not compute it. Separate readings of the same content can increment again. Tags contain only the
fixed reason vocabulary, never content, labels, IDs or workspace data. This diagnostic is not a
count of refused provider requests; retaining the static reader avoids constructor changes solely
for metrics.
Queue identifier admission parses the original complete text and refuses incomplete readings with
the existing `identifier_text_unsafe` error before identifier queries or provider dispatch.
For stored reader-authorized prose, incomplete member readings instead replace only that item's
AI prose with `(unavailable reference)` and register no partial labels. Record/list tools, scope
activities, work commitments and complete/reschedule task outcomes apply this projection before
screening/truncation. Other items and non-prose fields continue normally. Fresh write results and
stored-outcome replays use the same pure projection and do not throw because one description is
incomplete; durable source/outcome text remains unchanged. An unrepresentable `user:` destination
follows these same queue-refusal and per-item-degradation paths.
An invalid non-member destination does not suppress valid member provenance from original queue text.
There is no cross-request content, AST or authorization cache. Per-group member visibility,
500-ID batches and combined identifier limits remain unchanged; persisted references and mention
recipients still use only
the existing canonical write grammar and its 100-reference cap.

Within the input cap, content with none of `[`, `]`, `<` or `&` returns an empty
complete reading without census or AST parsing. Member compatibility scans, authored/logical/
rendered label extraction and reference grouping are invocation-local and lazy: only member/AI
APIs request them, never reader redaction or context-free snippets. No cross-request cache is added.
Colons, full-width colons and backslashes alone need no Markdown parsing, so timestamps, IPv6,
JSON audit strings and link-free tables with time text retain their original prose. The `]` guard
keeps a literal inline destination anchor detectable even when its opening `[` is absent.

Residuals are the retained compatibility supplement's conservative over-inclusion, literal
Markdown destination syntax in code/HTML, unsupported footnote bodies containing internal
destinations, bounded/unrepresentable inputs failing closed, and future parser/renderer drift.
Bare prose type/ID text is outside the census. The original conservative supplement can still
protect entity-encoded syntax that the renderer leaves literal; it does not create census anchors.
Literal `]:` followed by a digit-starting internal destination remains a conservative candidate
inside ordinary sentences, for example `Status [Q3]: deal:5 closed`. An invisible ID can therefore
redact that whole body, and a user destination can refuse AI admission. This residual requires
digits immediately after the destination colon; `Status [Q3]: deal: signed` and the English
and Japanese heading examples without IDs above remain prose.
Renew the differential fixtures when either dependency changes. Further normalization or Markdown
exotica outside the tested syntax layouts require a separately scoped review.
Current active members are masked everywhere through directory candidates. Prose mentions of
former members outside reference syntax remain outside #1822's guarantee. Full assistant note reads
bypass display previews so a label cannot be cut before this boundary. Shared sessions exclude private notes in SQL before the result limit, including company
notes; private sessions retain the requester's own private notes. These reads retain the existing
workspace/reader predicates and assistant history limit of at most 20 rows; `NoteBodyReadBoundaryArchTest` pins both the exact callers and hydration. This is runtime provenance handling, with no name heuristics or historical backfill.
Person, deal and company assistant note queries also exclude restricted or inaccessible linked
people in SQL before their limit, including inline person references. Ineligible recent notes
cannot crowd older eligible notes out of the assistant's bounded page.

`AiAssistantWriteTool.modelIdentifiers` is a pure stored-outcome projection. `assign_owner` declares
the committed owner label as MEMBER provenance, excluding the removal sentinel. Fresh execution
and replay use that same label without a new directory lookup, including after rename or
offboarding. Member cards and raw stored write outcomes retain their existing content. Final answer
metadata stores both exact member replacements captured during demasking and every known member
candidate contained in the persisted raw text, even when no token was allocated or exposed. Summary
metadata follows the same rule, within the existing count and value-length limits. All echoing write
tools retain raw argument provenance in their server-authored durable envelope and restore it from
both stored proposal and result on replay, without changing public arguments, outcomes or cards.
Proposal provenance remains available when an older binary writes its older result format. Readers reseed that provenance for ordinary history and compaction under
a fresh masking context; no token binding is persisted. In that stored message only, every recorded
member label is replaced and leak-scanned by containment, regardless of label length or adjacency.
Only matches aligned with complete source characters receive reversible tokens. A containment match
that cuts through a character's compatibility expansion irreversibly redacts its complete source
span with `[redacted]`, so demasking cannot replace unmatched characters. Other labels and other
messages retain ordinary short-label word boundaries. Tool-result containment and leak checks
operate per tenant-bearing textual value; fixed tool, tier, status and record-type fields retain
their ordinary matching rules.

Allocation alone never authorizes demasking for any reversible kind: PERSON, COMPANY, DEAL, EMAIL,
PHONE and MEMBER. A binding must remain valid and its exact token must have recorded exposure. Provider adapters derive immutable token evidence
from the decoded tenant-data and tool-result text positions in their final serialized bodies, after
screening, budgeting and truncation. `AiMessage` retains each message's positional origin segments;
native result and repair segments retain theirs. Every serialized string value must match its
recorded provenance; missing, extra, changed or unclassified positions refuse before transport.
Static vocabulary, schemas, image data and provider-authored replay or repair cannot grant exposure.
OpenAI-compatible and scripted providers share the same body serializer; Azure, Bedrock and both
Vertex families apply the same body/provenance validation to their own formats.

`AiProviderDispatch` pairs those exact body bytes with their staged token set. The existing
`beforeSend` callback still commits pre-send budget accounting. Transports promote that attempt's
set through `onResponse` at response headers, including HTTP error responses, before reading the
body, parsing or publishing a delta. A connection failure or timeout before headers promotes
nothing. A rebuilt fallback body carries fresh evidence; retries reusing identical bytes retain
their existing transport behavior. Scripted transport failures follow the same rule, while a
scripted HTTP rejection counts as a response. Only tokens
exposed in an actual dispatch during the current run can demask; earlier dispatches in the run remain
valid, and history inside a dispatched payload counts. A guessed unissued or unexposed token
in a final answer, stream or tool argument follows invented-placeholder refusal and cannot reveal
the name or create an owner proposal. This includes dropped tool rows, oversized attachments,
redaction-only matches and omitted replay messages. Candidate and bound values both participate in
the outbound scan, including short Unicode names, with the replacer's matching rules. These rules
apply atomically to every AI caller, in JSON-ReAct and native tools, with or without streaming. Registered identifiers containing placeholder syntax are irreversible:
they receive `[redacted]`, never reversible bindings. Their placeholder spellings are reserved even
if another identifier already holds that binding, so a literal member label cannot authorize it.

Provider-authored output, including raw and decoded native tool arguments, is checked for unexposed
reversible tokens before envelope/schema validation or repair retention. Complete provider text and
native argument strings receive a conservative, linear Unicode/backslash escape projection before
parsing, repair or replay admission, even when a valid object prefix precedes an offending suffix.
Native call ids, names and thought signatures use the same complete-text check. Encoded unexposed
tokens cannot earn a repair dispatch; the projection is used only for refusal and never changes
retained text. Plain-text completions used for attachment descriptions also refuse escaped unexposed
tokens with a warning and empty output, before the description can be replayed. Noncanonical lowercase or
NFKC-equivalent placeholders are refused with warnings; only the demasker's literal ASCII
syntax (including its optional whitespace) can re-identify a value. Structured answers and native calls
refuse invented references before repair or execution. Internally the demasker returns
`[unknown reference]` and warnings; callers discard warned results. Streaming callbacks immediately
withdraw durable partial content and send a reset on detection. They defer the malformed-output
exception until completion so a
subsequent timeout or disconnect retains its provider classification with no partial to reconnect to.
Terminal persistence also purges partials for `malformed_output`. Provider stream adapters deliver
new native-call field and separate/flagged reasoning fragments through a screening-only callback
with stable per-field identities; public content is delivered only once through its content callback.
Incremental screening continues for public, reasoning and native fields after publication reaches
its 16,000-character bound. Only an already detected reference, whose partial has been withdrawn,
stops screening. Parser checkpoints retain semantic state instead of field history: an open record
label advances an alternate downstream checkpoint, and completed links restore that checkpoint
without replaying the label. Equal checkpoints are shared; the 16 projection passes and convergence
probe bound the parser depth. Unicode reduction retains only incomplete escape prefixes. Ordinary
field length never consumes a screening budget.
A single open placeholder candidate exceeding 16,384 projected characters, or an incomplete nested
Unicode escape suffix exceeding 16,384 reduced characters, fails closed and withdraws immediately. This
bound measures projected UTF-16 units and unresolved reduced escape units, rather than cumulative
encoding bytes. It exceeds the durable publication limit and accommodates long whitespace/escape forms while
bounding unresolved candidate state. It never discards a candidate to resume publication.
Refusal is sampled after each complete callback, matching the complete-text screen when a completed
link removes a speculative target match; the overlong-candidate refusal is permanent.
Malformed-attempt reset creates fresh screening state and permits repaired output from offset zero.
After truncation the answer projector retains subsequent content for final validation; invocation
also screens the complete provider result, including hidden fields and text beyond the publication
bound, before parsing or settlement. A later timeout/disconnect retains its provider classification,
with no durable partial if any callback detected a reference. Final validation remains independent.
Hidden fields never enter the answer projection. Both raw and decoded content receive the same
check, including JSON whitespace escapes within a placeholder. Incomplete lowercase and NFKC-equivalent token prefixes use the same
normalization and source offsets as token validation, and are withheld across streaming flushes.
Context-free invented-reference checks require braces for all kinds, so
ordinary vocabulary such as “Apple M1” and “BMW M1” remains valid. Such output cannot earn a repair payload or exposure through replay. Static server
text is excluded only in its own fields; a collision never exempts a member label in tenant text.
Native call ids, names and thought signatures receive the same unexposed-token check as arguments
before validation, persistence or replay. Replaying any provider-authored native call field cannot
grant exposure, even when it contains a bound placeholder. The dispatch exposure projection excludes
the entire call by position and retains the server-authored tool result; the complete replay payload
still passes the ordinary byte budget and outbound leak scan. Previously exposed tokens remain valid
inside opaque fields without altering those fields.
Final server-authored masking markers `[redacted]` and `[omitted by policy]` are excluded by
position from MEMBER leak matching. Genuine source occurrences are replaced before these markers
are inserted; a member named “Redacted” receives no label-wide exemption. Strict provider-authored
scanning keeps its existing checks. Outbound text retains its origin independently of serialization:
server-static system directives, repair instructions, schema keys and validated catalog tool names
never participate in MEMBER raw-name scanning. Tenant messages, stored content and masked tool results
are scanned as tenant data. Replayed native ids, arguments and repair model output remain provider-authored, with a strict
MEMBER scan of raw and JSON-decoded text. Opaque thought signatures retain placeholder checks
without MEMBER-only raw-name matching, as described below. The masker
records exact UTF-16 spans only when it writes a replacement marker or placeholder; schema-repair
segments carry those offsets through JSON escaping and message assembly. MEMBER scanning neutralizes
only these recorded insertions while preserving JSON decoding. Provider-authored text that merely
looks like `[redacted]` or `[omitted by policy]` receives no exemption, including when the same field
also contains genuine masker insertions. This insertion provenance is request-local and never
persisted. Only tenant-data segments may grant dispatch exposure; provider text and static vocabulary
cannot authorize a hidden binding. Server-created markers in tool results retain their positional
exemption. Unclassified repair text defaults to tenant data. Field projections are built once per scanned segment and reused
across the complete candidate dictionary. Tool-result provenance retains the JSON pointer of each originating textual field; containment
never extends to unrelated sibling fields. Unscoped legacy declarations retain ordinary
boundary-aware matching. For persisted provider-authored text, provenance
includes exact demask replacements independently of adjacency, supplemented by raw candidates
matched with the same boundary-aware member matcher as user provenance. In space-delimited scripts,
Unicode letters and digits are word characters; underscores and punctuation are separators.
Han, Hiragana, Katakana, Hangul and Thai need no boundaries. MEMBER replacement and outbound leak
scanning use this same boundary rule for labels of every length, so `Иван` matches `Иван пришёл`
but leaves the distinct surname `Иванов` intact when that value is MEMBER-only. Values registered
under several kinds retain the union of every applicable protection, independently of which kind
issued their shared token first. A member named `Acme` cannot weaken COMPANY residual redaction or
containment scanning of `AcmeCorp`. Recorded MEMBER provenance cannot promote another kind's
residual-only match into a reversible token, including equal source spans beside accented letters. Checkpoints restore those kind registrations along with bindings.
Legacy static-text exemptions apply only to non-member policies; MEMBER matching retains its
origin-scoped rules. Other identifier kinds retain their existing rules. Exposure gating follows the raw value's MEMBER role too: a PERSON,
COMPANY or DEAL token sharing that value requires dispatch exposure even if allocated before member
registration. Invalidating a binding with a placeholder-shaped label does not erase that MEMBER
association; its opaque native fields remain subject to invented-placeholder refusal. Dispatched tokens of every kind are remembered so later member registration preserves
legitimate exposure. Non-colliding existing kinds keep their existing demasking contract (#2056).

Native call admission screens ids and arguments with the same strict raw-member predicate as replay,
using identical raw field values, without an extra JSON encoding of ids at admission. The shared
segment screen checks raw text and decoded JSON fields with the segment's recorded insertion spans
and candidate snapshot before any AUTO mutation can execute. A refused call is malformed and retains any existing demask
warnings, so raw-label refusal cannot make an invented reference repairable. Tool reads can discover authored
former-member labels after admission; each admitted call therefore freezes its raw-member candidate
set for replay. Placeholder validation uses the live context on every provider-controlled field.
Validated catalog tool names are fixed vocabulary. `thoughtSignature` is opaque provider state:
MEMBER-only raw-name matching does not interpret bytes such as `/Li+`, but placeholder and exposure checks remain
mandatory. Provider-authored fields never establish exposure.

Server-built tool-result segments are marked structurally: their keys are schema positions and their
decoded scalar values remain member-bearing. This includes JSON-ReAct tool-result messages and
native tool-role results; arbitrary JSON-shaped user messages retain full-text scanning. Successful `find_tools` results are
server-static catalog declarations, revalidated against the closed catalog before budgeting. Their
values keep fixed vocabulary on both protocols; refused loads retain ordinary result masking. The current
result producers were audited, including nested projections: record/history/schedule reads, scope
and work-brief reads, skill-plan evidence, tool discovery, and every write tool's `modelOutcome`.
Their exported keys are server constants or closed schema fields. Record-id lookup maps remain
internal; tenant labels, custom text and names are exported as values, never map keys.

For raw-text provenance, `Bob_team` records `Bob`,
while raw `xBob` and `client` (for `Li`) record nothing: the provider already authored that text,
and short names must not corrupt ordinary words. Exact replacements such as `x{{M1}}` retain their
precise member provenance, including through echoed write arguments, independently of these raw-text
rules. At queue time, the shared member-reference collector reads labels and user targets from
the original text. Distinct target ids are checked in workspace-scoped batches of at most 500 against
active or pending membership. An authored or decoded label is registered when any of its possible
targets is visible, preserving conservative masking for ambiguous keys. The combined identifier
budget still refuses excess labels with `identifier_limit_exceeded`. An unrelated invisible target
that collapses the reader-redacted body cannot suppress an authorized member label. Invisible user
targets contribute no reference labels. These labels are
persisted in user-message `memberIdentifiers`, deduplicated with directory mentions within the
combined 20-identifier cap, without adding member resource handles. Blank and whitespace-only
reference labels are excluded before candidate construction. Ordinary queued member provenance
carries `matching:"mention"` and replays with the boundary-aware matcher, so `[Li](user:42)` does
not enable containment inside an unrelated word such as `client`. Exact suggestion-click provenance
retains containment; when the same label is also resolved as a mention, the exact demask provenance
takes precedence. The optional matching field is ignored by older metadata readers.
Recorded provenance protects its
originating field after a rename and fresh-context replay. Exposure controls demasking only, never replay provenance. Final answer metadata also retains the
raw MEMBER provenance of each persisted follow-up suggestion separately at `/suggestions/N` in
`memberIdentifiers` (answer text uses `/text`), using the original parsed paths even
when filtering trims, removes or deduplicates suggestions. When the next user text exactly equals a
persisted suggestion of the session's latest answer, queueing copies only that suggestion's own member provenance
into that user message's `memberIdentifiers`, within the combined 20-identifier limit. This survives
rename, offboarding, compaction and fresh-context replay without inferring a suggestion click from
similar or edited text. The latest-answer read uses the existing authorized session mutex and exact
workspace/session predicates, with no additional row lock or transaction boundary.

Stage and tag names remain workspace vocabulary outside the masked identifier namespace by design.
Their exemption adds no bypass: text colliding with a registered identifier still receives ordinary
masking, screening and leak detection. The P1.1 hold on member-name string classes is lifted only
when those labels enter the MEMBER boundary above; unrelated P1.1 scope is unchanged.

`complete_task` and `reschedule_task` also declare every authored member-reference label in their
stored description snapshots. The task delegates hydrate those descriptions through the existing
reader-visibility boundary before storing the outcome. Fresh projection and replay register the
labels as candidates before masking, including labels authored before a rename and references to
pending members. MEMBER identifiers carried by tool results register as candidates without eager
allocation; actual dispatch exposure remains required for demasking. This adds no directory lookup,
mutation, outcome field or token kind.

Answer, user-message and summary writers put raw MEMBER provenance only in the separate
`memberIdentifiers` field, with the same `{kind:"member",value:...}` entry shape in all three forms.
Unmasked turns retain directory labels only for boundary-aware raw-output provenance, without
masking bindings or MEMBER leak-scan candidates. Their answer and summary metadata records matching
raw labels without changing the output, so a later masked turn can protect replay after a rename
or offboarding even when the directory no longer supplies the old label.
The existing `identifiers` array never gains a `member` entry. New readers read both fields and
retain old-shape conversation compatibility. Older releases ignore the additional field and replay
without member masking, exactly their pre-#1822 behavior; automatic or manual rollback remains
compatible. No writer flag, reader-first rollout, rollback floor, migration or historical rewrite is required.

## Image/media exception

Pixels cannot be masked like text. Image-powered AI is an explicit policy exception and must use `AiInputImage` plus all of the following:

- conservative byte/dimension/decoded-memory admission;
- a static prompt assembled through `PromptAssembly`;
- `AI_USE` and all provider/no-training gates;
- customer-facing disclosure for the feature;
- metadata-only audit;
- review-only output rather than silent autonomous mutation;
- direct embedded bytes, never a provider-fetchable URL.

Hold the established global, per-organization, and estimated-memory media leases through provider parsing.

Business-card image fallback has additional rules in `docs/backend/BUSINESS_CARD_SCANNING.md`.

## Provider egress

Provider requests are bounded, redirect-free, and destination-validated immediately before every send.

- Bedrock destinations derive from the closed reviewed region model.
- Azure OpenAI uses reconstructed HTTPS URLs on approved Azure OpenAI hosts.
- Vertex uses the exact supported model/location combinations and the established Google authentication path.
- OpenAI-compatible endpoints resolve through `AiEndpointAddressValidator` before every send and honor the organization-controlled internal-endpoint posture.
- Fixed-provider clients pin the resolved final address and share one monotonic deadline across resolution/auth/model/provider phases where the adapter requires it.
- Redirects remain disabled.
- NAT64/network-specific translation prefixes must be explicitly configured so translated addresses are classified correctly; ambiguous organization-controlled IPv6 that could target blocked IPv4 fails closed.
- A new provider is implemented behind `AiProvider`, registered through `AiProviderRouter`, and receives the same gate/masking/audit/deadline/address-validation treatment.

Do not add remote-image fetching or place provider I/O inside a database transaction.

## Scripted provider test seam

`ai/provider/scripted` holds a fixture-driven `AiProvider` that **replaces** `OpenAiCompatibleAdapter`, so agent trajectories can be rehearsed end to end with no provider credential and no network. It answers under the existing `openai_compatible` provider id deliberately — that id is a closed set in both a database `CHECK` constraint and `AiProviderConfigService`, and a test seam is not a reason to weaken either — and `AiProviderRouter` refuses duplicate adapter ids, which is why the real adapter carries `@Profile("!" + ScriptedAiProviderProfile.NAME)`.

### Five layers keep it out of a deployed instance

Do not remove one because another looks sufficient; `ScriptedAiProviderArchTest` fails the build if any of them goes.

1. The `ai-scripted-provider` Spring profile on `ScriptedAiProviderConfiguration`, negated on `OpenAiCompatibleAdapter`.
2. `@ConditionalOnProperty` on `connex.ai.scripted-provider.enabled`.
3. Four refusals in `DeploymentProfileValidator`, reached from `DeploymentProfileEnvironmentPostProcessor` before the application context exists: the profile beside any declared `connex.deployment.profile`; the profile outside `dev`/`test`; the profile without the flag; the flag without the profile.
4. The flag on `POSTURE_KEYS` and on every edition's forbidden-key list.
5. **No script ships in the artifact.** `backend/src/main/resources/ai/scripted/` does not exist and nothing is read from the classpath: `ScriptedAiScriptLoader` reads only `connex.ai.scripted-provider.fixture-dir`, which must name a readable directory holding at least one `*.json`, or the context fails to start. An instance that defeated layers 1–4 would still answer nothing.

Scripts live on test trees only — `backend/src/test/resources/ai/scripted/` for backend trajectories and `frontend/test/e2e/fixtures/ai-scripted/` for the CI browser stack. Never add one under `src/main`.

In a test, activate it with `@ActiveProfiles({"test", ScriptedAiProviderProfile.NAME})` plus `connex.ai.scripted-provider.enabled=true` and `connex.ai.scripted-provider.fixture-dir`; `AbstractScriptedTrajectoryTest` already sets both, together with the AI master switch.

### The browser-stack recipe

This is the only copy of the recipe; [`FRONTEND_TESTING.md`](../FRONTEND_TESTING.md) and `frontend/AGENTS.md` point here rather than repeat it. The assistant and mission-proposal browser scenarios use the root guide's `dev` boot with the profile and settings below, exactly as the `Frontend — unit & e2e` CI job's backend boot step sets them:

```bash
SPRING_PROFILES_ACTIVE=dev,ai-scripted-provider
CONNEX_AI_ENABLED=true
CONNEX_AI_SCRIPTED_PROVIDER_ENABLED=true
CONNEX_AI_SCRIPTED_PROVIDER_FIXTURE_DIR=<absolute path to the checkout>/frontend/test/e2e/fixtures/ai-scripted
CONNEX_AI_ALLOW_INTERNAL_ENDPOINTS=true
SPRING_APPLICATION_JSON='{"connex":{"ai":{"missions-enabled":true,"missions-single-jvm-confirmed":true,"mission-capabilities":[{"provider":"openai_compatible","endpoint":"http://127.0.0.1:8080/v1","model":"scripted-json","capability":"missions_v1"}]}}}'
```

- **The profile, the flag and the fixture directory** install the seam. Leave the profile and the flag out and the real `openai_compatible` adapter dials the spec's loopback endpoint, so the turn settles `provider_error`; set one without the other, or both without a readable fixture directory, and the boot refuses (layers 3 and 5 above).
- **The AI master switch** defaults to `false` in `application.yml`, and `AiFeatureGate` checks it before provider readiness. Without it the seam boots but every turn is refused, so the spec never reaches send, tools and answer.
- **The internal-endpoint opt-in** is needed because the spec *saves* its provider row through `PUT /api/ai/provider`. The save path resolves the submitted endpoint's address, so the loopback literal the browser suite configures fails `AiEndpointAddressValidator.isFetchable` with a 400 without it — while the scripted adapter never dials the endpoint at all, whatever it says. That opt-in is part of the browser recipe and nothing wider: it is confined to a job declaring no deployment profile, the saas edition forbids the key outright, and on silo and on-prem it is a deliberate operator posture for an internal model host rather than a route to this seam. The trajectory harness needs none of it, because it inserts its row directly and readiness there is shape-only.
- **The mission switches and exact capability declaration** admit the mission-proposal browser scenario on this single test JVM. They bind only the `scripted-json` model and exact endpoint configured by the test and its JSON-protocol fixture; ordinary provider readiness and per-organization governance still apply. Registration provisions the workspace privacy barrier through the normal after-commit `WorkspaceProvisioned` initializer before mission creation.

`ScriptedAiProviderArchTest` pins the whole recipe to that one CI job and step, so a second workflow booting a stack this way fails the backend suite, and it requires this block to carry every `CONNEX_AI_*` setting that step sets and to be the only place the recipe is written down.

### Contracts to honour near this code

- **Any provider adapter must call both `providerAttemptExecutor().execute(...)` (or `.executeStream(...)`) and `providerAttemptExecutor().beforeSend()`, in the real transport's order.** `execute` runs the restriction epoch, the feature gate, the provider guard and the admission commitment; `beforeSend` is the *only* route to the organization budget lease's dispatched mark. An adapter that produces its output inside `execute` without calling `beforeSend` passes every functional test while under-counting real sends. On the streamed path the order matters too: `OpenAiCompatibleClient.sendStream` opens the transport, re-checks cancellation and the caller deadline, and only *then* calls `beforeSend`, because `AiChatStreamingProgress.Observer.onTransportOpen` refuses a turn that is no longer running. `ScriptedAiProviderTest` and `AiBudgetDispatchBoundaryTest` own that boundary.
- **`scripted-json` rehearses the JSON protocol as other provider families and the runtime native-to-JSON degradation path produce it, not as `openai_compatible` ever presents it** — the real adapter declares `JSON_SCHEMA` and `NATIVE_FUNCTIONS` unconditionally, so a real `openai_compatible` target is always native.
- **`ScriptedAiTurnCursor` derives protocol control state from the assembler's structure, never from prompt text.** The serialized prompt carries every system, member and CRM string in the turn, so a substring search for the repair delimiter or the closing sentence would let an ordinary member sentence or a record value move the provider into a state the loop never entered. `AiAssistantPromptAssembler` emits untrusted content only inside a `CRM_DATA_BEGIN` or `USER_REQUEST_BEGIN` envelope that occupies a whole message, and its own directives as bare messages, so the segment's shape decides: a repair attempt is the last bare directive ending in the offending-output delimiter, a JSON closing step is a bare directive whose stripped content exactly equals the complete production closing directive, and a completed tool call is a message-leading envelope whose top-level type is `tool_result`. The script *selector* is the deliberate exception — it rides in the member's own words on purpose and chooses which fixture answers, not which protocol state the loop is in.

### Fixture authoring

- JSON `closing` includes both the ordinary final-only directive and the mission checkpoint-or-report directive, whose opening is `This is the last decision of this segment.` Only the complete, exact bare server-authored directive selects that state; a shared opening sentence or member/CRM envelope cannot. A checkpoint on the last segment decision must declare `closing: true`, including a repair that consumes the last dispatch after native-to-JSON fallback. A fresh segment starts with zero completed tool calls; with only one decision remaining, it immediately uses the ordinary closing directive. Checkpoint settlement queues a fresh claim as specified in [the mission continuation contract](AI_MISSIONS.md#durable-continuation-and-model-projection).
- **A step predicate is `(afterToolCalls, onRepair, protocol, closing)`, and two steps may not share one.** Alternative outcomes at one cursor position are therefore not expressible inside a single script: three failure kinds after the same successful read need **three fixtures with three selectors**, not three steps.
- **`protocol` is a closed set — `native`, `json` or `any` (the default).** It exists because one turn can visit both protocols: a client-error rejection on the first native attempt does not fail the turn, it clears the native state and retries the same cursor position as JSON. A fixture that declares `expectsNativeDegradation` must declare both halves — the rejecting native step and the JSON step that answers the retry — and the loader refuses it otherwise, because a trajectory that can only end in a second rejection rehearses nothing.
- **A tool call's `arguments` must be a JSON object, and the loader parses it.** The provider never re-encodes the value: it becomes the native function call's arguments verbatim, or is spliced into the JSON step envelope as raw JSON. An unparseable fixture value would otherwise surface as malformed model output at trajectory time rather than as a refused fixture at startup.
- **`ScriptedAiRequestJournal` records every request the provider was handed, not only the ones that left.** The provider records before the attempt executor's egress seam, which can still refuse on the restriction epoch, the feature gate, the provider guard or the admission commitment. Assert egress with `dispatched()`; `recorded()` being empty means the refusal happened *above* the provider. Entries are redactions — credentials emptied, images dropped, the live executor replaced — so no provider credential sits in a process-lifetime buffer.

### Trajectory harness

`AbstractScriptedTrajectoryTest` supplies the shared Spring configuration that activates the profile, and `AiAssistantScriptedTrajectoryTest` runs whole turns through it: `AiAssistantTurnService`, the generation worker, `AiChatAgentLoopService`, the real tool executor and write-tool service, and real MySQL. Delegate behavior below the agent loop remains real, and no provider credential exists. An unstubbed verification spy may require a separate test context.

The non-DB loader tests validate both the base corpus and its `missions` subdirectory, including selector disjointness. Mission trajectory declarations bind `scripted-native` and `scripted-json` at `https://scripted.invalid/v1`; the browser recipe above binds its JSON fixture's exact loopback target. Capability-gate tests validate each corpus against its own declarations without starting MySQL.

- **Run it with `./gradlew scriptedTrajectoryTest`, not `./gradlew test`.** The pattern `**/*ScriptedTrajectory*Test.class` is excluded from `test` so this context key does not evict a hot one out of the shared ten-slot cache. `check` depends on the task. In CI, DB shard 2 runs `dbTestShard2 scriptedTrajectoryTest` behind the required `Backend — build & test` aggregator. `ScriptedAiProviderArchTest` asserts both that a required matrix entry supplies the task to an unconditional Gradle step and that every class extending the harness matches the pattern — so a trajectory class CI would silently skip fails the build instead.
- **A golden that writes must load its toolset first.** A turn starts holding only the `core` toolset, so every write tool sits behind a `find_tools` step; the fixtures spell that sequence out and the goldens assert the durable tool order, which is where a regression in toolset loading becomes visible.
- **A journaled request has two authors, and a leak assertion may only read one of them.** The server authors the system prompt, its directives and every masked tool result; the model authors its tool-call arguments, which native replay carries back verbatim and never re-masks. Scanning both halves measures the fixture rather than the pipeline: a fixture that simply never quotes its own seeded record passes trivially, and a real leak in a server-authored field could hide behind it. Assert absence against the server-authored half, and say so in the assertion message. The rule for fixture authors follows from the same replay: `OutboundLeakScan` refuses any payload containing a registered raw identifier, so a fixture's search query must be a distinctive **fragment** of a record's name rather than the whole name. That is a constraint on writing fixtures, not evidence that no raw identifier travels. Raw member
  names are model-authored incoming arguments, rather than an outbound leak at receipt. Native
  calls must nevertheless be safe for verbatim replay: admission applies the strict replay screen
  before any tool executes, and refuses raw member labels as malformed. A masked fixture assigning a member uses a MEMBER token actually exposed in its
  user request, rather than inventing a token or replaying the complete raw label.
- **Proposal freshness lives on the approval path only.** `AiAssistantWriteToolService.approve` refuses a CONFIRM proposal whose target was written in the proposal's own second. Proposing a write and executing an AUTO write never consult `AiAssistantProposalFreshness`, and the read service uses it only for a display-only staleness flag. The harness backdates seeded write targets so a future golden that *approves* is not silently converted into a staleness test — and a golden that means to pin that refusal must therefore not backdate its target.
- **The harness is not transactional.** The loop runs on a worker thread that reloads the initiating identity from committed rows, so every fixture is committed and then deleted, in one fresh organization per test method. A turn that outruns the harness deadline is cancelled before teardown, because a worker still calling the provider would keep appending to the context-scoped request journal and fail the *next* golden's egress count.

## Unmasked disclosure and streaming

`UNMASKED` disclosure is durable fail-closed posture. It may resolve only when the deployment permits it and the exact resolved destination has a current organization-admin attestation. Destination changes invalidate the attestation, and the snapshot is rechecked at provider egress.

Streaming remains inside `AiInvocationService`:

- publish only decoded terminal answer text to user-facing streams;
- persist replay data through the existing exact offset/batch protocol;
- replace durable partial output with screened terminal content in the locked completion path;
- keep cancellation authorization and terminal state database-backed;
- treat JVM transport-abort hooks as an optimization, not the source of truth.

## Read-only missions and durable chat privacy

V232 introduces `record_review_v1` inside an existing Ask Connex session. [AI_MISSIONS.md](AI_MISSIONS.md) defines its durable generation/taint predicate, conservative cleanup enrollment, DSR authority, and drained single-JVM activation boundary. Every ordinary message writer uses the same durable session sequence allocator; every derived execution retains its original privacy token through reads, secondary provider dispatch, writes, and deferred requester publication. Missing barriers and stale tokens fail closed after restart and after purge unblocks.

Missions use the existing invocation, masking, leak scan, organization budget, audit and provider transport. Read-only runtime-1/2 policy narrows the catalog and execution boundary to five read tools, enforces the selected primary record before retrieval/registration/handle resolution (including preclaim validation), and never enables CONFIRM/AUTO or question tools. Linked tasks are bounded evidence without task handles. Runtime 3 adds only the selected-record CONFIRM allowlist described under [Mission proposal authority](#mission-proposal-authority-v238). One `AGENT_RUN` lease covers each attempt; legacy `CHAT_TURN` routes cannot claim or control it.

Mission realtime sends only requester-authorized metadata invalidations, with fresh membership, permission, session and generation checks inside the local fence. Ordinary content frames revalidate their immutable stored turn provenance and source state at send time. Output is fetched through the canonical owner-only report API; no mission model prose is broadcast.

## Assistant tools and collaboration

Native assistant tools are capability-declared transport choices, not a second authorization model.

- Tool definitions project only the static `AiAssistantToolCatalog`, never tenant/customer data.
- **Every declared tool names one `AiAssistantToolCatalog.Toolset`.** `CORE` — record, activity, task, and plan reads, including `list_scope_activities`, `list_tasks` and the `find_tools` meta-tool — is the toolset a turn always holds; the rest group by object family (`analytics`, `schedule`, `write_activity`, `write_content`, `write_pipeline`, `write_followup`) so one family can grow without dragging every other write tool along. The keys are stable wire vocabulary: add, never rename. A declaration without a toolset is refused in the `ToolSpec` constructor, because every catalog view filters on it.
- **A turn starts from `CORE` and widens itself with `find_tools`.** The prompt vocabulary, the strict ReAct step schema, the native function definitions and the raw step guard are all functions of one per-turn `loadedToolsets` set. `find_tools` is a READ-tier core tool whose single argument is a closed enum of the loadable keys (`core` is never legal — it is always held), and the agent loop executes it itself: `AiAssistantToolExecutor` is a stateless tenant-read component, so its closed switch refuses `find_tools` with `find_tools_requires_turn_state` rather than holding per-turn state. `AiAssistantToolsetLoader` resolves the load and returns the **whole** active set, which is why the loaded set is reconstructible from the `ai_chat_tool_call` rows a turn already writes and this needed no migration. The loader does **not** mutate: the loop commits the widening only after `finishTool(..., "executed", ...)` returns true, so a step that settles any other way cannot leave the turn holding a toolset no durable row records. Its `tools` list names only *executable* tools — a reserved declaration such as `get_deal_brief` stays in the rendered vocabulary with its unavailable reason, but advertising it as freshly unlocked would invite a non-recoverable executor refusal that forfeits the step the load cost.
- **`MAX_ACTIVE_TOOLSETS_PER_TURN` is one budget, and the set's own size is its counter.** There is no separate load counter, so toolsets a routed skill seeds and toolsets `find_tools` loads spend from the same allowance. Both over-budget cases are recoverable refusals the model can act on — `toolset_already_loaded`, `toolset_load_limit_reached` — as are `toolset_unavailable_for_skill`, a load outside the turn's offer (below), and `tool_not_loaded`, the loop backstop for a tool name that survived the raw guard. All four settle under `malformed_output`, which is closable, so an escape still spends the closing step. The cap is checked before the offer, so a turn with no load left hears that whichever family it asks for.
- **A routed skill seeds its declared toolsets, inside the same branch that writes the attribution.** `SkillSpec.toolsets` names the non-core sets a declaration's synthesis step starts holding, so a skill does not spend a governance step rediscovering a read it already declared; `AiSkillCatalogTest` refuses a declaration whose `allowedTools` name a catalog tool that `core` plus its own toolsets never reaches. The seed is applied only inside `if (execution.executed())`, beside `persistenceService.applySkill`, because a reader rebuilding the loaded set from `ai_chat_turn.skill_key` plus the turn's `find_tools` rows must see the set the turn really held: a routed-but-unexecuted plan leaves a null `skill_key` **and** a core-only turn. Keep the two writes together, and resolve the seed **before** the attribution row: the key lookup fails closed, so resolving it second would persist an attribution for a turn that then died holding nothing. **That reconstruction is valid only while `ai_chat_turn.skill_version` equals the version the catalog declares for that key today.** Declarations are mutable across releases — a key is stable and additive, its `toolsets` are not — and `AiSkillCatalog` exposes only `find(String key)`, so a turn recorded under an older version cannot be resolved back to the declaration that actually ran. A reader that sees a version mismatch must fail closed rather than reconstruct from the current declaration; a version-pinned catalog lookup is the alternative, and P0.4 must add one before it consumes this contract for anything but a same-release turn. Seeding grants no authority — `requireSkillAuthority` still owns writes — and a declaration may not seed a write family its `authority` or `allowedTools` cannot call: seeding is all-or-nothing over an object family while write authority is per tool, so `SkillSpec`'s compact constructor refuses that declaration rather than let the server offer a write tool whose use settles the turn as a non-closable `tool_outside_skill_authority`. Every shipped skill declares `Set.of()` today, so this is the forward contract Phase 1's write skills consume rather than a change to what current turns send.
- **A routed turn is offered only the toolsets its skill may hold (#1808).** `AiSkillCatalog.mayHold(authority, allowedTools, toolset)` is the one rule: a family is holdable only when every write tool in it is one `requireSkillAuthority` would let the skill call — a tier above `READ` and a key its `allowedTools` names — and a family with no write tool always is. Requiring *every* write rather than *any* deliberately narrows #1808's "allowedTools intersects it" direction, because a family is held whole: **a write skill must be allowed every write in a family to be offered or seeded it**, and when a family grows, every skill holding it must widen `allowedTools` or lose the family. `AiSkillCatalogTest` fails the build on a shipped skill above `READ` left allowed a write tool that no family it may hold contains, so that loss is never silent. `SkillSpec` refuses a declaration that seeds a family failing it, and the agent loop computes the turn's *offer* from it once, from the skill the turn actually runs under: a generic turn (including a routed turn whose plan did not execute) is offered every loadable set, byte-for-byte as before; a routed turn only the holdable ones. The toolset directory renders only offered sets, and `AiAssistantToolsetLoader` refuses any other key recoverably with `toolset_unavailable_for_skill`, so a read-only skill's directory never lists a write family and, if its model asks for one anyway, it is told so and still answers — instead of loading it and ending the turn at its first write with a non-closable `tool_outside_skill_authority`. **Only the directory and the loader narrow.** The `find_tools` argument enum in the strict step schema and in the native definition still names every loadable key on every turn, so the schema stays one function of the loaded set; a narrowed turn's directive therefore says that only the listed sets can load and any other key is refused, while a generic turn's directive is unchanged. The loader, not the schema, is the refusal: never drop its offer check on the belief that a routed model cannot name the key. On routed turns the durable signal of an attempted write-family widening is now the failed `find_tools` row carrying `toolset_unavailable_for_skill`; such a turn no longer ends as `tool_outside_skill_authority`, so do not count that terminal reason to detect write-injection attempts. The offer only narrows: the envelope is unchanged for generic turns and only smaller for routed ones, the budget is still measured from the full reservation, and `requireSkillAuthority` stays exactly where it is as the binding gate for every write.
- **Loading is narrowing; it is never an authorization event.** Every tool in every toolset a turn is offered is already reachable on it, so a load restores reach the taxonomy removed. Inside the agent loop, `requireSkillAuthority` runs **first** and still owns write authority: where both loop-level checks run, a write tool that is both unloaded and outside its skill's declaration settles as `tool_outside_skill_authority`, never as `tool_not_loaded`. That ordering is not the turn's observable outcome for an unloaded name: on both protocols the raw step guard (and on the native protocol the loaded-only definitions) refuses a name outside the loaded set first, as a recoverable repair that writes no `ai_chat_tool_call` row and no terminal reason. End to end, `requireSkillAuthority` is therefore reachable only through a loaded write family, which the offer and the seed rule make unreachable for a skill without authority; it stays as the loop-layer backstop in case either rule regresses, pinned by `AiChatAgentLoopServiceTest` with the offer deliberately bypassed. `requireToolsetLoaded` is raised from inside the `validateReferences` try so the refusal is recoverable and the turn keeps its closing step, and it runs **before** `recordNativeCall`: the refusal leaves the loaded set alone, so replaying a refused exchange for a name the next step's definitions still exclude would make `AiNativeToolRequest` throw an `IllegalArgumentException` — an internal error in place of the recoverable refusal. Keep both orders.
- **The budget is measured once, from `reservationToolsets()`.** Core plus the `MAX_ACTIVE_TOOLSETS_PER_TURN + RESERVATION_HEADROOM_TOOLSETS` weightiest loadable sets, picked by a pure-catalog weight proxy. Re-deriving the budget when a toolset loads would shrink `toolResultBytes` mid-turn and strand tool results the turn had already admitted and executed writes against, so the reservation is deliberately a bounded worst case a turn may never reach. **Adding a tool to a non-core toolset therefore grows the reservation, not every turn's envelope** — but it can still breach the 64k context floor. `AiAssistantPromptEnvelopeTest` measures the true envelope for every reachable loaded set on both protocols, including core-only, and fails with the numbers printed if the proxy stops dominating them; its per-toolset ceiling is derived from the floor margin the reservation has to fund, as an advance warning for the floor assertion it protects.
- **`find_tools` is exempt from the tool-result cache and publishes no step frame.** The cache is keyed on name plus arguments, so a repeated key would replay a stale `active`/`remainingLoads` and tell the model it holds fewer toolsets than it does; the exemption also keeps exactly one durable row per `find_tools` step. It reads nothing, so it has no coverage category: the loop publishes no `step` frame and `AiChatProgressService.project` skips its rows, which is what keeps the live coverage strip and the reloaded transcript identical. `sourceForTool` stays untouched.
- **The `find_tools` result is server-authored and is replayed verbatim.** It is the authoritative statement of what the turn holds, built entirely from catalog constants. Running it through the tenant-data replacer like any other tool result would let a workspace record named after a toolset key — "Analytics", "Core" — tokenize or redact the `loaded`/`active`/`tools` values, so the model would be told it holds a placeholder it can never name again and would burn its no-progress budget re-asking. `AiAssistantPromptAssembler.maskedToolResult` therefore skips masking for this one tool and fails closed through `AiAssistantToolCatalog.isDeclaredVocabulary` if the result ever carries a string the catalog did not author. The outbound leak scan still passes because those keys are trusted server text the system prompt emits on every step.
- **The model-visible cap is rendered from the constant, never written out.** `AiAssistantToolCatalog.capSentence()` is the single source for both the `find_tools` description and the system-prompt directive, because those are the only two places a model learns the limit and a hard-coded numeral beside a changed constant would quietly contradict the loader.
- **A non-core trajectory costs one extra governance step.** The effective floor is three `assistantMaxSteps` — load, call, close. `assistantMaxSteps` is operator-settable down to 1, so a workspace below that floor reaches no non-core tool at all; that is a declared consequence of the taxonomy rather than a bug, and the turn still answers from what it has instead of failing. Pinned by `AiChatAgentLoopServiceTest`. If a family proves to be on most turns, promote its tool into `CORE` against the envelope ceiling rather than raising this floor.
- Native arguments pass the same assistant step guard as the JSON/ReAct path.
- Tool results reuse the same masked `CRM_DATA` representation, except the server-authored `find_tools` result above.
- A provider without native-tool capability retains the existing ReAct protocol; do not silently change its prompt/request semantics.
- Each tool step executes as the member who authored that turn.
- Shared transcript access requires joined membership in the same workspace.
- Durable turn responses include requester-only `toolCalls` execution evidence: the tool key,
  its current authoritative `AiAssistantToolCatalog.ToolTier` (null for unknown tools), and its
  durable status. `AiChatProgressService.projectTurn` derives this and progress from one query
  bounded by `MAX_TOOL_CALL_ROWS_PER_TURN`; no arguments, results, or record identifiers enter
  the projection. `AiChatTurnDto` applies the same requester identity check as partial text and
  detailed progress, returning null for shared viewers. Retry still requires settled progress
  and transcript reconciliation. Every projected call must be READ-tier or a write proposal in a
  server-guaranteed terminal non-executable state (`rejected` or `expired`). Still-`proposed`,
  pending, failed or unknown write outcomes, unknown tiers and absent projections fail closed.
  A review deadline or derived `reviewState: expired` is insufficient: the current server keeps
  the durable status `proposed`, and an action admitted before expiry may still commit afterward.
  New approvals after the deadline are refused at the final mutation boundary by
  `AiAssistantWriteToolService.ProposalAdmission.beforeMutation`; durable rejected rows also fail
  approval's locked `requireStatus(PROPOSED)` check. No client read can invalidate a live proposal.
- The client re-reads a bound failed turn's projection during transcript reconciliation and after
  approval; polling stopping at the turn's terminal state does not freeze proposal outcomes.
  Observed executed writes are retained for that turn across bounded card and projection refreshes.
  Pending or failed execution-evidence refreshes cannot authorize Retry from a cached proposal.
  Approval settlement invalidates outstanding transcript refreshes so a delayed pre-approval
  projection cannot replace the execution evidence after an unknown approval response.
  The Retry callback also revalidates the bound turn's projection and cards immediately before
  submission, regardless of realtime notifications. Only fresh `none` evidence can submit;
  failed reads, committed writes, and unknown proposal outcomes refuse and update client state.
- Citations are re-authorized and filtered per viewer.
- Realtime fanout resolves current joined recipients after commit; cached/caller-supplied recipient sets are not authorization.
- Presence/typing signals are bounded ephemeral hints and never authorization evidence.

### Cross-site admission of audited assistant reads

The GET handlers under `/api/ai/assistant/` that write `ai.assistant.session.read` (an administrator reading another member's session) would write that row under the victim's actor id if a foreign page could reach them. `AiAssistantNavigationAdmissionFilter`, which `SecurityConfig` registers unconditionally (no AI switch gates it), therefore admits only the shapes the SPA makes. It answers `403`, `text/plain`, `Assistant API request shape is not allowed` to a `Sec-Fetch-Dest` other than `empty`, to `Sec-Fetch-Mode: no-cors`, and to a `Sec-Fetch-Site` outside `same-origin`/`none` whose `Origin` is not in `connex.cors.allowed-origins`. A request with no Fetch Metadata is admitted on purpose; that residual and its bound are in the filter's Javadoc and in the [accepted-residual record](../SAST_TRIAGE_LOG.md#accepted-residual--audited-administrative-assistant-session-reads-111-114-151-152).

That fail-open path means a hop that dropped the headers would switch the control off without a sound, so the closed path is proven in a real browser rather than with MockMvc alone (#1520):

- **In CI, through the Next.js rewrite.** `frontend/test/e2e/assistant-admission-filter.spec.ts` runs in the `Frontend — unit & e2e` stack: Chromium, then `next start` and the `/api/:path*` rewrite in `next.config.ts`, then the backend. That job runs on every push to `main`, every merge-group run and every dispatched run, and on a pull request only when its changes set the `cross_stack` category in `.github/scripts/classify-ci-changes.py`: a change under `frontend/` or `backend/`, or to CI policy. A pull request that touches only `docs/` or `deploy/`, `deploy/Caddyfile` included, does not run the spec, so an edge or Caddy change needs the probe below. A page on `http://attacker.example` reads a chat's `/attachments` route as an `<img>`, as an opaque `no-cors` fetch, as a credentialed `cors` fetch and as a top-level link navigation. The `<img>`, the opaque fetch and the navigation each get the filter's `403` and body. The navigation carries the default `SameSite=Lax` session cookie, which is the forgery a Lax deployment is actually exposed to. A second context re-issues the session cookies as `SameSite=None; Secure`, the SAML posture the filter was written for. Its `<img>` and opaque fetch then carry the live session cross-site and are still refused with the filter's body. A request on the same session with no Fetch Metadata reads the route `200`, so the refusals turn on the browser's metadata alone.
- **The credentialed `cors` fetch is refused one filter earlier.** `SecurityConfig` puts the `CorsFilter` before `CsrfFilter` and the admission filter after it, so an `Origin` outside the allowlist gets Spring's `DefaultCorsProcessor` refusal, `403` `Invalid CORS request`, before the admission filter runs. The spec asserts that body. For this shape the filter's `Origin` condition is the second refusal, not the first, and a change in which layer answers has to be recorded here.
- **Each of the destination and mode checks refuses on its own.** Every cross-site probe above fails more than one check, so it would still be refused with either the destination or the mode check deleted. A third context therefore loads the same probe markup from a page under the app origin, which stands in for an app document carrying injected markup. Its requests are `Sec-Fetch-Site: same-origin`, carry the live session and pass the site check. Its link navigation (`Sec-Fetch-Dest: document`, `Sec-Fetch-Mode: navigate`) can be refused only by the destination check. Its opaque fetch (`empty`, `no-cors`) can be refused only by the mode check. Both get the filter's `403` and body, and so does its `<img>`, the shape model-authored Ask Connex markdown could produce. Its `cors` fetch, the shape the SPA makes, reads the route `200` from the same page and session. The site check cannot be isolated in a browser. The one shape it alone refuses, a `cors` fetch from a foreign origin, is refused first by the CORS layer. `AiAssistantControllerTest` covers that branch with MockMvc, with the admission filter as the only filter in front of the controller.
- **The SPA is unaffected.** The same spec opens a chat at `/ask-connex/{id}`. The workspace's session, attachments and tool-call reads go out with `Sec-Fetch-Site: same-origin`, `Sec-Fetch-Mode: cors` and `Sec-Fetch-Dest: empty` and answer `200`, as does an in-page same-origin `fetch` of the attachments route. They are admitted by the allowlist, not by the fail-open path.
- **Why the body is the proof.** The filter admits absent metadata, so a `403` with its body is only possible when Fetch Metadata reached the backend. Without the headers, the Lax-cookie cross-site subresources, which carry no session, would get `401`, and every request carrying the session would get `200`. A cross-site refusal shows that at least one `Sec-Fetch-*` header arrived. The same-origin navigation and opaque fetch show that `Sec-Fetch-Dest` and `Sec-Fetch-Mode` each arrived, since each is the only header that can refuse its probe. The spec has no probe that `Sec-Fetch-Site` alone refuses, so that header's passage rests on the proxy code below. The Next.js 16 rewrite proxy (`next/dist/server/lib/router-utils/proxy-request.js`) sets `changeOrigin`, which replaces `Host`, adds `X-Forwarded-Host`, and forwards the remaining request headers, `Sec-Fetch-*`, `Origin` and `Cookie` among them, as the browser sent them.
- **Public staging.** `preview.connexcrm.jp` (Cloudflare, its tunnel and the staging proxy in front of the backend) was checked the same way for #1520. `GET /api/ai/assistant/sessions` with `Sec-Fetch-Dest: image`, `Sec-Fetch-Mode: no-cors` and `Sec-Fetch-Site: cross-site` returned `403` with the filter's body, and the same request without Fetch Metadata returned `401`.
- **Production compose.** `deploy/Caddyfile` sends `/api/*` straight to `backend:8080` with `reverse_proxy`, which forwards request headers unchanged apart from its declared `header_up` lines (`X-Forwarded-For` set, `CF-Connecting-IP` removed). Next.js is not on that path.

Any new hop in front of `/api/`, whether an edge rule, a header allowlist or a CDN transform, must forward `Sec-Fetch-Dest`, `Sec-Fetch-Mode`, `Sec-Fetch-Site` and `Origin`. The browser spec covers only the Next.js hop, so probe the new topology from outside:

```bash
ORIGIN=https://preview.connexcrm.jp
curl -s -w '\n%{http_code}\n' -H 'Sec-Fetch-Dest: image' -H 'Sec-Fetch-Mode: no-cors' \
  -H 'Sec-Fetch-Site: cross-site' "$ORIGIN/api/ai/assistant/sessions"   # the filter's body, then 403
curl -s -o /dev/null -w '%{http_code}\n' "$ORIGIN/api/ai/assistant/sessions"   # 401: no metadata, no session
```

A `401` from the first command means the hop strips the headers and the control is inert behind it.

The spec reads every probe response through a forward proxy in the test, `frontend/test/e2e/support/wire-tap.ts`, because Chromium's Opaque Response Blocking discards a non-image `no-cors` or `<img>` body before Playwright or DevTools can read it. That proxy also serves the probe pages itself. A page fulfilled through Playwright's `route` was observed never to send its requests to a loopback app, while the same page served through the proxy did send them. Chromium's Local Network Access checks are the likely cause, but that is inferred from the behaviour and not confirmed in Chromium's source.

### Task handles and proposals

`list_tasks` issues per-turn `t1`, `t2`, … handles and `assignedToMe`, never task ids. Task handles
have a separate counter and are excluded from record citation snapshots and replay mappings.
`complete_task` and `reschedule_task` are CONFIRM tools in `write_followup`, require `TASK_UPDATE`,
and delegate only to the existing task service. Completion remains assignee-only. Task cards have
no record link. Their description follows task reference hydration and special-care screening;
model outcomes use the same free-text masking path as task reads.

A task handle belongs only in a tool argument. Final text, suggestions and titles are refused with
`final_task_handle`; summaries refuse task handles, todo items refuse with `todo_contains_handle`,
and narration containing one is dropped. Final text and summaries are checked again after
identifier demasking. Task-handle checks cover both raw text and the canonical link-stripped
conversation form; final text is checked again after link rewriting, and assistant history is
checked before and after replay preparation. Streaming checks only the stable prefix of accumulated
demasked text before persisting each fragment, withholding potential task-handle suffixes with
their required word context and unresolved links or cancelling braces whose canonical form can
still change. Closed bracketed prose and Unicode sentence boundaries release settled text. It
withdraws the current partial answer if a handle is detected across fragment boundaries; the
complete answer is checked again at stream finish.
No task-handle replay remapping exists. Stored task arguments normalize the handle to `t1` and
keep the resolved tenant-local identity under `target`, plus a canonical `targetVersion` hash.

### Assistant proposal review deadlines

`AiAssistantProposalExpiry.PROPOSAL_REVIEW_WINDOW` is seventy-two hours, anchored only to the
original server `ai_chat_tool_call.created_at` interpreted as UTC with microsecond precision.
A trusted still-proposed row expires at `now >= created_at + window`, including legacy singles and
stranded AUTO rows. Missing or invalid creation times fail closed. Reads, replay and retries never
extend the deadline. Expiry derives `reviewState` and `reviewExpiresAt` on both legacy DTOs; it does
not write a new status, column or holding. Terminal outcomes retain their actual meaning, and undo
continues to use its independent inverse eligibility and ten-minute deadline.

Each approval and AUTO execution carries a transaction-bound `MutationAdmission` through
`AiAssistantWriteTool.Execution` to the domain delegate. The callback reads the injected clock
at the final pre-mutation boundary after every blocking prerequisite lock and validation, including
successful no-op branches. Successful admission belongs to that one atomic action and may commit
after the deadline; subsequent effects do not perform a new expiry decision. The framework refuses
to persist an execution receipt if the delegate did not admit. Non-assistant overloads retain their
original signatures and use unrestricted admission. New write tools must propagate this callback
and prove its placement under their domain locks; an early framework check is insufficient. The
complete hierarchy and checkpoints are in [LOCKING.md](LOCKING.md#assistant-write-tools).

Legacy approval and discard return HTTP 409 with code `AI_PROPOSAL_EXPIRED` and message
`Assistant proposal review window has expired` only after authorization, requester/session, trust,
state and tier validation. Discard checks immediately before its conditional rejection write under
session/tool-call locks; it adds no domain permission, target lookup or domain lock. An expired
refusal rolls back without domain mutation, success audit, receipt or rejection. AUTO remains
ineligible for manual decisions; its existing generation-owned failure settlement remains intact.

Every list captures one injected-clock instant for SQL and projection. Pending reads filter trusted
`proposed` rows with `created_at > now - 72 hours` before their existing ordering and 100-row limit,
including audited retained reads. Historical reads retain the oldest 100 rows and supplement them
with up to 100 newest trusted undecided rows beyond that raw `(message.seq, tool-call.id)` window,
filtering trust and proposed status before the separate limit. The supplement includes both open
and expired proposals; it cannot consume the live-pending read's budget. The same bounds apply to
audited retained history. Individual reads retain authorized expired rows.
False-marker proposed/failed rows and malformed envelopes retain existing exclusion rules; legacy
terminal history remains visible without being promoted to trusted, with a null review deadline.
Current visibility, special-care masking, processing restrictions, local restriction fences and
retained-read audits remain authoritative.

Frontend Apply and Discard require a valid open deadline and server-relative time strictly before
it. Assistant API responses calibrate that clock from their HTTP `Date` header, advanced with
monotonic elapsed time rather than the device wall clock; missing samples disarm action guards.
The header's second precision and transport delay can leave the client slightly behind, so the
server's locked expiry checks remain authoritative. A local timer, focus and visibility resume
disarm controls without a network frame, and recalibration reschedules mounted timers; the typed expiry
error clears the action and settles the individual row without retry. EN/JA expiry copy and
historical/pending reload merging preserve the existing surfaces. This deadline is decision
expiry, not erasure: deletion cascades, export ownership, retention and account-erasure handling
are unchanged. V231 is marker-only, raises the rollback floor, and does not repair retained-chat
privacy. Ship the full contract together and drain older instances as described in
[UPGRADING.md](../UPGRADING.md#v231-assistant-proposal-review-expiry).

### Dormant chat proposal-set storage (tenant V233)

`ai_assistant_proposal_set` stores one immutable chat invocation: opaque workspace-scoped key,
real session/message/turn ancestry, original requester, step/call coordinates, tool, scalar/bulk
mode, row count, intent fingerprint, scope revision, privacy generation and original timestamp.
The generated bulk slot reserves one bulk invocation per turn, including failed calls and arrays
of one. Scalar sets require one child; bulk sets permit 1–20. No legacy rows are backfilled.
The parent stores no copied labels, rationale or argument JSON. `publication_state` is technical
settlement (`staged`, `ready`, `failed`), independent of child decisions; failed storage requires a
closed reason (`call_failed`, `cancelled`, `timed_out`, `execution_lost`), never exception text.

Composite cascading FKs bind parents to messages and turns in the same workspace and session,
and children to their parent's exact message. Children keep `message_id NOT NULL`, the existing
status vocabulary and trust marker. Set-owned children add a unique ordinal and preparation
anchor; legacy rows retain null membership columns. BEFORE INSERT/UPDATE triggers enforce complete
membership metadata, ordinal bounds and server trust without adding a COPY-requiring CHECK to the
retained tool-call table; parent constraints remain CHECKs. The guarded online DDL and migration-only
FK installation procedure are documented in [UPGRADING](../UPGRADING.md#dormant-assistant-proposal-set-storage-tenant-v233).
Their creation timestamp and privacy generation are copied from the parent, separately from the
earlier preparation anchor. Children remain ordinary reactive chat artifacts with no mission run;
read-only missions use their separate persistence path and do not produce proposal sets.
Proposal-key FK columns explicitly use
the same ASCII binary collation. Account erasure clears the parent's requester beside message
authors and turn requesters; membership departure retains attribution and never transfers it.
The table's `TenantLifecycleRegistry` message cascade supplies tenant export, teardown and
residual reach while chat messages, tools and turns retain their direct lifecycle declarations.
Export applies the existing mission privacy barrier and parent message/turn visibility to set
metadata; residual counts remain unconditional. V232 restriction and DSR cleanup remove sets
through their message and origin-turn cascades, including sets attached to retained pre-taint
user input, because the originating turn remains enrolled. The mapper reads no person rows, so
the person-reader restriction registry does not apply. Account erasure discovers proposal-set
requester provenance when locking session roots and clears it in the chat-child phase before
domain mutations, restriction fences and privacy invalidation.

`AiAssistantProposalSetMapper` is a dormant storage surface with no production consumer. Its
inserts admit a user message and turn with matching requester/session, then stage trusted children
under that parent. It has no identity, mode, membership or timestamp update operation and no
publication transition. Exact reads return all children in ordinal order, without transcript caps;
`hasCompleteMembership` checks count, contiguous ordinals, trust, tool, requester ancestry and
creation/preparation anchors. It is an integrity check, not authorization or readiness. MySQL
constraints alone cannot enforce a cross-row child count at commit.

The later creation service must establish current authority, privacy generation and exact
turn/message association under the session mutex, insert the parent and every member in one
transaction, and require complete membership before commit. Existing chat readers and writers
retain their legacy behavior. Production set creation, effective-tier decisions, whole-call
publication, set-specific privacy admission and discovery remain disabled until their own slices. V233
preserves the existing V232 rollback floor; activation requires a fresh semantic cutover.

### Adding an assistant write tool

Adding an assistant write tool is one `AiAssistantWriteTool` bean, its catalog line and `description` arm, its record in the sealed `AiAssistantWriteToolRequest`, its two `AiChatProgressService` arms (`sourceForTool` and `resultCount`, forced by `AiChatProgressServiceTest`), its own permitted-method grants in `AiAssistantWriteToolSpiArchTest`, its request and outcome label arms in `frontend/app/lib/askConnex.ts` with EN and JA copy (forced by the same arch test), its toolset staying within that toolset's pinned ceiling in `AiAssistantPromptEnvelopeTest`, and its trajectory golden. A tool that changes an existing record is confirm-tier unless [`docs/PRODUCT.md`](../PRODUCT.md) enumerates it as an immediate write; a confirm-tier tool returns non-empty `diffs` for every card it can apply (the client never arms Apply without one) and resolves any name the model wrote through `resolve`, never inside `apply`, so the framework pins it. A card that states a column the target holds now as its before-value declares `ReviewInput.FIELDS` and reads it from `RecordSnapshot.fields`, which the card projection fills from the row it already loaded for the page (a person's `title` and `firstResponseDueAt`; a company's `website`, `industry`, `address`; a deal's `value`, `expectedCloseDate`); a tool that does not declare it is handed an empty map. A shared-in row can have owner-only columns masked; `RecordSnapshot.sharedIn` keeps a missing field there from being treated as a statement that the record holds none. A tool whose delegate writes only a record the workspace owns (the lead-response service behind `set_response_due`) declares `requiresOwnedTarget()`: the framework then refuses a contact or company shared in from another workspace recoverably with `unresolved_reference` when the proposal is prepared, so no card offers an approval the delegate could only refuse, and the registry permits it only on record kinds (person, company, deal); deal ownership is checked through the workspace-scoped `DealService.getDealById` query. `update_record_fields` declares it because both person and company update delegates require ownership. `AiAssistantRemoveTagWriteTool` is the reference for a new confirm-tier tool.

`AiAssistantWriteToolService` is the framework: it owns the lock order, the locked-row RBAC assertion, proposal freshness for confirm-tier tools, the owner-scope target gate, verify-after-write, undo bookkeeping and the envelope bytes, and `AiAssistantWriteToolRegistry` refuses to start on a declaration that disagrees with the catalog.

`update_record_fields` is CONFIRM in `write_fields` (weight 8). `AiAssistantWriteFieldPolicy.EDITABLE_FIELDS` is the closed per-kind edit allowlist: person title (128), company website (255), industry (128), address (512), deal value (DECIMAL 15,2) and expected close date. Absent/null arguments keep existing values; blank values and clearing are refused. Email, phone, names, relationships, owner/stage/currency, lifecycle, provenance, outcome/board state, storage paths and custom fields are not accepted. Provenance is never written by an assistant edit. Website uses the approved host-shape regex (no scheme, optional path); decimal values have at most 13 integer and two fractional digits, and ISO dates must parse. The tool reads its locked target through scoped getters, preserves a person's company reference and all unrequested company columns, and writes only through `PersonService.update`, `CompanyService.updateCompany`, `DealService.updateValue` and `reschedule`. Its read-back compares the returned record id, and its model outcome contains only `recordType` and `updated` field keys.

`create_person` and `create_deal` are CONFIRM tools in `write_create`, anchored to a company the member can currently read. They declare `TargetLock.NONE` and `Freshness.NONE`: the company is access-checked at preparation and approval but never locked or edited by the framework. The registry permits this pair only together, without target-kind writable fields or a board lock, and requires an implemented `duplicateProbe` unless the only accepted target kind is `workspace`. Task targets still require `TASK_ROW` and `TARGET_FINGERPRINT`. Workspace targets require NONE/NONE, no board lock, and request records without a `handle` component. Their id comes from the active server workspace; replay, stored execution, and card reads refuse a different workspace id. The scoped target gate rechecks active membership, and the card screens the current workspace name and renders it without a link.

The create preparation order is validation and marker/identifier refusal, handle/kind validation, resolution/principal pins, the tool's `prepare`, and the framework-owned duplicate preflight before persistence. `prepare` returns durable pins separately from transient duplicate inputs; its default combines `pin` and `duplicateProbe`. The create tools select the same `quick_create` preset as the form and store `pinned {templateId, templateVersion, templateSetRevision, templateName, defaultedFields}`. Person preparation derives email/phone defaults from that same selected catalog snapshot using guided creation's core text-default rules. Those values enter only the transient duplicate probe, never durable pins or model output. Values in pins use the inverse metadata vocabulary (String/Integer/Boolean or nested string-keyed maps); replay carries the original pin without selecting a new template or probing again. Pins are excluded from model outcomes and withheld reviews. A required field that the tool, an intrinsic actor value, or a template default cannot supply refuses recoverably with `template_requires_fields`.

The `template` and `templateDefaults` rows extend the existing card rows. The template name uses its English label with Japanese fallback; default rows list localized field keys, with the lead-source enum as the only retained value and custom fields grouped under `customFields`. No private default value or reference id is projected. Guided creation applies provenance and other defaults exactly as quick-create; assistant edits still cannot change provenance. Neither tool accepts email or phone arguments.

`duplicateProbe` is pure and returns only proposed identity inputs. The framework holds `DuplicatePreflightService`, never the tools. Its single strict policy in `requireNoKnownDuplicate` refuses **any** visible candidate or truncated match as `possible_duplicate`, including weak name-only matches; rate limiting or data-access failure yields `duplicate_check_unavailable`. No proposal is persisted on either refusal. This deliberately retains the strict default pending the founder's #410 decision; members can review and create anyway in the ordinary create form. `preflightPerson` retains its owned/shared visibility filtering. `dealCandidatesExist` uses the same workspace-owned candidates and normalization as interactive deal review, in a read-only transaction without a mutex or proof issuance; member ownership filters are request-level narrowing, not permission to expose another workspace's deal. A ready proposal can still lose the race to a new candidate: guided approval submits a null duplicate token and its canonical check under the organization mutex refuses with 409, creating nothing.

Approval delegates only to `GuidedRecordCreationService` with the pinned template version and set revision, and the domain refuses a stale template rather than applying new defaults. `create_deal` also pins exactly one visible stage as `stageId`, its exact `stageName` and its integer `stagePipelineId`. The stage id must match the framework resolution pin, so a name swap between their reads cannot mix two stages. Approval submits the pinned pipeline and passes the reviewed name through server-only augmentation; canonical creation compares the name under the stage lock and checks membership in the pinned pipeline under the existing relationship locks. A rename, including case-only drift, refuses with `Assistant proposal target changed`; a concurrent pipeline move fails canonical relationship validation. Missing stage pins fail closed, so proposals from before these pins must be re-proposed. Required permissions are `PERSON_CREATE` or `DEAL_CREATE`; all preset, reference and delegated permission checks remain. Read-back compares the returned person's company or deal's stage. Unavailable inverses project live person/deal links, without undo. Stored member outcomes carry the created name and deal values; model outcomes contain only `{recordType, created:true}`. All create-name/title diff values remain special-care screened. Rollback to a build without these tools drops their cards and refuses approval/rejection/undo as unknown stored tools; no migration or status change is introduced.

The create goldens are in `AiAssistantScriptedTrajectoryCreateRecordsTest`: no proposal for an existing name or template-default identity candidate, mutex-observed approval race refusal, stage-name/pipeline drift before or during approval, independently stale template version and set revision, unsupported required fields, and creation only after approval. Slice 8's serialization-equivalent estimate was 1,307 / 975 bytes over core (JSON-ReAct/native), against the pinned 1,800 / 1,400 ceilings. `AiAssistantPromptEnvelopeTest` is the authoritative runtime measurement.

`create_company` is CONFIRM in `write_create`, targeted at the workspace, with `COMPANY_CREATE` and all guided/preset delegate gates retained. It uses the same quick-create pin and strict framework duplicate policy. Its website argument follows slice 7's scheme-less host shape (including its optional path); no phone argument is accepted. Preparation checks the effective website (explicit input before template default) and template phone default from the same selected catalog snapshot, keeping private defaults transient. Approval supplies the pinned template with no related-company context and a null duplicate token. Required unsupported fields refuse before preflight. Company name, website and industry rows are screened; name and website reject seeded identifiers.

`create_report` is CONFIRM in `write_workspace`, targeted at the workspace, with `REPORT_CREATE` and `REPORT_READ` (the latter is required by `ReportService.templates`). It accepts the ten built-in keys other than `quota-attainment`, which the report UI gates behind `GOAL_READ`. Its required name is model-authored in the member's language and screened on the `report` diff row. Approval calls only `templates` and `create`, passing no description and reconstructing the configuration with widget titles cleared. The browser localizes titles and template names by their existing report keys; server rendering retains the ordinary English fallback for blank titles. No provider call or report snapshot is created. The model outcome carries only `{recordType, template}`. Separate approved proposals can create separate reports. `ReportService.create` and guided creation read the ambient authenticated user; a future mandate executor must establish that context.

Unavailable inverses project company/report links only while the created row remains visible and live; reports additionally require `REPORT_READ`. `ReportMapper.getDefinitionIdsIn` is a bounded workspace-scoped statement in its existing `TenantScopeInterceptor` namespace. Workspace cards retain requester-only details. The action-surface trajectory goldens cover duplicate website refusal and report permission loss, including an unstubbed report-service spy proving the framework refused before any delegate. The spy gives that class a distinct test context.

Slice 9 reserves `{analytics, write_create, write_content}`. Its serialization-equivalent estimates are **1,875 / 1,396** bytes over core for `write_create` and **810 / 498** for `write_workspace` (JSON-ReAct/native). The former exceeds the pinned JSON-ReAct ceiling by **75 bytes**; both new descriptions are already at most 36 bytes and do not enter the JSON-ReAct catalog. The 1,800 / 1,400 and 1,700 / 1,350 ceilings, shared serialization, other tools' text, context floor, headroom and cap constants remain unchanged pending the orchestrator's budget decision. Gradle's envelope test remains the authoritative measurement and is expected to reject the current JSON-ReAct allocation. Rollback to a backend without these tools hides their cards and refuses their approval, rejection and undo as unknown tools.

Slice 7 SPI additions are framework-owned controls:

- `validateFor(targetKind, JsonNode request)` defaults to no-op and is pure. It sees raw fields so unknown keys and wrong JSON types cannot disappear during binding; legacy tools do no additional parsing. Prepare and both stored readers run it; invalid field combinations are recoverable `invalid_tool_arguments` before any proposal exists.
- `identifierValueFields()` defaults to empty and names String request components, validated at registry startup. In a masked turn the framework captures any issued placeholder inside a raw model argument before demasking, including compatibility-width braces, and refuses it recoverably as `identifier_from_another_record`. Preparation also refuses demasked values containing any seeded identifier before kind validation; field edits declare website, and both record creates declare name. This protects the destination field from copied identifiers; company websites themselves remain excluded from provider context and do not seed placeholders.
- Prepare rejects any string or string-list item containing `[redacted]` or `[omitted by policy]` as `redacted_value`, before kind validation. `MARKER_TOLERANT_PROSE` is a static, registry-validated, test-pinned ledger: `create_note.content/title`, `create_activity.subject/notes`, `create_task.description`. No field-edit argument is exempt; future prose exceptions require explicit review (the deferred `log_activities` is not registered yet). Stored execution also refuses markers outside that ledger.
- `diff(Review)` now defaults to null; `diffs(Review)` defaults to zero or one legacy row. `modelAuthoredDiffFields()` defaults to empty and identifies proposed text screened in full by `SpecialCareTextScreen`. Excluded text is removed and that row is `withheld`. `changes` is always present, empty outside requester-readable pending CONFIRM cards. Legacy `change` is the first row with aggregate precedence: unresolved, withheld, permissionLost, all-unchanged, recordChanged, ready, except that a multi-row ready proposal projects `unresolved` to disarm older clients that display only one field. Single-row projections remain byte-identical. For `update_record_fields`, `create_person`, `create_deal`, `create_company`, and `create_report`, modern clients render and assess every `changes` row with its own state notice, permitting a mix of ready and unchanged only when at least one row is ready; older server responses fall back to `[change]`, or no rows when neither is present. Existing tools keep `change` authoritative for their single-row values, notices, and applicability, including a withheld null change.
- `TargetLock.DUPLICATE_DECISION_RECORD_UPDATE` is allowed only for person/company; the framework takes the organization duplicate mutex before the target row. See `LOCKING.md` for the reciprocal interactive-edit test.

`write_fields` has 1,100 / 875 byte envelope ceilings, pinned in `AiAssistantPromptEnvelopeTest`, after measuring 1,029 / 825 bytes with `update_record_fields`. The catalog description is `Propose correcting record fields.` The allocation remains below the per-toolset cap; the context floor and headroom are unchanged. An older backend hides unknown tool cards and cannot approve this new tool; rollback does not convert the proposal to another tool.

A tool takes no lock, reaches no mapper and never re-resolves a member: `AiAssistantWriteToolSpiArchTest` holds its injected dependencies to an explicit allowlist of domain services and helpers (no mapper, no `WorkspaceService`) and gives each tool its own permitted-method grants per service it holds (for example only `create` and `deleteIf` on `ActivityService` for the activity tool; a grant to one tool is never a grant to another tool holding the same service, and holding a service with no grant fails), gives it an `Authority` with no reason-for-authorization field, and refuses lock, permission-read and lifecycle-mutator names in its source; `principals` receives the only member lookup (for a confirm-tier tool the framework also runs `principals` and `resolve` once when the proposal is prepared, pins the resulting ids beside the stored request, refuses a name that resolves to no single row recoverably with `unresolved_reference`, and refuses an approval whose own resolution differs from the pin; a card reviews a pinned proposal through `Review.pinnedResolutionId` and `Review.pinnedPrincipalIds`, so name only the pinned row there), `Execution.scheduleConflicts`, which the framework binds to the row's own person target, is the only read a tool gets beyond its own domain services (the read-tool executor answers it, so the activity tool never injects it), and card summaries for a viewer who may not read the details are computed from a review the framework has emptied of every record value — only the boolean `sharedOutcomeFlags` and `sharedRequestFlags` a tool declares survive (a request flag is a static field-names-keyword declaration the registry reads once at startup and the framework evaluates, never tool code on the read path), which is how `add_tag` still says whether the tag was added and `assign_owner` whether the owner was removed. A `changed` flag that is false does imply what the record held when the write ran — the tag was already there or already gone — and that is a reviewed, accepted disclosure recorded beside `SHARED_OUTCOME_FLAGS` in `AiAssistantWriteToolRegistryTest`: the card names its target only to a viewer who can currently see the record, where the same fact is on the record itself, and no flag ever carries a value the record holds, such as the deadline. `set_response_due` shares no outcome flags: whether a contact already carried a first-response clock is withheld with the details, so every withheld outcome says only "Request completed".

A detailed request summary is special-care screened unless the tool declines under `screensDetailedRequestSummary`, which only `assign_owner` does (its summary names only the server-resolved member its pending card already shows as the proposed owner); both are pinned by reviewed ledgers in `AiAssistantWriteToolRegistryTest`, as is `requiredRequestText`: a call the loop refused keeps the model's raw arguments under the tool's name, so the card projection shows a stored row only when every field that declaration names holds non-blank text (`assign_owner`'s `owner`, `change_deal_stage`'s `stage`), and the registry refuses a name that is not a string component of the tool's request record.

The order is in [`docs/backend/LOCKING.md`](LOCKING.md). Every catalog write tool is exactly one bean — the registry refuses to start otherwise — so the framework, the card projection (`AiAssistantToolCallReadService`) and the read-tool executor's handle-kind check read every per-tool answer from that bean through `AiAssistantWriteToolRegistry` and hold no tool-name arm, table or fallback of their own; `AiAssistantWriteToolSpiArchTest` refuses a write tool's key spelled in any of them.

`draft_document` is a CONFIRM write in `write_pipeline`: it resolves exactly one case-insensitive
workspace template name through `DocumentTemplateService.getAll`, pins its id, and delegates only
`DealDocumentService.generate`. That delegate creates a draft and its audit row, never a final or
sent document; the tool has no inverse. `requiresOwnedTarget` declares a prepare-time owned-deal
check through `DealService.getDealById`, refusing an unavailable or shared-in deal recoverably with
`unresolved_reference` before persisting a proposal. The registry permits that declaration
only for person, company and deal targets. Replays retain the original pins; approval still runs the locked target gate.
Cards batch `ReviewInput.TEMPLATES` once per authorized page and show only the pinned template,
withholding the diff when its name fails the special-care screen. `ReviewInput.DOCUMENTS` reads
`DealDocumentService.getForDeal` once per distinct deal among pending cards whose details are readable,
retaining only the latest version by template. The document diff's current value carries that version
for the pinned template as informational context; the client describes an additional draft without a
before-row and shows the existing-document notice in English or Japanese. After a draft approval,
the client refreshes pending draft cards on that deal to show the newly created version. Existing documents do not
invalidate another approval or update the deal timestamp: separate approvals may create v1 and v2.
Withheld reviews receive no document versions and cause no document lookup of their own.
The stored outcome includes the
rendered title, but the model projection is only `{recordType, type, version}`: a merged title is
record-derived text, not another workspace label. Member outcome titles use the existing screen.
The `draft_document_proposal` golden covers no document before approval, draft v1 afterward, and
refusal when a different template takes the reviewed name.

### Operator-declared parallel tool calls

One model step carries one tool call unless an operator has declared, for one exact endpoint and model, that it carries more. On a declared endpoint the agent loop executes a batch of up to `AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS` **READ-tier** calls in one step, strictly sequentially and under exactly the per-call checks a single call gets. An undeclared endpoint is unchanged: its requests still ask for one call, still carry the literal `parallel_tool_calls: false`, and an over-delivered batch is still refused.

- **The declaration is per endpoint and model, and default off.** `connex.ai.model-overrides[i].parallel-read-calls` binds to `AiProperties.ModelOverride.parallelReadCalls`, is bounded to 1..`AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS` by bean validation, and is honoured **only together with `endpoint`** — the same character-for-character scoping `streaming` and `thoughts` use, because the same model id behind two gateways is two different answers. Unlike those two, the `model-id` must name the configured model id **exactly, namespace included** (case-insensitively): on a router endpoint, a declaration for `google/gemini-2.5-pro` covers neither the bare `gemini-2.5-pro` nor `somemirror/gemini-2.5-pro`, because the probe answered for one upstream model. `AiModelCatalog.parallelReadCalls` answers 1 for every undeclared target, and reads any declared value outside 1..`MAX_PARALLEL_TOOL_CALLS` as 1 rather than as the ceiling.
- **The bound travels on the request, and is re-checked against the target every call is sent to.** `AiProvider.parallelToolCallLimit` defaults to 1 for every adapter — Azure, Bedrock and Vertex declare no native tools at all, so the default is defence in depth rather than an enablement — and `OpenAiCompatibleAdapter` answers it from the catalog. Every reader of an adapter's answer (`AiProviderCapabilities.parallelToolCallsOrSingle`) maps a value outside 1..`MAX_PARALLEL_TOOL_CALLS` to 1, so an adapter's arithmetic mistake cannot enable a batch. The turn records the answer in `AiChatMemory.parallelToolCalls` before its first step, but that is only an upper bound: the input that really moves at runtime is the organization's provider configuration, which an admin may switch mid-turn to another model or endpoint, and every invocation re-resolves it. `AiInvocationService.invokeAdmitted` therefore refuses, as a `provider_capability` block and before any egress, a request whose `AiNativeToolRequest.maxParallelCalls` exceeds what the *currently resolved* target's adapter answers, and the egress checkpoint repeats the check, exactly as it does for streaming and native function capability. The adapter that serializes the request field and the parser that bounds the response then read one value that the current target has declared.
- **The loop asks for no more than it can execute, and no more than the endpoint declared.** `AiChatAgentLoopService.MAX_EXECUTABLE_CALLS_PER_STEP` equals `MAX_PARALLEL_TOOL_CALLS`, and every native request is bounded by the smaller of it and the turn's declared bound. A declared endpoint is therefore sent `parallel_tool_calls: true` and its batches are executed; an undeclared endpoint declares one, so it is sent `false` as it always was, and a batch it sends anyway is refused at the parse boundary under `native_multiple_calls` and audited as malformed — unless one of its calls invented a placeholder, which ends the turn instead (see below). The goldens `aBatchedStepIsRefusedWholeAndExecutesNeitherOfItsCalls` and `aBatchInventingAPlaceholderEndsTheTurnWithoutARepair` pin that undeclared behaviour against the real loop.
- **The wire difference is exactly one field, plus the wording of a repair.** `parallel_tool_calls` has always been sent as the literal `false`; it is sent as the literal `true` only when the request's bound is above one. A native tool-call repair on a request bounded to one still says, byte for byte, "Return exactly one valid native tool call or one valid JSON final answer."; on a request that invites a batch it says "Return up to N valid native tool calls …" with the request's bound, so repairing a batch envelope does not steer the model back to one call per step. `AiInvocationService.parseNativeToolCall` refuses a response above the bound under the existing `native_multiple_calls` repair rule, so an over-delivering provider is still corrected rather than crashing its turn — unless the response invented a placeholder, which ends the turn (see below).
- **A response is validated whole or not at all.** Within the bound, every call's arguments are parsed, passed through the raw step guard and demasked individually, in provider order; a single rejected call makes the whole response malformed and repairable, because the guard is the server's statement about one model decision and keeping the calls that happened to be valid would execute part of a decision it refused. Demask warnings are summed across the calls (saturating rather than wrapping), and a non-zero total fails the turn as `malformed_output` — `nativeStepAttempt` settles a batch carrying any warning that way before admission, so a sibling of an inventing call is never executed. Every call first receives complete-text invented-reference validation; a guessed token refuses before any repairable guard or envelope error. Identifiers must be distinct **within** the response as well as against replayed exchanges, under `native_duplicate_call_id`, because the replayed `tool` messages correlate on exactly that identifier. One response is one model call, so it emits one `ai.llm.call` audit row whatever its cardinality.
- **An invented placeholder anywhere in provider output is never laundered into a repair.** Complete text, separate reasoning, native call ids/names/signatures, raw argument strings and decoded trees receive all-kind validation before envelope/schema checks, retention or execution. This includes malformed/non-object arguments and valid JSON followed by an offending suffix. A refusal is `invented_placeholder` with warnings and no repair, even for a single guard-rejected call. Native responses accumulate refusal warnings across calls; any warning refuses the whole response before any sibling can execute. Raw-member leak checks remain independent. Warning-free envelope failures retain their repair behavior.
- **Step admission is whole-step, ordered, and runs behind skill authority.** For a batch the loop runs, before dispatching anything: `requireSkillAuthority` for **every** call, so an unauthorized write paired with a read still ends the turn as the non-recoverable `tool_outside_skill_authority` rather than being laundered into a recoverable refusal; the closing check, so any call on a closing step ends the turn; then a known-but-unloaded name anywhere refuses the whole step as `tool_not_loaded`; then `mixed_tier_step` (any write in a batch), `find_tools_alone` (`find_tools` beside anything) and `duplicate_parallel_call` (one name with the same canonicalized arguments twice), in that precedence. `tool_not_loaded` is **not replayed** — every call gets its durable failed row and failed frame, and the step contributes no assistant message, because a replayed name the next request's definitions exclude would fail `AiNativeToolRequest`'s membership check — which is byte-for-byte what a single unloaded call has always produced. The other three **are replayed**: every call is recorded, settles as a failed row carrying `{"reason": …}` and a failed frame, and is answered with that reason, so the model learns the rule and the cursor advances by K. The unauthorized-write precedence, both refusal shapes and the order are pinned by `AiChatAgentLoopParallelReadStepTest` and goldens 12 and 14.
- **A write is always the only call in its step.** `mixed_tier_step` refuses a batch carrying any AUTO or CONFIRM tool before anything is prepared, proposed or executed. The review a write gets *is* its step — `writeToolService.prepare`, the proposal-freshness fence, a durable proposal row under a caller-retained unsuffixed key, the requester's own `proposed` frame, and for CONFIRM a turn that ends waiting for a human — so a write the model wants must be emitted alone, and golden 12 proves the same write then lands exactly once. The write path, proposal replay and approval code are untouched by batching.
- **`find_tools` is always alone.** The step's native definitions, the raw step guard and the loaded-set check are computed once per step, so a widening committed mid-batch would run the rest of the step under a vocabulary the guard never admitted. Keeping it alone also keeps every *executed* `find_tools` row the sole, unsuffixed row of its step, which is what lets the loaded set be reconstructed from `ai_chat_tool_call` rows — the reconstruction reads executed `find_tools` rows only — and keeps its verbatim-replay carve-out a statement about one step.
- **Execution is sequential on the loop thread, with every check per call.** `TenantContext` and `TenantWorkScope` hold the tenant scope and workspace routes in `ThreadLocal`s, and the security context and the MyBatis transaction binding are thread-bound too, so a concurrent dispatch would be a cross-tenant read waiting for one propagation miss; and `AiChatResourceRegistry` and `MaskingContext` mint handles and placeholders unsynchronized, so concurrency would make the byte-exact replay non-deterministic. `AiChatAgentLoopService.executeBatch` therefore runs each call through the same `executeStepCall` a single call takes, in provider order: `ownership.isStopped()` and the turn deadline are re-polled before **each** call and return before any `proposeTool`, so no row is written for a turn the loop no longer owns; `requireCurrentAccess` and `requireCurrentToolExecution` re-read membership, `AI_USE` and the restriction epoch per call; and each call's result — a cache hit's included — is admitted to the tool-result budget before it is kept. Every call's handles resolve against the registry **as issued when the batch was admitted** (`AiChatResourceRegistry.issued`), not the live one: the model emitted the whole batch against the handles it had been shown, so a handle an earlier call of the batch mints is one it could only have guessed, and a later call naming it settles as `unknown_handle` — exactly what that call alone would get — while its siblings still run. Placeholders obey the same rule at the parse boundary. What batching saves is provider round-trips, budget reservations, audit rows and re-sends of the growing replay — not database time.
- **A step's replay is all or nothing.** Every admitted call yields exactly one replayed turn, a result or its own stable refusal (a per-call `unknown_handle`, `invalid_tool_arguments`, `plan_updates_exhausted` and the like refuse only that call, with its own failed row and replayed refusal, while its siblings run — golden 13; a single call past the plan allowance gets the same replayed refusal, and its turn goes on, #1913), so a completed batch replays ordinals `1..K`. If any call ends the batch early — a lost owner, a passed deadline, a closable refusal such as `tool_result_budget_exhausted` (raised for a fresh result or a cache hit alike, and taken to the closing step), a non-recoverable failure or an exception — the loop discards the step's replayed turns and recorded calls before it moves on, so no assistant message is ever replayed carrying fewer calls than the model emitted. The same discard withdraws every record handle and identifier placeholder the batch's calls issued, back to checkpoints taken when the batch was admitted (`AiChatResourceRegistry.restore`, `MaskingContext.restore`): the closing request carries none of those calls' results, so a closing answer citing one of their handles fails as `malformed_output` exactly as a citation of a handle the turn never issued does, and one of their placeholders demasks as an invented placeholder rather than into a value the model was never shown. Neither issue counter is rewound, so a withdrawn handle or placeholder — still named by the abandoned calls' durable rows — is never issued again for another record or value. Calls that already ran keep their committed durable rows; only their results leave the replay, as a single call's result does when it cannot be admitted — and a step's only call abandoned that way keeps the handles and placeholders it issued, as it always has. The result cache and the no-progress guard's seen results are left as they are, because every discard ends the turn or hands it to the closing step, which executes no tool. `AiChatAgentLoopParallelReadStepTest` pins the withdrawn citation, the turn's durable handle metadata and the unbound placeholder. `AiNativeToolRequest` refuses a run whose ordinals are not the single `0` or exactly `1..n`, so a dropped middle call fails fast rather than being serialized.
- **A batch is one model decision.** It costs one step against `assistantMaxSteps`, against a routed skill's `maxModelSteps` and against `HARD_MAX_STEPS`, one provider request, one budget reservation and one `ai.llm.call` outcome row. The no-progress guard moves once per batch: any fresh result resets it, a batch of nothing new costs one unit, a whole-step refusal costs one unit, and a batch of plans alone leaves it where it was. Per-call accounting would let the second of four repeated reads close a turn whose third read was fresh.
- **Frames.** A batch publishes one `thinking` and one `narration` frame to the requester and one `step` frame per call, all sharing the step's `seq`; shared viewers receive only the `step` frames, stripped of tool name, reason and `toolCallId` as before. The client does not key `step` frames on `seq`, so no client change is needed.
- **Streamed unnumbered calls keep whole identifiers.** `OpenAiSseAccumulator` places an unnumbered streamed call by the `id` it carries in full, so for such a call the `id` and the `thought_signature` are whole values repeated across fragments, not pieces: they are kept once, and a fragment naming a different one is refused as an invalid stream. Numbered fragments keep concatenating, because their fields may genuinely be split.
- **The durable key shape needs no migration.** A call that was the only one its step made keeps the exact `turn-{turnId}-step-{stepNumber}` key; a call that shared its step renders a `-call-{k}` suffix with k from 1. Every executed or proposed write and every executed `find_tools` is ordinal 0 and unsuffixed. A whole-step refusal (`tool_not_loaded`, `mixed_tier_step`, `find_tools_alone`, `duplicate_parallel_call`) writes a failed row under `-call-{k}` for **every** call of the batch, writes and `find_tools` included: such a row is never executed, never a pending proposal and never the loaded set's source, and the write-card reader drops it because it holds the model's raw arguments rather than a prepared write — so no reader may assume a suffixed write or `find_tools` row cannot exist, only that it cannot have run. The longest possible key sits well inside the existing `VARCHAR(64)` column and its `(workspace_id, idempotency_key)` uniqueness, and the `turn-{id}-step-` prefix scan is unchanged. Ordinals are positions of the **emitted** calls: dense in the replay, and potentially **sparse in the durable rows**, because a call answered from the tool-result cache writes no row at all. No reader may infer a call count from row keys. Every site that builds or parses a key bounds the ordinal by `MAX_PARALLEL_TOOL_CALLS`, so a key naming a position no step could have produced is treated as malformed rather than trusted for its step number.
- **The progress projection reads rows, not steps.** `AiChatProgressService` passes `AiChatAgentLoopService.MAX_TOOL_CALL_ROWS_PER_TURN` — the step ceiling times the per-step call ceiling — as its row limit. The old limit was exact only while one step wrote one row, and exceeding it is silent: every suffixed key still parses and every milestone still looks healthy while the rows past the limit are simply absent.

**Probe checklist — record this before declaring any endpoint.** There is no provider credential in any development or CI environment, so nothing in this repository can speak for a real endpoint. The request field itself is known to be accepted, because it already travels on every request; what has to be established is behavioural.

1. Send one native-tools request to the exact endpoint with the parallel field set true and a prompt that plainly needs two independent reads. Record the endpoint string verbatim, the model id, and the date.
2. Confirm the assistant message carries **more than one** entry in `tool_calls`, that each entry has its own non-empty `id`, and that those ids are distinct within that one message.
3. Confirm each entry carries its own opaque replay state where the endpoint uses one — for Gemini's compatibility layer, a per-call thought signature — rather than one signature for the whole message.
4. Replay that assistant message back: all of its `tool_calls` in one message, followed by one `tool` message per call in the same order. Confirm the endpoint accepts it and continues the conversation. An endpoint that emits several calls but rejects that replay cannot be declared.
5. Record steps 1-4, including the cardinalities actually observed, beside the deployment's configuration. Declare `parallel-read-calls` only for that endpoint and that exact model id — namespace included, as configured in the organization's provider settings — never deployment-wide, and never above what the probe observed. Re-probe before pointing the declaration at another upstream behind the same router.
6. Expect the declaration to take effect on the next turn: the loop then asks that endpoint for up to the declared number of calls per step and executes the READ-tier batches it returns. Remove the declaration to return the endpoint to one call per step.

## Skills, declared scope, and bounded bulk reads

Ask Connex routes routine CRM jobs through `AiSkillCatalog` rather than letting the model invent a retrieval plan. The catalog is a declaration, not authority.

- A skill declares its key/version, supported context, retrieval plan, bounds, required server-computed metrics, allowed tools, result-block contract, permissions, budgets, authority level, golden-evaluation gate, and partial-result behaviour. Keys are additive and replay-safe; a reserved key is `DECLARED` with a stable reason rather than given an invented contract.
- Routing is deterministic and server-side. A skill executes only its declared plan, through the same tool executor, domain services, and RBAC as a model-chosen step, and every plan step is persisted as an ordinary durable tool call.
- A skill never gains authority its tools do not have. The declared authority level is a ceiling, not a grant, and it is enforced: after a routed plan runs, a model-proposed WRITE tool ends the turn with `tool_outside_skill_authority` unless the skill both lists it in `allowedTools` and carries an authority above `READ`. Read tools stay available to synthesis — they grant nothing the caller's generic loop would not, and their scope honesty is governed by the declared-scope tool policy. (Refusing reads here originally made every synthesis-time lookup fatal for plan-only skills.) The skill's declared permissions are re-asserted on the generation thread before the plan's first step, because the routing-time check is a pre-lock snapshot.
- Several `SkillSpec` fields are **declarative only** pending the #1335 result-contract follow-up (`bounds`, `requiredMetrics`, `resultBlockKinds`, `coverageSources`, `citationsRequired`, `minimumContextTokens`, `budgets.maxLatencyMillis`, `budgets.maxAnswerBlocks`). Retrieval is bounded by `AiChatScopeBounds` and each plan step's own limits, and the turn by `AiAssistantTurnBudget`. The record's Javadoc states which fields enforce and which only declare; do not read a declaration as a guarantee.
- **The skill catalog never enters the fixed prompt envelope.** On the smallest supported context window that envelope is paid out of the answer's own output-token budget, so only the selected skill's bounded per-turn directive travels with a turn. `AiAssistantPromptEnvelopeTest` guards the remaining budget.
- Declared query scope (`AiChatQueryScopeRequest`) is a **request** filter, validated and authorized server-side and then applied to the retrieval, so the scope the caller was shown and the query the server ran cannot diverge. Owners resolve through active workspace membership, stages through the workspace pipeline catalog, saved views through their own accessibility rules. A saved view whose config contains a facet the server cannot execute, or which carries no server-evaluable segment at all, is refused — at admission *and* again at execution, so a view edited in between fails the read rather than silently widening the cohort. An accepted view binds its own record type into the scope. Facets only a deal cohort can honour (stages, deal statuses) are refused for a non-deal cohort rather than echoed and dropped. Owner selection is presentational per `docs/DEAL_VALUE_CONTRACT.md`; it is not an access boundary.
- A model argument may only **narrow** a declared scope. Warmth bands intersect with the declared bands and refuse when the intersection is empty; a record kind outside the declared kinds is refused rather than substituted. The anchoring page record is server-derived context and fills the cohort kind only where the declaration leaves it open. `AiChatCohortKind` is the single rule, used by both the scope preview and the executed read, so the preview refuses exactly what the retrieval refuses and the confirmed count describes the set the turn reads.
- An argument refusal is **recoverable, not terminal**. When the server refuses model-proposed tool arguments (`AiAssistantLoopException.refusedArguments` — an unknown metric, a warmth filter on a deal cohort, a hallucinated handle), nothing was computed or exposed, so the loop persists the call as failed and returns `{"error": reason}` to the model as a correctable tool result instead of ending the turn. Refusals that no argument change can cure — revoked access, exhausted budgets, a changed saved view, a write outside skill authority — stay terminal. Refused calls never count as progress, so an uncorrecting model lands in the closing step via the no-progress bound rather than looping.
- Durable turn scope (`ai_chat_turn.scope_json`) stores **identifiers only**. Owner, stage, and saved-view labels are re-resolved on read under the reader's own authorization, so an offboarded member is never left named inside a stored turn and a renamed saved view is never restated under its old name.
- `POST /api/ai/assistant/sessions/scope-preview` evaluates a whole smart segment and spends no model tokens, so no generation budget bounds it. It is metered by `AiChatScopePreviewRateLimiter`, a fixed window per workspace and member.
- Bulk reads apply their row, per-record, and date bounds **inside the mapper query**. Materializing history and truncating afterwards is the defect class #1189 exists to prevent. Every bulk result states the interpreted scope, true match counts, applied caps, truncation, freshness, and exclusion categories. It deliberately offers **no** continuation handle: the per-record cap re-partitions rows on every read, so a second page is not the remainder of the first, and any "rows remaining" figure would name rows no follow-up could reach.

## Governance, budgets, and admission

Workspace governance and organization budgets are independent gates.

- Missing workspace governance uses the documented default; an explicitly disabled workspace terminates an in-flight turn before another provider step.
- Organization token budgets reserve conservatively in the control-plane ledger before egress and settle against provider-reported actual usage.
- The database ledger is the cluster coordinator; do not replace it with JVM-local counters.
- Zero retains its documented meaning as unlimited rather than disabled.
- Cache-miss generation uses `AiInvocationAdmissionService`; single-flight losers wait for the leader and re-read persistent cache rather than becoming artificial rate-limit failures.
- Deal briefs, deal-risk rationales, and introduction rationales call `AiInvocationAdmissionService.precheck` before assembling and masking context whenever the request can only end in a provider attempt: a forced refresh, or a request with no stored output row. Deal briefs run it before any CRM load. Deal-risk rationales run it after the deterministic risk assessment (the deal, its stakeholders, their latest touches, and their warmth), because that assessment decides `not_at_risk` and the rationale panel on every deal page hides on `not_at_risk` rather than reporting rate limiting; a refused rationale therefore still pays for the assessment, the same read the deal page's risk panel already performs. Introduction rationales run it before the workspace-wide suggestion ranking, so under an exhausted quota a pair with no stored row reports rate limiting rather than whether it is still suggested. The precheck applies `acquire`'s leader rejection rules without reserving quota, recording a refresh, or registering a flight, and never refuses an identity that has an active flight it may join. A request with a stored row always assembles, because only a fresh assembly can validate the row's content hash, so a valid cache hit is never refused for quota. A non-forced request that found no row and is refused probes the row again before returning the refusal and assembles when one now exists, because a concurrent caller can publish a row and release its flight between the first probe and the precheck, and that caller's completed attempt may be what filled the quota. Generation runs outside any transaction, so each probe reads committed rows in its own MyBatis session rather than a session-cached empty result. `acquire` remains the binding, reserving decision.
- The precheck mirrors only the JVM-local admission quota, refresh throttle, and registry capacity. Refusals decided after assembly (organization budget exhaustion, the outbound leak scan, and the context-window check) release the admission reservation without filling the quota window, so a request that always ends in one of them still pays for its assembly each time; see #1703.
- A generation timeout interrupts its worker. Context assembly calls `AiCancellation.throwIfInterrupted()` immediately after each CRM load on a generation path, so an interrupt during one load stops the next from starting: each deal, summary, stage-history, stakeholder, activity, note, and task load, each person-lookup batch, the company-profile and account-history loads, each stakeholder's employment and connection loads, the warmth rescore, the deal-risk assessment, each introduction-suggestion load, the suggestion ranking, and each introduction endpoint lookup. It also checks per digest item, and masking checks per dictionary identifier while screening free text, so a timed-out generation releases its fixed-pool worker. Only introduction rationales rank suggestions through `IntroductionService.computeCancellableSuggestions`; the request and scheduled-sweep callers of the same ranking never check for cancellation. A single-flight follower's wait for its leader is interruptible too: an interrupted follower stops waiting and leaves the leader's flight untouched. The interrupt status stays set, so a best-effort helper that swallows the exception and reports degraded context is still stopped at the next checkpoint. A single load or rescore is not interruptible once started. Raw text is never truncated before masking to save time; the replacer must keep parity with the outbound leak scan.

### Exposure enforcement across callers and upgrades

- Assistant turns share one ephemeral context across skills, tool rounds, repair, retries and closing.
  Actual exposures survive later eviction. A fresh turn starts empty. Scheduled briefs publish only
  resolved turns; an invented-reference failure cannot notify a member.
- History compaction and attachment image descriptions discard warned results before persistence or
  replay. Ordinary stored answers and summaries are already demasked, and history is remasked before
  dispatch. Persisted provenance supplies matching candidates, never exposure.
- Deal briefs, deal-risk rationales, introduction rationales, report narratives and the report composer
  reject warned structured results before caching or disclosure. Report period/fingerprint bindings
  stay unexposed when their tokens are absent from the dispatched registry. Business-card extraction
  discards warned results before producing review fields.
- A legacy masked artifact needing demasking without current-run dispatch evidence fails closed.
  Regenerate disposable artifacts. No token map, exposure backfill or inferred historical exposure
  is persisted; historical transcript display does not claim retroactive validation.

Output-cache hash version `v4-dispatched-exposure` invalidates all older generated-output rows.
Regeneration is lazy on the next request and remains subject to current provider admission and quota.
There is no stale fallback: users may temporarily see an unavailable or rate-limited brief/rationale,
report narrative or composer preview until regeneration is admitted. Existing rows bypass the deal
brief's early admission precheck because validation needs a fresh assembly; invalid old rows therefore
still incur CRM loading, masking and hashing under exhausted admission. Expect a temporary increase in
assembly load and provider demand after deployment. Same-identity/hash requests share the existing
single flight within one JVM; every non-refresh leader rechecks the cache after admission so a just
completed regeneration is reused. Admission's documented replica-local scope remains unchanged.
See [UPGRADING.md](../UPGRADING.md) for the deployment implications.

## Failure behavior

Keep provider failure, rate limit, timeout, cancellation, blocked, and unavailable states distinct. Do not recover a lost response by issuing a second generation request when the established handle/polling protocol can continue the original operation.

Deterministic warmth, risk, report figures, and other non-AI behavior remain available without AI.

## Review checklist

- Every model call enters through `AiInvocationService`.
- All gates are independent and fail closed.
- Text follows masking + `PromptAssembly`; media follows the explicit `AiInputImage` exception.
- No prompt/response/credential/content is logged or audited.
- Destination is re-resolved/revalidated immediately before egress; redirects are off.
- One monotonic wall-clock deadline bounds the complete provider invocation.
- No provider/network I/O occurs inside database transactions.
- Unmasked/streaming/collaboration behavior preserves durable authorization and screening.
- Budgets/governance/admission are database-coordinated and checked before egress.
- Provider, masking, leak-scan, timeout, cancellation, budget, and cross-workspace tests pass.

## Durable mission continuation (V235)

Runtime-2 plans and checkpoints follow [AI_MISSIONS.md](AI_MISSIONS.md). Bookkeeping has
strict mission-only schemas and bypasses ordinary todo normalization, result caching and
proposal persistence. Screen and admit the complete result before committing. Provider
requests receive explicit masked plan/context projections; canonical member IDs, original
member-label provenance and evidence bindings remain server-side. Fresh segments reauthorize
bounded evidence and member provenance and rebuild fresh registries without chat history or
provider replay state. Required-context overflow refuses rather than dropping facts.

Every actual dispatch, including repair/fallback, carries its exact mission ordinal through
usage settlement. Continuation never resets organization gates, root caps, deadlines or
outbound leak/special-care screening. Slice 2 adds no write, proposal, question or live-evaluation
surface. See the mission contract for checkpoint-only closing decisions and retry exclusions.

## Mission proposal authority (V238)

Only `record_preparation_v1/1/3` can prepare mission CONFIRM proposals. Its selected-record
allowlist and exact immutable target are independently checked at preparation, binding and
decision boundaries. AUTO, mandates/standing approvals, task writes and creation remain
unreachable. Mission publication uses ordered MANDATORY participants and source-linked
canonical tool rows, without the reactive REQUIRES_NEW facade or V233 set producer.

Original preparation/source observations and typed intent are immutable; replay uses original
pins. Source vectors and privacy ancestry remain server-only. Explicit output/item projection
supplies origin turn through action→segment ancestry, preparation anchor and frozen deadline.
Ordinary/retained readers and overflow fallbacks exclude mission rows; legacy known-ID
approve/reject require mission controls. Unsupported/blocked content retains safe metadata and
cancel authority while refusing all proposal content/counts.

Batch decisions prevalidate the complete immutable membership before effects, use independent
item transactions and preserve partial outcomes on later failure. Fresh authorized canonical
reconciliation resolves uncertain commit acknowledgments; unresolved outcomes are explicitly
indeterminate and disarmed until reload. Authority loss suppresses disclosures without erasing
committed effects. Final mutation admission uses the original deadline after domain contention.
Source holdings are enrolled in action-first bounded privacy deletion, unconditional residuals
and validated UTF-8 tenant export. See [AI_MISSIONS](AI_MISSIONS.md) and [LOCKING](LOCKING.md).
After-commit realtime publication retains requester authority, session/root shared locks and
restriction/privacy fences through local broker enqueue, suppressing revoked or superseded sources.
