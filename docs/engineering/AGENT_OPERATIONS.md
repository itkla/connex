# Agent Operations

This document contains orchestration mechanics that should not consume every coding agent's base context. Repository-wide engineering invariants remain in `/AGENTS.md`; package implementation rules remain in the nearest package `AGENTS.md`.

Read this document when coordinating multiple agents, preparing/merging PRs, managing shared worktrees, or deciding review depth.

## Evidence before effort

Work against the active work item's acceptance/exit criteria rather than hypothetical completeness.

- No failure signal means no side investigation. A slow or pending job is not a failure.
- Use the smallest deterministic local check that can refute the change. CI owns exhaustive suites where package guides say so.
- Do not repair the shared development host during product work unless the task concerns that host.
- Do not invent extra merge gates after acceptance criteria, required checks, and review feedback are satisfied.
- Wait for CI with one blocking watcher rather than sleep/poll loops, for example `gh pr checks <n> --watch --fail-fast` or `gh run watch <id> --exit-status`.
- A completed independent review remains valid until the diff changes materially. Do not debug optional reviewer infrastructure during product work merely to reproduce a review.

## Delegation

Subagents are useful when they create independent context or parallelize genuinely separate work. They are not a quality metric.

### Risk tiers

| Tier | Typical scope | Default dispatch budget |
|---|---|---|
| 0 — mechanical | one-line/local mechanical edit, trivial copy/docs | 0 |
| 1 — narrow | one layer, small known file set, established pattern | up to 2 |
| 2 — broad | cross-layer feature, migration, substantial refactor, uncertain integration | up to 4 |
| 3 — critical | auth, tenancy/RBAC, secrets/crypto, provider egress, destructive data, concurrency/locking, release/fundamental architecture | up to 6 |

These are ceilings, not quotas. Exceed them only for a concrete uncovered risk with a unique deliverable.

### Dispatch rules

- Default to no more than two concurrent subagents.
- Keep at most one mutating owner per layer and normally no more than two mutating worktrees for one task.
- Give each agent a unique charter by files, layer, invariant, or failure class. Do not send several agents the same generic review/exploration prompt.
- Batch adjacent questions and pass forward a context packet: issue, approved plan, relevant files/contracts, settled findings, and test evidence.
- Subagents execute their assignment directly; do not recursively spawn fleets.
- Prefer `rg`, focused diffs, compiler/test output, browser traces, and API/SQL checks over another opinion when deterministic evidence can answer the question.
- Stop dispatching once acceptance criteria, required checks, and required review are satisfied.

A separate planning agent is warranted for Tier 3 work, unresolved destructive/locking design, or genuinely unsettled cross-layer contracts. The orchestrator should plan Tier 1 and most Tier 2 work directly.

## Review

Every material change gets self-review of the exact diff and evidence. Independent review is risk-based:

- Standard material work: one adversarial reviewer trying to refute acceptance criteria with file/line evidence.
- Security/tenant-sensitive work: a security-focused review.
- Tier 3 concurrency/locking/migration work: a separate correctness/concurrency/migration-safety charter when distinct from security.
- Cross-layer or release-critical work may use the same two-charter split.

Do not exceed two reviewers unless they disagree, a high-severity finding remains unresolved, or a concrete risk is still uncovered. Reproduce/reason through findings; reviewers are inputs, not authorities.

Model/provider choice belongs to the active agent environment, not the repository contract. Choose a capable independent context appropriate to the risk; do not encode transient model names into `AGENTS.md`.

## Shared checkout and worktrees

Multiple agents may share `/home/dev/Projects/connex`. The base checkout's HEAD and index are therefore unsafe as an ownership boundary.

For a mutating unit of work, use a dedicated worktree off current `main`:

```bash
git fetch origin
git worktree add /tmp/connex-<short-desc> -b type/short-description origin/main
cd /tmp/connex-<short-desc>
```

Inside the worktree:

- Confirm `git branch --show-current` before committing.
- Stage only explicit paths with `git add -- <paths>`; never `git add -A`, `git add .`, or `git add --all` in a shared environment.
- Do not modify a sibling agent's branch/worktree to repair your task.
- If commits become tangled, create a fresh worktree at the last known-good commit and cherry-pick only verified commits (`git show --stat` each one).
- Remove the worktree after merge when safe.

## Issues and plans

Search the owning tracker before creating work. Extend/update an existing item when it already covers the request. GitHub issues are the default development tracker; team-specific workflows may live in Linear.

Implementation plans and task breakdowns belong on the tracked work item. Do not commit transient `*_PLAN.md` files. Long-lived architecture/reference/runbook material belongs under `docs/` and should be linked from the relevant guide or issue.

## PR and merge procedure

- Never commit directly to `main`; use the repository's `type/short-description` branch convention.
- Use short Conventional Commit subjects and no self-sign/co-author/generated trailers.
- Push the branch and open a PR linked to the work item.
- Before merging, inspect the PR conversation/review threads and required checks. Resolve or explicitly answer human comments, requested changes, bot annotations, and red/pending required checks.
- Once acceptance criteria pass, required checks are green, merge state is clean, and review feedback is resolved, merge without inventing additional combined-head/repeated-smoke gates unless the parent work item requires one.
- Do not force-push or rewrite shared history without explicit authorization.

## CI waiting

Prefer blocking commands that return on state change/failure/completion. Do not spend agent turns repeatedly reporting that a job is still running.

If an optional external reviewer/tool fails before producing evidence, make at most one reasonable fallback to another fresh read-only context. Do not turn authentication, installation, provider-session, or model-name debugging into a side project.
