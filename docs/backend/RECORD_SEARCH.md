# Contact and company search

List filtering (including counts and bulk-selection filters) and global contact/company search
retain their existing workspace, sharing, privacy, member-scope and archive predicates. Global
search returns at most ten rows per group; list reads retain SQL pagination. Normalization never
loads the whole workspace into application memory or filters after pagination.

A returned contact or company may belong to another workspace only through an explicit share
within the viewer's organization. The record retains its owning workspace ID; the viewing workspace
owns the share and any private overlays. The trusted organization workspace ceiling excludes
cross-organization owners even if a stale share points at the viewer. Name, phone and NULL-key
candidate branches all remain subject to that same final visibility predicate.

Tenant V244 adds `search_name` and `search_phone` projections to the existing record tables. They
are derived search data, never identity keys, duplicate-review authority, merge decisions or
display values. Their lifecycle follows the owning table. No canonical identities are rewritten.

Names use NFKC width normalization, lowercase, katakana-to-hiragana folding and removal of Unicode
whitespace. Punctuation and name order remain significant; search does not transliterate kanji.
Queries and stored names use the same normalizer. Existing display-field matches remain available.
Phone search removes accepted separators and Unicode whitespace before parsing, separates optional
`ext.`, `x`, `;ext=`, `#` or Japanese `内線` extensions from the base number, and normalizes valid whole numbers to E.164 digits
with Japan as the default region (the existing identity-matching regional policy), and accepts digit
fragments for substring lookup. Bare digits without `+` or a leading domestic `0` remain fragments
rather than acquiring an inferred country code. Non-phone queries cannot become empty wildcard
phone searches.
Literal `%`, `_` and backslashes remain escaped. This contract applies to names and phones; other
global-search object groups retain their existing behavior.

Query-bearing list, count, filtered-selection and global reads first select candidate IDs through
the owning-workspace index and incoming-share workspace index, with the original organization
ceiling on shared owners. A deduplicated UNION and primary-key STRAIGHT_JOIN prevent the outer
visibility OR from selecting an all-record scan. Original visibility predicates still authorize
every candidate; deduplication concerns one record ID, never two records with equivalent keys.

The phone indexes begin with `workspace_id, search_phone`. Recognized complete domestic or explicit
international phone queries use equality on the normalized base in their own indexed candidate
branch. A separate workspace-indexed branch preserves other-field and raw-phone matches while
excluding records already covered by the phone branch. Raw matches remain available for NULL or
nonstandard projections, including literal matches within multiple displayed phone numbers. Digit fragments retain
contains matching, including interior and suffix matches. Name contains matching scans workspace
candidates; a name-key contains index cannot seek on `%…%`, so V244 does not add one for lookup.
The separate one-character name-prefix indexes serve only indexed `search_name IS NULL` repair
discovery. Mixed-field searches preserve name/title/email
matches even when the input is a phone number, so the complete query can still scan its permitted
workspace candidates. A phone-index range does not make the other contains branches selective.
Filled keys use precomputed columns. For NULL keys only, bounded workspace candidates also compare
raw sources before pagination: name fallback removes Unicode whitespace and standalone kana voicing
marks and uses the key's width/kana-insensitive collation; phone fallback strips accepted formatting
and extensions, compares digits, and recognizes Japanese domestic/optional-trunk forms. A voicing-only
query cannot turn into an empty wildcard. These predicates apply in both candidate selection and
final matching, including linked company metadata. Existing visibility joins and SQL limits remain
authoritative, so transitional matching never bypasses archive, membership, sharing or organization
ceilings. Literal raw-field matches remain available alongside equivalent-form matching.

Single and batch mapper inserts and ordinary updates bind projections from their source values.
`search_key_revision` distinguishes those writes from legacy writers and privacy scrubbers. Triggers
clear the affected projection on a source change without a revision advance, preventing stale
search terms from surviving anonymization. Old binaries can still serve literal matching safely;
recurring reconciliation fills keys after legacy writes, including inserts and changes made during
a rollback. Supported equivalent forms remain searchable through NULL-only fallback until repair
commits; reads do not fill projections or change canonical identities.

