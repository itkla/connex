#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path


SHA_PATTERN = re.compile(r"[0-9a-f]{40}")
QUALIFICATION_TAG_PATTERN = re.compile(
    r"candidate-soak-v(?:0|[1-9][0-9]*)\."
    r"(?:0|[1-9][0-9]*)\."
    r"(?:0|[1-9][0-9]*)-tc\.(?:[1-9][0-9]*)"
)
ALLOWED_WORKFLOW_REFS = frozenset({"refs/heads/main"})
CANONICAL_REPOSITORY = "itkla/connex"
CANONICAL_REPOSITORY_ID = 1222010579
CANONICAL_WORKFLOW_REF = (
    "itkla/connex/.github/workflows/candidate-soak.yml@refs/heads/main"
)
MINIMUM_REQUIRED_RUNS = 10
PAGE_SIZE = 100
ROUND_WINDOW = timedelta(hours=6)
VALID_CONCLUSIONS = {
    "action_required",
    "cancelled",
    "failure",
    "neutral",
    "skipped",
    "stale",
    "startup_failure",
    "success",
    "timed_out",
}
VALID_STATUSES = {
    "completed",
    "in_progress",
    "pending",
    "queued",
    "requested",
    "waiting",
}
COVERAGE_GAPS = (
    "Security runs triggered outside pull_request or merge_group upload CodeQL analyses "
    "but do not execute the CodeQL alert-blocking steps. A passing soak verdict does not "
    "prove that alert gate.",
    "The release workflow separately requires successful push runs for the exact commit "
    "while it remains the current main head. This audit counts workflow_dispatch runs on "
    "the requested qualification ref and does not replace that release precondition.",
)


@dataclass(frozen=True)
class WorkflowSpec:
    path: str
    display_name: str
    mandatory_jobs: tuple[str, ...]


WORKFLOW_SPECS = (
    WorkflowSpec(
        path=".github/workflows/ci.yml",
        display_name="CI",
        mandatory_jobs=(
            "OCR — contract tests",
            "ClamAV — contract tests and image smoke",
            "Migrations — forward-only guard",
            "Backup tooling — offline regression tests",
            "Support bundle tooling — offline regression tests",
            "Backend — build & test",
            "Frontend — typecheck & lint",
            "Frontend — unit & e2e",
            "CI — required",
        ),
    ),
    WorkflowSpec(
        path=".github/workflows/security.yml",
        display_name="Security",
        mandatory_jobs=(
            "Workflow action pin policy",
            "Secret scan (gitleaks)",
            "Frontend dependency audit",
            "OCR dependency audit",
            "Backend SAST (CodeQL)",
            "Frontend SAST (CodeQL)",
            "Security — required",
        ),
    ),
    WorkflowSpec(
        path=".github/workflows/deploy-smoke.yml",
        display_name="Deploy smoke",
        mandatory_jobs=(
            "Validate compose bundle",
            "Staging deploy tooling — offline regression tests",
            "Boot every deployment profile",
            "Build and boot the stack from source",
            "Deploy — required",
        ),
    ),
)
WORKFLOW_BY_PATH = {spec.path: spec for spec in WORKFLOW_SPECS}
MANDATORY_JOBS = frozenset(
    job_name for spec in WORKFLOW_SPECS for job_name in spec.mandatory_jobs
)


class InputError(ValueError):
    pass


@dataclass(frozen=True)
class ReferencedWorkflow:
    path: str
    sha: str
    ref: str | None


@dataclass(frozen=True)
class WorkflowRun:
    spec: WorkflowSpec
    path: str
    referenced_workflows: tuple[ReferencedWorkflow, ...]
    run_id: int
    run_number: int
    attempt: int
    head_sha: str
    head_branch: str
    event: str
    status: str
    conclusion: str
    html_url: str
    created_at: datetime
    started_at: datetime
    updated_at: datetime


@dataclass(frozen=True)
class WorkflowJob:
    job_id: int
    name: str
    status: str
    conclusion: str
    started_at: datetime
    completed_at: datetime


@dataclass(frozen=True)
class TagSnapshot:
    tag_object_id: str
    commit_sha: str
    tagger_date: datetime


@dataclass(frozen=True)
class TagBinding:
    start: TagSnapshot
    end: TagSnapshot


@dataclass(frozen=True)
class MainBinding:
    main_sha: str
    candidate_sha: str
    compare_status: str


@dataclass(frozen=True)
class AuditedRun:
    run: WorkflowRun
    mandatory_jobs: tuple[tuple[str, WorkflowJob | None], ...]
    completed_at: datetime
    problems: tuple[str, ...]


@dataclass(frozen=True)
class RunIssue:
    audited: AuditedRun
    sequence_problem: str


