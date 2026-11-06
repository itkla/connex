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
Phone search accepts width and separator variants, normalizes valid whole numbers to E.164 digits
with Japan as the default region (the existing identity-matching regional policy), and accepts digit
fragments for substring lookup. Bare digits without `+` or a leading domestic `0` remain fragments
rather than acquiring an inferred country code. Non-phone queries cannot become empty wildcard
phone searches.
Literal `%`, `_` and backslashes remain escaped. This contract applies to names and phones; other
global-search object groups retain their existing behavior.

The indexes begin with `workspace_id`. Contains matching retains the existing substring semantics;
it is not a prefix index seek or full-text search. The additional predicates compare precomputed
columns instead of applying Unicode or phone functions to every database row. Existing visibility
joins and SQL limits remain authoritative.

Single and batch mapper inserts and ordinary updates bind projections from their source values.
`search_key_revision` distinguishes those writes from legacy writers and privacy scrubbers. Triggers
clear the affected projection on a source change without a revision advance, preventing stale
search terms from surviving anonymization. Old binaries can still serve literal matching safely;
their writes may need a subsequent current-version edit to restore normalized coverage.

The V244 Flyway callback backfills at most 500 records per page with a primary-key cursor. Updates
require the original binary name and phone and the original workspace, so concurrent edits are
never overwritten. Rows with both projections filled are skipped on retry. It preserves `updated_at`; the
existing person-version trigger still invalidates open edit drafts safely. Failed SQL batches fail
migration rather than reporting a completed backfill. V244 uses separate INSTANT column additions
and INPLACE index additions and refuses a table-copy fallback. Earlier binaries remain safe, so
this additive migration does not raise the rollback floor.

Global search suppresses debounce requests during IME composition in both the inline box and
command palette. Enter, Escape and navigation keys do not activate search controls while the
browser reports composition, the input lifecycle is active, or the key code is 229. Committed
input resumes the normal debounce; a subsequent deliberate Enter can navigate.