V244 is DDL-only: separate INSTANT column additions, INPLACE phone/backlog-index additions with
`LOCK=NONE`, and legacy-write invalidation triggers. It refuses a table-copy fallback. Existing
columns and indexes are skipped only after verifying their definitions in `information_schema`;
incompatible definitions fail closed. Each committed prefix can be replayed without duplicating
columns or indexes. Existing trigger table/event/timing/body definitions are verified before
`CREATE TRIGGER IF NOT EXISTS`; compatible invalidation triggers stay live throughout replay,
including concurrent legacy/privacy writes. Unknown trigger definitions fail closed rather than
being dropped. There is no Java Flyway
callback or data reconciliation inside migration execution. Earlier binaries remain safe for
V244 alone, whose component rollback floor stays V243. The consent-version cutover in this
release raises the active floor to V245; follow
[the release-wide upgrade policy](../UPGRADING.md#contact-channel-consent-versions-tenant-v245).

`RecordSearchReconciler.sweep()` starts without an initial delay and runs every 60 seconds. It reads
at most 25 active workspace IDs from the control catalog, then enters `TenantWorkScope.inWorkspace`
before acquiring each tenant transaction. This mirrors the notification scheduler's control-to-tenant
routing; organization placement selects the physical catalog, while every record statement also
binds the exact workspace. No control table is read from an organization catalog.

Each workspace/table page has its own `REQUIRES_NEW`, `READ_COMMITTED` transaction. An initial
phone-NULL index range and name-NULL index range each select at most 500 numeric IDs, then their
deduplicated union retains at most 500 IDs in order. Already filled rows cannot delay missing rows.
Source reads use those exact primary keys and refuse empty or oversized ID lists; even sparse
missing keys never create a scan across the intervening filled records. Each update requires the
original binary name and phone, original workspace,
and a missing key. A concurrent edit wins the CAS; it is never overwritten. Processing-suspended
and provision-ceased people are excluded during source selection and CAS. Archived sources are
retained for authorized archive search and restoration through the exact bounded maintenance
exception in `ArchiveVisibilityRegistry`; this path exposes no product response.
Source snapshots and bound search keys use lossless UTF-8 byte transport, including on a Cp1252
JDBC connection, so Japanese source comparisons cannot become permanently failing lossy CAS writes.

Workspace and record traversals capture fixed ID watermarks. Successful commits advance their
in-memory cursors, and each completed traversal wraps to the start. Continuous inserts cannot
prevent revisiting earlier records. A zero-row CAS is revisited after wrap, as are keys nulled by
later legacy or privacy writes. An interrupted page rolls back; a failed table does not prevent
repairing the other table or other workspaces. Repair preserves `updated_at`; the existing person
version trigger still invalidates open edit drafts safely. Search keys never modify canonical
identity rows or record source fields.

Operation and recovery:

- Scheduling defaults to enabled. `connex.record-search.scheduling-enabled=false` pauses it;
  `reconciliation-delay-ms` and `initial-delay-ms` under the same prefix control cadence.
- Cursors are disposable. Restarting after a reconciliation interruption resumes ordinary startup
  with V244 already recorded, begins traversal again, and skips filled projections. Multiple
  instances may reconcile concurrently because the source-checked update is idempotent.
- Monitor metadata-only warnings identifying workspace, table kind and exception class. To measure
  backlog, count rows with either key NULL in a chosen routed workspace. Do not print names or phones
  or run a whole-catalog repair transaction. Recovery uses the same bounded sweeper.
- A process interruption with absent V244 history needs a normal restart: verified conditional DDL
  resumes the committed prefix, including a person-only column addition or completed indexes with
  triggers still absent. A recorded failed Flyway history row still requires the normal Flyway
  failure-history repair before retry; replay then verifies/completes the schema. Do not adopt
  incompatible definitions or mark V244 successful before every statement completes. A crash after
  successful history but before repair also needs only a normal restart. No pre-V244 restore or
  destructive schema reconstruction is needed for a compatible interrupted prefix.

Global search suppresses debounce requests during IME composition in both the inline box and
command palette. Enter, Escape and navigation keys do not activate search controls while the
browser reports composition, the input lifecycle is active, or the key code is 229. Committed
input resumes the normal debounce; a subsequent deliberate Enter can navigate.
