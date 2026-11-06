# Contact and company search

List filtering (including counts and bulk-selection filters) and global contact/company search
retain their existing workspace, sharing, privacy, member-scope and archive predicates. Global
search returns at most ten rows per group; list reads retain SQL pagination. Normalization never
loads the whole workspace into application memory or filters after pagination.

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
candidates; a name-key index
cannot seek on `%…%`, so V244 does not add one. Mixed-field searches preserve name/title/email
matches even when the input is a phone number, so the complete query can still scan its permitted
workspace candidates. A phone-index range does not make the other contains branches selective.
The additional predicates compare precomputed columns instead of applying Unicode or phone
functions to every database row. Existing visibility joins and SQL limits remain authoritative.

Single and batch mapper inserts and ordinary updates bind projections from their source values.
`search_key_revision` distinguishes those writes from legacy writers and privacy scrubbers. Triggers
clear the affected projection on a source change without a revision advance, preventing stale
search terms from surviving anonymization. Old binaries can still serve literal matching safely;
recurring reconciliation restores normalized coverage after legacy writes, including inserts and
changes made during a rollback. Raw-field predicates remain available until repair commits.

V244 is DDL-only: separate INSTANT column additions, INPLACE phone-index additions with `LOCK=NONE`,
and legacy-write invalidation triggers. It refuses a table-copy fallback. There is no Java Flyway
callback or data reconciliation inside migration execution. Earlier binaries remain safe; the
rollback floor stays V243.

`RecordSearchReconciler.sweep()` runs every 60 seconds after an initial 60-second delay. It reads
at most 25 active workspace IDs from the control catalog, then enters `TenantWorkScope.inWorkspace`
before acquiring each tenant transaction. This mirrors the notification scheduler's control-to-tenant
routing; organization placement selects the physical catalog, while every record statement also
binds the exact workspace. No control table is read from an organization catalog.

Each workspace/table page has its own `REQUIRES_NEW`, `READ_COMMITTED` transaction. An initial
workspace-index/primary-key range selects at most 500 IDs regardless of whether their keys are
already filled. The repair candidate range therefore spans at most those 500 records, even with
sparse missing keys. Each update requires the original binary name and phone, original workspace,
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
- A failed DDL migration is separate from a failed repair page. MySQL DDL can partially commit;
  inspect Flyway history and actual columns/indexes/triggers before applying the repository's normal
  migration recovery procedure. `repair` alone never completes DDL. Do not rerun unconditional
  `ADD COLUMN` against a partially applied catalog or mark V244 successful before all its DDL exists.
  A crash after successful migration history but before any repair needs only a normal restart.
  When history is absent and DDL is partial, restore the affected catalog's pre-V244 backup and
  migrate again, or follow a separately reviewed catalog recovery procedure after identifying
  every committed DDL step. Never apply history-only repair as a substitute for the missing DDL.

Global search suppresses debounce requests during IME composition in both the inline box and
command palette. Enter, Escape and navigation keys do not activate search controls while the
browser reports composition, the input lifecycle is active, or the key code is 229. Committed
input resumes the normal debounce; a subsequent deliberate Enter can navigate.
