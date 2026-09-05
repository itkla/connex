# CodeQL dismissal snapshots

This folder is the audit carrier for the one-off replay that
[STATIC_ANALYSIS.md](../STATIC_ANALYSIS.md) and [SAST_TRIAGE_LOG.md](../SAST_TRIAGE_LOG.md) describe
under *Third generation — 2026-09 path restoration*.

## Why a snapshot exists

From 2026-08-26 (PR #1294) the Security workflow passed `source-root: backend|frontend` to CodeQL,
so every alert raised after that date carries a path rooted at `src/…` or `app/…` instead of
`backend/src/…` or `frontend/app/…`. Restoring repository-relative paths (per-language
`.github/codeql/backend.yml` and `frontend.yml` with `paths:`) regenerates the whole alert
inventory: every finding re-materialises under a `backend/`- or `frontend/`-prefixed path, either as
a new alert number or as a reopened pre-2026-08-26 number, and the dismissal metadata (reason,
comment with owner, expiry and issue link) that the `src/`-path records carry is **not** carried over.

The snapshot is taken immediately before the path-restoring change merges and is the input from
which those dismissals are replayed onto the regenerated identities.

## File

`dismissal-snapshot-<YYYY-MM-DD>.json.gz` — the complete CodeQL alert inventory (every state), gzip-compressed
because the `EncryptionGuardrailArchTest` customer-facing text scan walks every `.json` under `docs/` and a
1 MiB alert dump overflows its regex stack. Captured with:

```bash
gh api --paginate "repos/itkla/connex/code-scanning/alerts?tool_name=CodeQL&per_page=100" \
  | gzip -9 > docs/sast/dismissal-snapshot-$(date -u +%F).json.gz
```

`gh api --paginate` emits the pages as one flat JSON array on current `gh` releases and as concatenated
arrays on older ones; normalise the latter before compressing. The replay script also accepts the
`--paginate --slurp` shape (an array of pages), so either capture form is valid.

The file is committed as-is. It is evidence, not configuration: nothing reads it at runtime, and it
must not be edited after capture. Take the snapshot only **after** any alert that is still open on
`main` has been dispositioned, so that the replay finds a dismissal for every regenerated identity.

## Replay

```bash
gh api --paginate --slurp \
  "repos/itkla/connex/code-scanning/alerts?tool_name=CodeQL&state=open&ref=refs/heads/main&per_page=100" \
  > /tmp/open-on-main.json
python3 .github/scripts/replay-codeql-dismissals.py \
  --snapshot docs/sast/dismissal-snapshot-<YYYY-MM-DD>.json.gz --open /tmp/open-on-main.json
python3 .github/scripts/replay-codeql-dismissals.py \
  --snapshot docs/sast/dismissal-snapshot-<YYYY-MM-DD>.json.gz --open /tmp/open-on-main.json --apply
```

The script matches each open alert to a snapshotted dismissal by
`(rule id, path with a leading backend/ or frontend/ removed, start line, start column)` and copies
the snapshot's `dismissed_reason` and `dismissed_comment` verbatim. The dry run prints the plan and
the old→new mapping table; `--apply` issues one `PATCH` per match. An open alert with no snapshotted
dismissal is listed and makes the script exit `1` — it needs fresh triage, never a blanket dismissal.
Paste the mapping table into [SAST_TRIAGE_LOG.md](../SAST_TRIAGE_LOG.md).