def require_object(value: object, context: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise InputError(f"{context} must be a JSON object")
    if any(not isinstance(key, str) for key in value):
        raise InputError(f"{context} contains a non-string key")
    return value


def require_list(value: object, context: str) -> list[object]:
    if not isinstance(value, list):
        raise InputError(f"{context} must be a JSON array")
    return value


def require_positive_integer(document: dict[str, object], field: str, context: str) -> int:
    value = document.get(field)
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise InputError(f"{context} has a missing or invalid {field}")
    return value


def require_nonnegative_integer(
    document: dict[str, object], field: str, context: str
) -> int:
    value = document.get(field)
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise InputError(f"{context} has a missing or invalid {field}")
    return value


def require_string(document: dict[str, object], field: str, context: str) -> str:
    value = document.get(field)
    if not isinstance(value, str) or not value:
        raise InputError(f"{context} has a missing or invalid {field}")
    return value


def require_conclusion(document: dict[str, object], context: str) -> str:
    conclusion = require_string(document, "conclusion", context)
    if conclusion not in VALID_CONCLUSIONS:
        raise InputError(f"{context} has an unrecognised conclusion: {conclusion!r}")
    return conclusion


def require_status(document: dict[str, object], context: str) -> str:
    status = require_string(document, "status", context)
    if status not in VALID_STATUSES:
        raise InputError(f"{context} has an unrecognised status: {status!r}")
    return status


def require_timestamp(
    document: dict[str, object], field: str, context: str
) -> datetime:
    value = require_string(document, field, context)
    try:
        timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise InputError(f"{context} has an invalid {field}") from error
    if timestamp.tzinfo is None or timestamp.utcoffset() != timedelta(0):
        raise InputError(f"{context} {field} must be a UTC timestamp")
    return timestamp


def require_instant(
    document: dict[str, object], field: str, context: str
) -> datetime:
    value = require_string(document, field, context)
    try:
        timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise InputError(f"{context} has an invalid {field}") from error
    if timestamp.tzinfo is None or timestamp.utcoffset() is None:
        raise InputError(f"{context} {field} must include a UTC offset")
    return timestamp.astimezone(timezone.utc)


def format_timestamp(timestamp: datetime) -> str:
    return timestamp.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def canonical_workflow_path(path: str) -> str:
    return path.split("@", 1)[0]


def load_document(path: Path) -> object:
    return json.loads(path.read_text(encoding="utf-8"))


def require_sha(value: object, context: str) -> str:
    if not isinstance(value, str) or SHA_PATTERN.fullmatch(value) is None:
        raise InputError(f"{context} must be exactly 40 lowercase hexadecimal characters")
    return value


def parse_tag_snapshot(
    raw_snapshot: object, requested_tag: str, label: str
) -> TagSnapshot:
    snapshot = require_object(raw_snapshot, f"{label} tag snapshot")
    ref_document = require_object(snapshot.get("ref"), f"{label} tag ref response")
    expected_ref = f"refs/tags/{requested_tag}"
    if require_string(ref_document, "ref", f"{label} tag ref response") != expected_ref:
        raise InputError(f"{label} tag ref does not equal {expected_ref}")
    ref_object = require_object(
        ref_document.get("object"), f"{label} tag ref object"
    )
    if require_string(ref_object, "type", f"{label} tag ref object") != "tag":
        raise InputError(f"{label} qualification ref is not an annotated tag object")
    tag_object_id = require_sha(
        ref_object.get("sha"), f"{label} tag object id"
    )

    tag_document = require_object(snapshot.get("tag"), f"{label} tag object response")
    if require_sha(tag_document.get("sha"), f"{label} tag response sha") != tag_object_id:
        raise InputError(f"{label} tag response does not match the ref object id")
    if require_string(tag_document, "tag", f"{label} tag object response") != requested_tag:
        raise InputError(f"{label} tag object name does not match the requested tag")
    target = require_object(tag_document.get("object"), f"{label} tag target")
    if require_string(target, "type", f"{label} tag target") != "commit":
        raise InputError(f"{label} annotated tag does not point directly to a commit")
    commit_sha = require_sha(target.get("sha"), f"{label} resolved commit sha")
    tagger = require_object(tag_document.get("tagger"), f"{label} tagger")
    tagger_date = require_instant(tagger, "date", f"{label} tagger")
    return TagSnapshot(
        tag_object_id=tag_object_id,
        commit_sha=commit_sha,
        tagger_date=tagger_date,
    )


def parse_tag_binding(document: object, requested_tag: str) -> TagBinding:
    binding = require_object(document, "tag binding response")
    start = parse_tag_snapshot(binding.get("start"), requested_tag, "start")
    end = parse_tag_snapshot(binding.get("end"), requested_tag, "end")
    if start != end:
        raise InputError(
            "qualification tag object id, resolved commit, or tagger date changed "
            "during collection"
        )
    return TagBinding(start=start, end=end)


def parse_main_binding(document: object, candidate_sha: str) -> MainBinding:
    binding = require_object(document, "main binding response")
    ref_document = require_object(binding.get("ref"), "main ref response")
    if require_string(ref_document, "ref", "main ref response") != "refs/heads/main":
        raise InputError("main ref response does not identify refs/heads/main")
    ref_object = require_object(ref_document.get("object"), "main ref object")
    if require_string(ref_object, "type", "main ref object") != "commit":
        raise InputError("main ref does not identify a commit")
    main_sha = require_sha(ref_object.get("sha"), "main ref commit sha")

    comparison = require_object(binding.get("compare"), "main comparison response")
    compare_status = require_string(comparison, "status", "main comparison response")
    if compare_status not in {"identical", "behind"}:
        raise InputError(
            f"candidate commit is not reachable from main: compare status {compare_status!r}"
        )
    base_commit = require_object(
        comparison.get("base_commit"), "main comparison base commit"
    )
    if require_sha(base_commit.get("sha"), "main comparison base sha") != main_sha:
        raise InputError("main comparison base commit does not match the recorded main SHA")
    merge_base_commit = require_object(
        comparison.get("merge_base_commit"), "main comparison merge base commit"
    )
    if (
        require_sha(merge_base_commit.get("sha"), "main comparison merge base sha")
        != candidate_sha
    ):
        raise InputError("candidate commit is not the proven merge base of main")
    if (compare_status == "identical") != (main_sha == candidate_sha):
        raise InputError("main comparison status contradicts its commit SHAs")
    return MainBinding(
        main_sha=main_sha,
        candidate_sha=candidate_sha,
        compare_status=compare_status,
    )


def paginated_entries(document: object, collection: str, label: str) -> list[object]:
    pages = require_list(document, f"{label} response")
    if not pages:
        raise InputError(f"{label} response must contain at least one page")

    entries: list[object] = []
    seen_ids: set[int] = set()
    total_count: int | None = None
    for page_index, raw_page in enumerate(pages, start=1):
        page = require_object(raw_page, f"{label} page {page_index}")
        page_total = require_nonnegative_integer(
            page, "total_count", f"{label} page {page_index}"
        )
        if total_count is None:
            total_count = page_total
        elif page_total != total_count:
            raise InputError(
                f"{label} pages disagree on total_count: "
                f"{total_count} and {page_total}"
            )
        page_entries = require_list(
            page.get(collection), f"{label} page {page_index} {collection}"
        )
        for entry_index, raw_entry in enumerate(page_entries, start=1):
            entry = require_object(
                raw_entry,
                f"{label} page {page_index} {collection} entry {entry_index}",
            )
            entry_id = require_positive_integer(
                entry,
                "id",
                f"{label} page {page_index} {collection} entry {entry_index}",
            )
            if entry_id in seen_ids:
                raise InputError(f"{label} id {entry_id} appears more than once")
            seen_ids.add(entry_id)
        entries.extend(page_entries)

    if total_count is None:
        raise InputError(f"{label} response has no total_count")
    expected_pages = max(1, (total_count + PAGE_SIZE - 1) // PAGE_SIZE)
    if len(pages) != expected_pages:
        raise InputError(
            f"{label} response has {len(pages)} page(s), expected {expected_pages} "
            f"for total_count {total_count}"
        )
    for page_index, raw_page in enumerate(pages, start=1):
        page = require_object(raw_page, f"{label} page {page_index}")
        page_entries = require_list(
            page.get(collection), f"{label} page {page_index} {collection}"
        )
        expected_entries = min(
            PAGE_SIZE, max(0, total_count - ((page_index - 1) * PAGE_SIZE))
        )
        if len(page_entries) != expected_entries:
            raise InputError(
                f"{label} page {page_index} has {len(page_entries)} {collection}, "
                f"expected {expected_entries}"
            )
    if len(entries) != total_count:
        raise InputError(
            f"{label} response collected {len(entries)} {collection}, "
            f"but total_count is {total_count}"
        )
    if len(seen_ids) != total_count:
        raise InputError(
            f"{label} response collected {len(seen_ids)} unique {collection} ids, "
            f"but total_count is {total_count}"
        )
    return entries


def parse_referenced_workflows(
    entry: dict[str, object], context: str
) -> tuple[ReferencedWorkflow, ...]:
    raw_workflows = require_list(
        entry.get("referenced_workflows", []), f"{context} referenced_workflows"
    )
    workflows: list[ReferencedWorkflow] = []
    for index, raw_workflow in enumerate(raw_workflows, start=1):
        workflow_context = f"{context} referenced_workflow {index}"
        workflow = require_object(raw_workflow, workflow_context)
        ref_value = workflow.get("ref")
        if ref_value is not None and (not isinstance(ref_value, str) or not ref_value):
            raise InputError(f"{workflow_context} has a missing or invalid ref")
        workflows.append(
            ReferencedWorkflow(
                path=require_string(workflow, "path", workflow_context),
                sha=require_sha(workflow.get("sha"), f"{workflow_context} sha"),
                ref=ref_value,
            )
        )
    return tuple(workflows)


def parse_run_snapshot(
    document: object, requested_tag: str, label: str
) -> list[WorkflowRun]:
    entries = paginated_entries(document, "workflow_runs", f"{label} runs")
    runs: list[WorkflowRun] = []
    for entry_index, raw_entry in enumerate(entries, start=1):
        context = f"{label} run {entry_index}"
        entry = require_object(raw_entry, context)
        path = require_string(entry, "path", context)
        canonical_path = canonical_workflow_path(path)
        run_id = require_positive_integer(entry, "id", context)
        spec = WORKFLOW_BY_PATH.get(canonical_path)
        if spec is None:
            continue
        if require_string(entry, "head_branch", f"{label} run {run_id}") != requested_tag:
            continue

        head_sha = require_sha(entry.get("head_sha"), f"{label} run {run_id} head_sha")
        html_url = require_string(entry, "html_url", f"{label} run {run_id}")
        expected_url = f"https://github.com/{CANONICAL_REPOSITORY}/actions/runs/{run_id}"
        if html_url != expected_url:
            raise InputError(f"{label} run {run_id} has a non-canonical html_url")
        created_at = require_timestamp(entry, "created_at", f"{label} run {run_id}")
        started_at = require_timestamp(
            entry, "run_started_at", f"{label} run {run_id}"
        )
        updated_at = require_timestamp(entry, "updated_at", f"{label} run {run_id}")
        if started_at < created_at:
            raise InputError(f"{label} run {run_id} started before it was created")
        if updated_at < started_at:
            raise InputError(f"{label} run {run_id} was updated before it started")

        runs.append(
            WorkflowRun(
                spec=spec,
                path=path,
                referenced_workflows=parse_referenced_workflows(
                    entry, f"{label} run {run_id}"
                ),
                run_id=run_id,
                run_number=require_positive_integer(
                    entry, "run_number", f"{label} run {run_id}"
                ),
                attempt=require_positive_integer(
                    entry, "run_attempt", f"{label} run {run_id}"
                ),
                head_sha=head_sha,
                head_branch=requested_tag,
                event=require_string(entry, "event", f"{label} run {run_id}"),
                status=require_status(entry, f"{label} run {run_id}"),
                conclusion=require_conclusion(entry, f"{label} run {run_id}"),
                html_url=html_url,
                created_at=created_at,
                started_at=started_at,
                updated_at=updated_at,
            )
        )
    seen_run_numbers: dict[tuple[str, int], int] = {}
    for run in runs:
        key = (run.spec.path, run.run_number)
        earlier_run_id = seen_run_numbers.get(key)
        if earlier_run_id is not None:
            raise InputError(
                f"{label} {run.spec.display_name} run number {run.run_number} "
                f"appears more than once: runs {earlier_run_id} and {run.run_id}"
            )
        seen_run_numbers[key] = run.run_id
    return runs


def parse_runs(document: object, requested_tag: str) -> list[WorkflowRun]:
    snapshots = require_object(document, "runs response")
    start = parse_run_snapshot(snapshots.get("start"), requested_tag, "start")
    end = parse_run_snapshot(snapshots.get("end"), requested_tag, "end")
    if not start:
        raise InputError("runs response contains no relevant workflow runs")
    if sorted(start, key=lambda run: run.run_id) != sorted(
        end, key=lambda run: run.run_id
    ):
        raise InputError("relevant workflow runs changed during evidence collection")
    return start


def parse_jobs(
    document: object, relevant_runs: dict[int, int]
) -> dict[int, list[WorkflowJob]]:
    page_sets = require_list(document, "jobs response")
    jobs_by_run: dict[int, list[WorkflowJob]] = {}
    seen_job_ids: set[int] = set()
    for set_index, raw_page_set in enumerate(page_sets, start=1):
        page_set = require_object(raw_page_set, f"jobs page set {set_index}")
        run_id = require_positive_integer(
            page_set, "run_id", f"jobs page set {set_index}"
        )
        if run_id not in relevant_runs:
            raise InputError(f"jobs document contains unexpected run {run_id}")
        if run_id in jobs_by_run:
            raise InputError(f"jobs document repeats run {run_id}")
        run_attempt = require_positive_integer(
            page_set, "run_attempt", f"jobs page set {set_index}"
        )
        if run_attempt != relevant_runs[run_id]:
            raise InputError(
                f"jobs page set for run {run_id} has attempt {run_attempt}, "
                f"expected {relevant_runs[run_id]}"
            )
        entries = paginated_entries(
            page_set.get("pages"), "jobs", f"jobs for run {run_id}"
        )
        parsed_jobs: list[WorkflowJob] = []
        for entry_index, raw_entry in enumerate(entries, start=1):
            entry = require_object(raw_entry, f"job {entry_index} for run {run_id}")
            job_id = require_positive_integer(
                entry, "id", f"job {entry_index} for run {run_id}"
            )
            if job_id in seen_job_ids:
                raise InputError(f"job id {job_id} appears in more than one page set")
            seen_job_ids.add(job_id)
            entry_run_id = require_positive_integer(
                entry, "run_id", f"job {entry_index} for run {run_id}"
            )
            if entry_run_id != run_id:
                raise InputError(
                    f"jobs page set for run {run_id} contains job for run {entry_run_id}"
                )
            parsed_jobs.append(
                WorkflowJob(
                    job_id=job_id,
                    name=require_string(
                        entry, "name", f"job {entry_index} for run {run_id}"
                    ),
                    status=require_status(entry, f"job {entry_index} for run {run_id}"),
                    conclusion=require_conclusion(
                        entry, f"job {entry_index} for run {run_id}"
                    ),
                    started_at=require_timestamp(
                        entry, "started_at", f"job {entry_index} for run {run_id}"
                    ),
                    completed_at=require_timestamp(
                        entry, "completed_at", f"job {entry_index} for run {run_id}"
                    ),
                )
            )
        jobs_by_run[run_id] = parsed_jobs

    missing_run_ids = set(relevant_runs).difference(jobs_by_run)
    if missing_run_ids:
        formatted = ", ".join(str(run_id) for run_id in sorted(missing_run_ids))
        raise InputError(f"jobs document has no page set for required run(s): {formatted}")
    return jobs_by_run


def audit_run(run: WorkflowRun, jobs: list[WorkflowJob]) -> AuditedRun:
    if not jobs:
        raise InputError(f"jobs document has no entry for required run {run.run_id}")
    if run.status != "completed":
        raise InputError(
            f"run {run.run_id} has non-completed status {run.status} "
            "with terminal conclusion and chronology"
        )
    for job in jobs:
        if job.status != "completed":
            raise InputError(
                f"job {job.job_id} for run {run.run_id} has non-completed status "
                f"{job.status} with terminal conclusion and chronology"
            )
        if job.started_at < run.started_at:
            raise InputError(
                f"job {job.job_id} for run {run.run_id} started before the run started"
            )
        if job.completed_at < job.started_at:
            raise InputError(
                f"job {job.job_id} for run {run.run_id} completed before it started"
            )
        if job.completed_at > run.updated_at:
            raise InputError(
                f"job {job.job_id} for run {run.run_id} completed after the run update"
            )

    mandatory_jobs: list[tuple[str, WorkflowJob | None]] = []
    problems: list[str] = []
    if run.attempt != 1:
        problems.append(f"{run.spec.display_name} run {run.run_id} is attempt {run.attempt}")
    if run.event != "workflow_dispatch":
        problems.append(
            f"{run.spec.display_name} run {run.run_id} used event {run.event}"
        )
    if run.conclusion != "success":
        problems.append(
            f"{run.spec.display_name} run {run.run_id} concluded {run.conclusion}"
        )

    for mandatory_name in run.spec.mandatory_jobs:
        matches = [job for job in jobs if job.name == mandatory_name]
        if len(matches) > 1:
            raise InputError(
                f"run {run.run_id} contains duplicate mandatory job {mandatory_name!r}"
            )
        if not matches:
            mandatory_jobs.append((mandatory_name, None))
            problems.append(
                f"{run.spec.display_name} run {run.run_id} is missing mandatory job "
                f"{mandatory_name}"
            )
            continue
        job = matches[0]
        mandatory_jobs.append((mandatory_name, job))
        if job.conclusion != "success":
            problems.append(
                f"{run.spec.display_name} run {run.run_id} mandatory job "
                f"{mandatory_name} concluded {job.conclusion}"
            )

    return AuditedRun(
        run=run,
        mandatory_jobs=tuple(mandatory_jobs),
        completed_at=max(
            run.updated_at,
            *(timestamp for job in jobs for timestamp in (job.started_at, job.completed_at)),
        ),
        problems=tuple(problems),
    )


def run_evidence(audited: AuditedRun) -> dict[str, object]:
    run = audited.run
    return {
        "path": run.path,
        "referencedWorkflows": referenced_workflow_evidence(run),
        "runId": run.run_id,
        "runNumber": run.run_number,
        "attempt": run.attempt,
        "headSha": run.head_sha,
        "headBranch": run.head_branch,
        "event": run.event,
        "status": run.status,
        "conclusion": run.conclusion,
        "createdAt": format_timestamp(run.created_at),
        "startedAt": format_timestamp(run.started_at),
        "updatedAt": format_timestamp(run.updated_at),
        "completedAt": format_timestamp(audited.completed_at),
        "htmlUrl": run.html_url,
        "mandatoryJobs": mandatory_job_evidence(audited),
    }


def referenced_workflow_evidence(run: WorkflowRun) -> list[dict[str, object]]:
    return [
        {"path": workflow.path, "sha": workflow.sha, "ref": workflow.ref}
        for workflow in run.referenced_workflows
    ]


def mandatory_job_evidence(audited: AuditedRun) -> dict[str, dict[str, object]]:
    evidence: dict[str, dict[str, object]] = {}
    for name, job in audited.mandatory_jobs:
        if job is None:
            evidence[name] = {
                "jobId": None,
                "status": "missing",
                "conclusion": "missing",
                "startedAt": None,
                "completedAt": None,
            }
        else:
            evidence[name] = {
                "jobId": job.job_id,
                "status": job.status,
                "conclusion": job.conclusion,
                "startedAt": format_timestamp(job.started_at),
                "completedAt": format_timestamp(job.completed_at),
            }
    return evidence


def streak_break_evidence(issue: RunIssue) -> dict[str, object]:
    run = issue.audited.run
    reasons = list(issue.audited.problems)
    if issue.sequence_problem:
        reasons.append(issue.sequence_problem)
    return {
        "workflow": run.spec.display_name,
        "path": run.path,
        "referencedWorkflows": referenced_workflow_evidence(run),
        "runId": run.run_id,
        "runNumber": run.run_number,
        "attempt": run.attempt,
        "headSha": run.head_sha,
        "headBranch": run.head_branch,
        "event": run.event,
        "status": run.status,
        "conclusion": run.conclusion,
        "createdAt": format_timestamp(run.created_at),
        "startedAt": format_timestamp(run.started_at),
        "updatedAt": format_timestamp(run.updated_at),
        "completedAt": format_timestamp(issue.audited.completed_at),
        "htmlUrl": run.html_url,
        "mandatoryJobs": mandatory_job_evidence(issue.audited),
        "reasons": reasons,
    }


def temporal_round_problem(
    members: list[AuditedRun], preceding_run: AuditedRun | None
) -> str:
    created = [member.run.created_at for member in members]
    started = [member.run.started_at for member in members]
    if not (created[0] < created[1] < created[2]):
        return "Round creation times do not prove CI → Security → Deploy dispatch order."
    if not (started[0] < started[1] < started[2]):
        return "Round start times do not prove CI → Security → Deploy start order."
    completion_watermark = preceding_run
    for current in members:
        if (
            completion_watermark is not None
            and current.run.started_at <= completion_watermark.completed_at
        ):
            return (
                f"{current.run.spec.display_name} run {current.run.run_id} started "
                f"before {completion_watermark.run.spec.display_name} run "
                f"{completion_watermark.run.run_id} "
                "and all of its jobs completed."
            )
        if (
            completion_watermark is None
            or current.completed_at > completion_watermark.completed_at
        ):
            completion_watermark = current
    timestamps = created + started
    if max(timestamps) - min(timestamps) > ROUND_WINDOW:
        return "Round timestamps exceed the six-hour coherence window."
    return ""


def append_issues(
    issues: list[RunIssue], members: list[AuditedRun], sequence_problem: str
) -> None:
    issues.extend(
        RunIssue(audited=member, sequence_problem=sequence_problem)
        for member in members
    )


def build_rounds(
    audited_runs: list[AuditedRun],
) -> tuple[list[dict[str, object]], list[RunIssue], int]:
    ordered = sorted(
        audited_runs,
        key=lambda audited: (audited.run.created_at, audited.run.run_id),
    )
    for earlier, later in zip(ordered, ordered[1:]):
        if earlier.run.created_at == later.run.created_at:
            raise InputError(
                f"runs {earlier.run.run_id} and {later.run.run_id} have the same "
                "created_at, so global order is ambiguous"
            )
        if earlier.run.started_at >= later.run.started_at:
            raise InputError(
                f"runs {earlier.run.run_id} and {later.run.run_id} have start times "
                "that contradict global creation order"
            )

    rounds: list[dict[str, object]] = []
    issues: list[RunIssue] = []
    pending: list[AuditedRun] = []
    clean_streak = 0
    completion_watermark: AuditedRun | None = None
    round_predecessor: AuditedRun | None = None
    for audited in ordered:
        preceding_run = completion_watermark
        if (
            completion_watermark is None
            or audited.completed_at > completion_watermark.completed_at
        ):
            completion_watermark = audited
        expected = WORKFLOW_SPECS[len(pending)]
        if audited.problems:
            if pending:
                append_issues(
                    issues,
                    pending,
                    f"Run {audited.run.run_id} interrupted an incomplete round.",
                )
            issues.append(RunIssue(audited=audited, sequence_problem=""))
            pending = []
            clean_streak = 0
            round_predecessor = None
            continue

        if audited.run.spec != expected:
            if pending:
                append_issues(
                    issues,
                    pending,
                    f"Run {audited.run.run_id} arrived while "
                    f"{expected.display_name} was required next.",
                )
            pending = []
            clean_streak = 0
            round_predecessor = None
            if audited.run.spec == WORKFLOW_SPECS[0]:
                pending.append(audited)
                round_predecessor = preceding_run
            else:
                issues.append(
                    RunIssue(
                        audited=audited,
                        sequence_problem=(
                            f"Unpaired {audited.run.spec.display_name} run; "
                            "CI was required next."
                        ),
                    )
                )
            continue

        if not pending:
            round_predecessor = preceding_run
        pending.append(audited)
        if len(pending) != len(WORKFLOW_SPECS):
            continue

        temporal_problem = temporal_round_problem(pending, round_predecessor)
        if temporal_problem:
            append_issues(issues, pending, temporal_problem)
            pending = []
            clean_streak = 0
            round_predecessor = None
            continue

        rounds.append(
            {
                "startedAt": format_timestamp(pending[0].run.started_at),
                "runs": {
                    member.run.spec.display_name: run_evidence(member)
                    for member in pending
                },
                "counted": False,
                "reason": "Clean, ordered candidate pipeline round.",
            }
        )
        pending = []
        round_predecessor = None
        clean_streak += 1

    if pending:
        append_issues(
            issues,
            pending,
            "Unpaired run at the end of the collected history.",
        )
        clean_streak = 0

    newest_rounds = list(reversed(rounds))
    for index, pipeline in enumerate(newest_rounds):
        if index < clean_streak:
            pipeline["counted"] = True
        else:
            pipeline["reason"] = "Clean but precedes streak-breaking run evidence."
    issues.sort(
        key=lambda issue: (issue.audited.run.created_at, issue.audited.run.run_id),
        reverse=True,
    )
    return newest_rounds, issues, clean_streak


def validate_request(
    required_runs: int,
    ref: str,
    repository: str,
    repository_id: int,
    event_repository_id: int,
    repository_fork: bool,
    workflow_ref: str,
    workflow_sha: str,
    github_workflow_ref: str,
    github_workflow_sha: str,
) -> None:
    if repository != CANONICAL_REPOSITORY:
        raise InputError(
            f"github.repository must be exactly {CANONICAL_REPOSITORY}"
        )
    if repository_id != CANONICAL_REPOSITORY_ID:
        raise InputError(
            f"github.repository_id must be exactly {CANONICAL_REPOSITORY_ID}"
        )
    if event_repository_id != CANONICAL_REPOSITORY_ID:
        raise InputError(
            f"github.event.repository.id must be exactly {CANONICAL_REPOSITORY_ID}"
        )
    if repository_fork is not False:
        raise InputError("github.event.repository.fork must be false")
    if (
        not isinstance(required_runs, int)
        or isinstance(required_runs, bool)
        or required_runs < MINIMUM_REQUIRED_RUNS
    ):
        raise InputError(
            f"required-runs must be an integer of at least {MINIMUM_REQUIRED_RUNS}"
        )
    if QUALIFICATION_TAG_PATTERN.fullmatch(ref) is None:
        raise InputError(
            "ref must match candidate-soak-vMAJOR.MINOR.PATCH-tc.N with "
            "canonical numeric components and a positive N"
        )
    if workflow_ref not in ALLOWED_WORKFLOW_REFS:
        allowed = ", ".join(sorted(ALLOWED_WORKFLOW_REFS))
        raise InputError(
            f"workflow ref {workflow_ref!r} is not allowed; expected one of: {allowed}"
        )
    require_sha(workflow_sha, "github.sha")
    if github_workflow_ref != CANONICAL_WORKFLOW_REF:
        raise InputError(
            f"github.workflow_ref must be exactly {CANONICAL_WORKFLOW_REF}"
        )
    require_sha(github_workflow_sha, "github.workflow_sha")
    if workflow_sha != github_workflow_sha:
        raise InputError("github.sha and github.workflow_sha must be equal")


def workflow_provenance(
    workflow_ref: str,
    workflow_sha: str,
    github_workflow_ref: str,
    github_workflow_sha: str,
) -> dict[str, str]:
    return {
        "github.ref": workflow_ref,
        "github.sha": workflow_sha,
        "github.workflow_ref": github_workflow_ref,
        "github.workflow_sha": github_workflow_sha,
    }


def repository_provenance(
    repository: str,
    repository_id: int,
    event_repository_id: int,
    repository_fork: bool,
) -> dict[str, object]:
    return {
        "github.repository": repository,
        "github.repository_id": repository_id,
        "github.event.repository.id": event_repository_id,
        "github.event.repository.fork": repository_fork,
    }


def tag_binding_evidence(binding: TagBinding) -> dict[str, object]:
    return {
        "start": {
            "tagObjectId": binding.start.tag_object_id,
            "commitSha": binding.start.commit_sha,
            "taggerDate": format_timestamp(binding.start.tagger_date),
        },
        "end": {
            "tagObjectId": binding.end.tag_object_id,
            "commitSha": binding.end.commit_sha,
            "taggerDate": format_timestamp(binding.end.tagger_date),
        },
    }


def unvalidated_tag_snapshot_evidence(snapshot: object) -> dict[str, object]:
    snapshot_object = snapshot if isinstance(snapshot, dict) else {}
    ref_document = snapshot_object.get("ref")
    ref_object = ref_document.get("object") if isinstance(ref_document, dict) else None
    tag_document = snapshot_object.get("tag")
    target = tag_document.get("object") if isinstance(tag_document, dict) else None
    tagger = tag_document.get("tagger") if isinstance(tag_document, dict) else None
    return {
        "tagObjectId": ref_object.get("sha") if isinstance(ref_object, dict) else None,
        "commitSha": target.get("sha") if isinstance(target, dict) else None,
        "taggerDate": tagger.get("date") if isinstance(tagger, dict) else None,
    }


def unvalidated_tag_binding_evidence(document: object) -> dict[str, object]:
    binding = document if isinstance(document, dict) else {}
    return {
        "start": unvalidated_tag_snapshot_evidence(binding.get("start")),
        "end": unvalidated_tag_snapshot_evidence(binding.get("end")),
    }


def main_binding_evidence(binding: MainBinding) -> dict[str, str]:
    return {
        "mainSha": binding.main_sha,
        "candidateSha": binding.candidate_sha,
        "compareStatus": binding.compare_status,
    }


def unvalidated_main_binding_evidence(
    document: object, candidate_sha: object
) -> dict[str, object]:
    binding = document if isinstance(document, dict) else {}
    ref_document = binding.get("ref")
    ref_object = ref_document.get("object") if isinstance(ref_document, dict) else None
    comparison = binding.get("compare")
    return {
        "mainSha": ref_object.get("sha") if isinstance(ref_object, dict) else None,
        "candidateSha": candidate_sha,
        "compareStatus": comparison.get("status") if isinstance(comparison, dict) else None,
    }


def audit_documents(
    runs_document: object,
    jobs_document: object,
    tag_document: object,
    main_document: object,
    required_runs: int,
    ref: str,
    repository: str,
    repository_id: int,
    event_repository_id: int,
    repository_fork: bool,
    workflow_ref: str,
    workflow_sha: str,
    github_workflow_ref: str,
    github_workflow_sha: str,
) -> dict[str, object]:
    validate_request(
        required_runs,
        ref,
        repository,
        repository_id,
        event_repository_id,
        repository_fork,
        workflow_ref,
        workflow_sha,
        github_workflow_ref,
        github_workflow_sha,
    )
    tag_binding = parse_tag_binding(tag_document, ref)
    sha = tag_binding.start.commit_sha
    main_binding = parse_main_binding(main_document, sha)
    runs = parse_runs(runs_document, ref)
    predating_runs = sorted(
        run.run_id
        for run in runs
        if run.created_at < tag_binding.start.tagger_date
        or run.started_at < tag_binding.start.tagger_date
    )
    if predating_runs:
        formatted = ", ".join(str(run_id) for run_id in predating_runs)
        raise InputError(
            f"relevant run(s) {formatted} have chronology that predates qualification "
            "tag creation at "
            f"{format_timestamp(tag_binding.start.tagger_date)}"
        )
    wrong_shas = sorted(
        (run.run_id, run.head_sha) for run in runs if run.head_sha != sha
    )
    if wrong_shas:
        details = ", ".join(
            f"run {run_id} at {head_sha}" for run_id, head_sha in wrong_shas
        )
        raise InputError(
            f"relevant run(s) on {ref} do not match resolved commit {sha}: {details}"
        )
    relevant_runs = {run.run_id: run.attempt for run in runs}
    jobs_by_run = parse_jobs(jobs_document, relevant_runs)
    audited_runs = [audit_run(run, jobs_by_run[run.run_id]) for run in runs]

    reasons: list[str] = []
    for spec in WORKFLOW_SPECS:
        count = sum(1 for audited in audited_runs if audited.run.spec == spec)
        if count < required_runs:
            reasons.append(
                f"{spec.display_name} has {count} run(s) on {ref} at {sha}; "
                f"{required_runs} required."
            )

    pipelines, issues, clean_streak = build_rounds(audited_runs)
    if clean_streak < required_runs:
        reasons.append(
            f"The newest proven candidate pipeline streak is {clean_streak}; "
            f"{required_runs} required."
        )

    verdict = "pass" if clean_streak >= required_runs else "fail"
    return {
        "schemaVersion": 2,
        "ref": ref,
        "resolvedSha": sha,
        "repositoryProvenance": repository_provenance(
            repository,
            repository_id,
            event_repository_id,
            repository_fork,
        ),
        "workflowProvenance": workflow_provenance(
            workflow_ref,
            workflow_sha,
            github_workflow_ref,
            github_workflow_sha,
        ),
        "tagBinding": tag_binding_evidence(tag_binding),
        "mainBinding": main_binding_evidence(main_binding),
        "requestedRuns": required_runs,
        "requiredRuns": required_runs,
        "cleanStreak": clean_streak,
        "verdict": verdict,
        "reasons": reasons,
        "coverageGaps": list(COVERAGE_GAPS),
        "pipelines": pipelines,
        "streakBreaks": [streak_break_evidence(issue) for issue in issues],
    }


def failed_evidence(
    ref: str,
    required_runs: int,
    repository: str,
    repository_id: int,
    event_repository_id: int,
    repository_fork: bool,
    workflow_ref: str,
    workflow_sha: str,
    github_workflow_ref: str,
    github_workflow_sha: str,
    tag_document: object,
    main_document: object,
    reason: str,
) -> dict[str, object]:
    effective_runs = (
        required_runs
        if isinstance(required_runs, int)
        and not isinstance(required_runs, bool)
        and required_runs >= MINIMUM_REQUIRED_RUNS
        else None
    )
    tag_evidence = unvalidated_tag_binding_evidence(tag_document)
    start_tag_evidence = tag_evidence["start"]
    candidate_sha = (
        start_tag_evidence.get("commitSha")
        if isinstance(start_tag_evidence, dict)
        else None
    )
    return {
        "schemaVersion": 2,
        "ref": ref,
        "resolvedSha": None,
        "repositoryProvenance": repository_provenance(
            repository,
            repository_id,
            event_repository_id,
            repository_fork,
        ),
        "workflowProvenance": workflow_provenance(
            workflow_ref,
            workflow_sha,
            github_workflow_ref,
            github_workflow_sha,
        ),
        "tagBinding": tag_evidence,
        "mainBinding": unvalidated_main_binding_evidence(
            main_document, candidate_sha
        ),
        "requestedRuns": required_runs,
        "requiredRuns": effective_runs,
        "cleanStreak": 0,
        "verdict": "fail",
        "reasons": [f"Input unusable: {reason}"],
        "coverageGaps": list(COVERAGE_GAPS),
        "pipelines": [],
        "streakBreaks": [],
    }


def render_summary(evidence: dict[str, object]) -> str:
    verdict = evidence.get("verdict")
    verdict_label = "PASS (qualified)" if verdict == "pass" else "FAIL"
    provenance = evidence.get("workflowProvenance")
    if not isinstance(provenance, dict):
        provenance = {}
    repository = evidence.get("repositoryProvenance")
    if not isinstance(repository, dict):
        repository = {}
    tag_binding = evidence.get("tagBinding")
    if not isinstance(tag_binding, dict):
        tag_binding = {}
    tag_start = tag_binding.get("start")
    if not isinstance(tag_start, dict):
        tag_start = {}
    tag_end = tag_binding.get("end")
    if not isinstance(tag_end, dict):
        tag_end = {}
    main_binding = evidence.get("mainBinding")
    if not isinstance(main_binding, dict):
        main_binding = {}
    lines = [
        "# Candidate soak evidence",
        "",
        f"- `github.repository`: `{repository.get('github.repository')}`",
        f"- `github.repository_id`: `{repository.get('github.repository_id')}`",
        "- `github.event.repository.id`: "
        f"`{repository.get('github.event.repository.id')}`",
        "- `github.event.repository.fork`: "
        f"`{repository.get('github.event.repository.fork')}`",
        f"- Ref: `{evidence.get('ref')}`",
        f"- Resolved SHA: `{evidence.get('resolvedSha')}`",
        f"- Start tag object ID: `{tag_start.get('tagObjectId')}`",
        f"- Start tagger date: `{tag_start.get('taggerDate')}`",
        f"- End tag object ID: `{tag_end.get('tagObjectId')}`",
        f"- End tagger date: `{tag_end.get('taggerDate')}`",
        f"- Recorded `main` SHA: `{main_binding.get('mainSha')}`",
        f"- Candidate/main compare status: `{main_binding.get('compareStatus')}`",
        f"- `github.ref`: `{provenance.get('github.ref')}`",
        f"- `github.sha`: `{provenance.get('github.sha')}`",
        f"- `github.workflow_ref`: `{provenance.get('github.workflow_ref')}`",
        f"- `github.workflow_sha`: `{provenance.get('github.workflow_sha')}`",
        f"- Requested pipeline rounds: {evidence.get('requestedRuns')}",
        f"- Required consecutive pipeline runs: {evidence.get('requiredRuns')}",
        f"- Clean streak: {evidence.get('cleanStreak')}",
        f"- Verdict: **{verdict_label}**",
        "",
        "## Reasons",
        "",
    ]
    reasons = evidence.get("reasons")
    if isinstance(reasons, list) and reasons:
        lines.extend(f"- {reason}" for reason in reasons if isinstance(reason, str))
    else:
        lines.append("- The required clean streak was present.")

    lines.extend(["", "## Coverage limitations", ""])
    coverage_gaps = evidence.get("coverageGaps")
    if isinstance(coverage_gaps, list):
        lines.extend(f"- {gap}" for gap in coverage_gaps if isinstance(gap, str))

    lines.extend(["", "## Counted runs", ""])
    counted_rows: list[str] = []
    pipelines = evidence.get("pipelines")
    if isinstance(pipelines, list):
        for pipeline in pipelines:
            if not isinstance(pipeline, dict) or pipeline.get("counted") is not True:
                continue
            started_at = pipeline.get("startedAt")
            runs = pipeline.get("runs")
            if not isinstance(started_at, str) or not isinstance(runs, dict):
                continue
            for workflow_name in ("CI", "Security", "Deploy smoke"):
                run = runs.get(workflow_name)
                if not isinstance(run, dict):
                    continue
                run_id = run.get("runId")
                html_url = run.get("htmlUrl")
                if isinstance(run_id, int) and isinstance(html_url, str):
                    counted_rows.append(
                        f"| {started_at} | {workflow_name} | "
                        f"[run {run_id}]({html_url}) |"
                    )
    if counted_rows:
        lines.extend(["| Pipeline started | Workflow | Run |", "| --- | --- | --- |"])
        lines.extend(counted_rows)
    else:
        lines.append("No candidate pipeline runs were counted.")
    lines.append("")
    return "\n".join(lines)


def write_results(
    evidence: dict[str, object], output_path: Path, summary_path: Path
) -> None:
    output_path.write_text(
        json.dumps(evidence, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    summary_path.write_text(render_summary(evidence), encoding="utf-8")


def parse_boolean(value: str) -> bool:
    if value == "true":
        return True
    if value == "false":
        return False
    raise argparse.ArgumentTypeError("expected exactly true or false")


def parse_required_runs(value: str) -> int:
    if re.fullmatch(r"[1-9][0-9]*", value) is None:
        raise argparse.ArgumentTypeError(
            "expected a canonical positive decimal integer"
        )
    return int(value)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Audit consecutive complete-suite workflow runs on one commit and ref"
    )
    parser.add_argument("--runs-json", required=True, type=Path)
    parser.add_argument("--jobs-json", required=True, type=Path)
    parser.add_argument("--tag-json", required=True, type=Path)
    parser.add_argument("--main-json", required=True, type=Path)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--repository-id", required=True, type=int)
    parser.add_argument("--event-repository-id", required=True, type=int)
    parser.add_argument("--repository-fork", required=True, type=parse_boolean)
    parser.add_argument("--workflow-ref", required=True)
    parser.add_argument("--workflow-sha", required=True)
    parser.add_argument("--github-workflow-ref", required=True)
    parser.add_argument("--github-workflow-sha", required=True)
    parser.add_argument(
        "--required-runs", required=True, type=parse_required_runs
    )
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--summary", required=True, type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    tag_document: object = None
    main_document: object = None
    try:
        tag_document = load_document(args.tag_json)
        main_document = load_document(args.main_json)
        evidence = audit_documents(
            load_document(args.runs_json),
            load_document(args.jobs_json),
            tag_document,
            main_document,
            args.required_runs,
            args.ref,
            args.repository,
            args.repository_id,
            args.event_repository_id,
            args.repository_fork,
            args.workflow_ref,
            args.workflow_sha,
            args.github_workflow_ref,
            args.github_workflow_sha,
        )
    except (OSError, UnicodeError, json.JSONDecodeError, InputError) as error:
        evidence = failed_evidence(
            args.ref,
            args.required_runs,
            args.repository,
            args.repository_id,
            args.event_repository_id,
            args.repository_fork,
            args.workflow_ref,
            args.workflow_sha,
            args.github_workflow_ref,
            args.github_workflow_sha,
            tag_document,
            main_document,
            str(error),
        )
        print(f"::error::Candidate soak input was invalid: {error}", file=sys.stderr)

    try:
        write_results(evidence, args.output, args.summary)
    except (OSError, UnicodeError, TypeError, ValueError) as error:
        print(f"::error::Could not write candidate soak evidence: {error}", file=sys.stderr)
        return 1

    if evidence["verdict"] == "pass":
        print(f"Candidate soak passed with a clean streak of {evidence['cleanStreak']}.")
        return 0
    print(
        f"Candidate soak failed with a clean streak of {evidence['cleanStreak']}.",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
