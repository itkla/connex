import copy
import importlib.util
import json
import re
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

import yaml


SCRIPT = Path(__file__).with_name("audit-candidate-soak.py")
ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
CANDIDATE_WORKFLOW = WORKFLOWS / "candidate-soak.yml"
RELEASE_DOCUMENT = ROOT / "docs" / "RELEASE.md"
SHA = "a" * 40
OTHER_SHA = "b" * 40
TAG_OBJECT_ID = "e" * 40
MAIN_SHA = "d" * 40
REF = "candidate-soak-v0.9.0-tc.2"
REPOSITORY = "itkla/connex"
REPOSITORY_ID = 1222010579
WORKFLOW_REF = "refs/heads/main"
WORKFLOW_SHA = "c" * 40
GITHUB_WORKFLOW_REF = (
    "itkla/connex/.github/workflows/candidate-soak.yml@refs/heads/main"
)
GITHUB_WORKFLOW_SHA = WORKFLOW_SHA
TAGGED_AT = datetime(2026, 6, 30, 6, tzinfo=timezone.utc)
SPEC = importlib.util.spec_from_file_location("audit_candidate_soak", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load the candidate soak auditor")
AUDITOR = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = AUDITOR
SPEC.loader.exec_module(AUDITOR)


def timestamp(value: datetime) -> str:
    return value.isoformat().replace("+00:00", "Z")


def run_fixture(
    workflow: object,
    run_id: int,
    run_number: int,
    created_at: datetime,
) -> dict[str, object]:
    return {
        "id": run_id,
        "path": workflow.path,
        "name": workflow.display_name,
        "head_sha": SHA,
        "head_branch": REF,
        "run_number": run_number,
        "run_attempt": 1,
        "referenced_workflows": [],
        "event": "workflow_dispatch",
        "status": "completed",
        "conclusion": "success",
        "html_url": f"https://github.com/itkla/connex/actions/runs/{run_id}",
        "created_at": timestamp(created_at),
        "run_started_at": timestamp(created_at + timedelta(minutes=1)),
        "updated_at": timestamp(created_at + timedelta(minutes=10)),
    }


def job_fixture(
    run_id: int, job_id: int, name: str, completed_at: datetime
) -> dict[str, object]:
    return {
        "id": job_id,
        "run_id": run_id,
        "name": name,
        "status": "completed",
        "conclusion": "success",
        "started_at": timestamp(completed_at),
        "completed_at": timestamp(completed_at),
    }


def tag_snapshot(
    tag_object_id: str = TAG_OBJECT_ID, commit_sha: str = SHA
) -> dict[str, object]:
    return {
        "ref": {
            "ref": f"refs/tags/{REF}",
            "object": {"type": "tag", "sha": tag_object_id},
        },
        "tag": {
            "sha": tag_object_id,
        "tag": REF,
        "object": {"type": "commit", "sha": commit_sha},
        "tagger": {"date": timestamp(TAGGED_AT)},
    },
}


def tag_binding_fixture() -> dict[str, object]:
    return {"start": tag_snapshot(), "end": tag_snapshot()}


def main_binding_fixture(
    status: str = "behind",
    main_sha: str = MAIN_SHA,
    candidate_sha: str = SHA,
) -> dict[str, object]:
    if status == "identical":
        ahead_by = 0
        behind_by = 0
        merge_base_sha = candidate_sha
    elif status == "behind":
        ahead_by = 0
        behind_by = 1
        merge_base_sha = candidate_sha
    else:
        ahead_by = 1
        behind_by = 1
        merge_base_sha = OTHER_SHA
    return {
        "ref": {
            "ref": "refs/heads/main",
            "object": {"type": "commit", "sha": main_sha},
        },
        "compare": {
            "status": status,
            "ahead_by": ahead_by,
            "behind_by": behind_by,
            "base_commit": {"sha": main_sha},
            "merge_base_commit": {"sha": merge_base_sha},
        },
    }


def job_page_set(run: dict[str, object], workflow: object) -> dict[str, object]:
    run_id = run["id"]
    if not isinstance(run_id, int):
        raise AssertionError("fixture run id is not an integer")
    started_at = run.get("run_started_at")
    if not isinstance(started_at, str):
        raise AssertionError("fixture run start is not a string")
    completed_at = datetime.fromisoformat(started_at.replace("Z", "+00:00"))
    jobs = [
        job_fixture(run_id, run_id * 1000 + index, name, completed_at)
        for index, name in enumerate(workflow.mandatory_jobs, start=1)
    ]
    return {
        "run_id": run_id,
        "run_attempt": run["run_attempt"],
        "pages": [{"total_count": len(jobs), "jobs": jobs}],
    }


def fixtures(count: int) -> tuple[dict[str, object], list[object]]:
    runs: list[dict[str, object]] = []
    job_sets: list[dict[str, object]] = []
    epoch = datetime(2026, 8, 1, 6, tzinfo=timezone.utc)
    for round_number in range(1, count + 1):
        round_start = epoch + timedelta(days=round_number - 1)
        for workflow_index, workflow in enumerate(AUDITOR.WORKFLOW_SPECS):
            run_id = round_number * 100 + workflow_index + 1
            run = run_fixture(
                workflow,
                run_id,
                round_number,
                round_start + timedelta(minutes=workflow_index * 30),
            )
            runs.append(run)
            job_sets.append(job_page_set(run, workflow))
    pages = [{"total_count": len(runs), "workflow_runs": runs}]
    return ({"start": pages, "end": pages}, job_sets)


def runs_in(document: dict[str, object]) -> list[dict[str, object]]:
    pages = document.get("start")
    if not isinstance(pages, list) or not pages:
        raise AssertionError("fixture run pages are invalid")
    page = pages[0]
    if not isinstance(page, dict):
        raise AssertionError("fixture page is not an object")
    runs = page["workflow_runs"]
    if not isinstance(runs, list) or any(not isinstance(run, dict) for run in runs):
        raise AssertionError("fixture runs are not objects")
    return runs


def set_run_total(document: dict[str, object]) -> None:
    pages = document.get("start")
    if not isinstance(pages, list) or not pages:
        raise AssertionError("fixture run pages are invalid")
    page = pages[0]
    if not isinstance(page, dict):
        raise AssertionError("fixture page is not an object")
    page["total_count"] = len(runs_in(document))


def find_run(
    document: dict[str, object], workflow_path: str, round_number: int
) -> dict[str, object]:
    for run in runs_in(document):
        if run.get("path") == workflow_path and run.get("run_number") == round_number:
            return run
    raise AssertionError(f"run fixture not found: {workflow_path} round {round_number}")


def find_job_set(document: list[object], run_id: object) -> dict[str, object]:
    for page_set in document:
        if isinstance(page_set, dict) and page_set.get("run_id") == run_id:
            return page_set
    raise AssertionError(f"job fixture not found: run {run_id}")


def jobs_for_run(document: list[object], run_id: object) -> list[dict[str, object]]:
    page_set = find_job_set(document, run_id)
    pages = page_set.get("pages")
    if not isinstance(pages, list) or len(pages) != 1:
        raise AssertionError("fixture job pages are invalid")
    page = pages[0]
    if not isinstance(page, dict):
        raise AssertionError("fixture job page is not an object")
    jobs = page.get("jobs")
    if not isinstance(jobs, list) or any(not isinstance(job, dict) for job in jobs):
        raise AssertionError("fixture jobs are not objects")
    return jobs


def set_job_total(document: list[object], run_id: object) -> None:
    page_set = find_job_set(document, run_id)
    pages = page_set["pages"]
    if not isinstance(pages, list) or not isinstance(pages[0], dict):
        raise AssertionError("fixture job pages are invalid")
    pages[0]["total_count"] = len(jobs_for_run(document, run_id))


def append_run(
    runs_document: dict[str, object],
    jobs_document: list[object],
    workflow: object,
    run_id: int,
    run_number: int,
    created_at: datetime,
) -> dict[str, object]:
    run = run_fixture(workflow, run_id, run_number, created_at)
    runs_in(runs_document).append(run)
    set_run_total(runs_document)
    jobs_document.append(job_page_set(run, workflow))
    return run


def audit(
    runs_document: object,
    jobs_document: object,
    required_runs: int = 10,
    ref: str = REF,
    tag_document: object | None = None,
    main_document: object | None = None,
    repository: str = REPOSITORY,
    repository_id: int = REPOSITORY_ID,
    event_repository_id: int = REPOSITORY_ID,
    repository_fork: bool = False,
    workflow_ref: str = WORKFLOW_REF,
    workflow_sha: str = WORKFLOW_SHA,
    github_workflow_ref: str = GITHUB_WORKFLOW_REF,
    github_workflow_sha: str = GITHUB_WORKFLOW_SHA,
) -> dict[str, object]:
    return AUDITOR.audit_documents(
        runs_document,
        jobs_document,
        tag_binding_fixture() if tag_document is None else tag_document,
        main_binding_fixture() if main_document is None else main_document,
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


def audit_with_main(
    runs_document: object,
    jobs_document: object,
    main_document: object,
    tag_document: object | None = None,
) -> dict[str, object]:
    return audit(
        runs_document,
        jobs_document,
        tag_document=tag_document,
        main_document=main_document,
    )


def workflow_needs(job: dict[str, object]) -> set[str]:
    needs = job.get("needs", [])
    if isinstance(needs, str):
        return {needs}
    if isinstance(needs, list) and all(isinstance(item, str) for item in needs):
        return set(needs)
    raise AssertionError("workflow job has invalid needs")


def runs_on_workflow_dispatch(job: dict[str, object]) -> bool:
    condition = job.get("if")
    if not isinstance(condition, str):
        return True
    event_only = re.fullmatch(r"github\.event_name == '([^']+)'", condition)
    return event_only is None or event_only.group(1) == "workflow_dispatch"


def required_dispatch_job_names(document: dict[str, object]) -> set[str]:
    jobs = document.get("jobs")
    if not isinstance(jobs, dict):
        raise AssertionError("workflow jobs are not an object")
    gates = [
        (job_id, job)
        for job_id, job in jobs.items()
        if isinstance(job_id, str)
        and isinstance(job, dict)
        and job.get("if") == "always()"
    ]
    if len(gates) != 1:
        raise AssertionError("workflow must have one aggregate gate")
    gate_id, gate = gates[0]
    gate_dependencies = workflow_needs(gate)
    internal_dependencies = {
        dependency
        for job_id in gate_dependencies
        for dependency in workflow_needs(jobs[job_id])
    }
    leaf_ids = gate_dependencies.difference(internal_dependencies)
    dispatch_leaf_ids = {
        job_id for job_id in leaf_ids if runs_on_workflow_dispatch(jobs[job_id])
    }
    evidence_ids = dispatch_leaf_ids | {gate_id}
    names = {jobs[job_id].get("name") for job_id in evidence_ids}
    if any(not isinstance(name, str) for name in names):
        raise AssertionError("workflow evidence job is missing a name")
    return names


class CandidateSoakCountingTest(unittest.TestCase):
    def test_ten_real_candidate_rounds_pass_with_stable_evidence(self) -> None:
        runs_document, jobs_document = fixtures(10)

        evidence = audit(runs_document, jobs_document)

        self.assertEqual(
            {
                "schemaVersion",
                "ref",
                "resolvedSha",
                "repositoryProvenance",
                "workflowProvenance",
                "tagBinding",
                "mainBinding",
                "requestedRuns",
                "requiredRuns",
                "cleanStreak",
                "verdict",
                "reasons",
                "coverageGaps",
                "pipelines",
                "streakBreaks",
            },
            set(evidence),
        )
        self.assertEqual(2, evidence["schemaVersion"])
        self.assertEqual(REF, evidence["ref"])
        self.assertEqual(SHA, evidence["resolvedSha"])
        self.assertEqual(
            {
                "github.repository": REPOSITORY,
                "github.repository_id": REPOSITORY_ID,
                "github.event.repository.id": REPOSITORY_ID,
                "github.event.repository.fork": False,
            },
            evidence["repositoryProvenance"],
        )
        self.assertEqual(
            {
                "github.ref": WORKFLOW_REF,
                "github.sha": WORKFLOW_SHA,
                "github.workflow_ref": GITHUB_WORKFLOW_REF,
                "github.workflow_sha": GITHUB_WORKFLOW_SHA,
            },
            evidence["workflowProvenance"],
        )
        self.assertEqual(
            {
                "start": {
                    "tagObjectId": TAG_OBJECT_ID,
                    "commitSha": SHA,
                    "taggerDate": timestamp(TAGGED_AT),
                },
                "end": {
                    "tagObjectId": TAG_OBJECT_ID,
                    "commitSha": SHA,
                    "taggerDate": timestamp(TAGGED_AT),
                },
            },
            evidence["tagBinding"],
        )
        self.assertEqual(
            {
                "mainSha": MAIN_SHA,
                "candidateSha": SHA,
                "compareStatus": "behind",
            },
            evidence["mainBinding"],
        )
        self.assertEqual(10, evidence["requestedRuns"])
        self.assertEqual(10, evidence["requiredRuns"])
        self.assertEqual(10, evidence["cleanStreak"])
        self.assertEqual("pass", evidence["verdict"])
        self.assertEqual([], evidence["reasons"])
        self.assertEqual(10, len(evidence["pipelines"]))
        self.assertTrue(all(pipeline["counted"] for pipeline in evidence["pipelines"]))
        self.assertEqual([], evidence["streakBreaks"])
        newest_ci = evidence["pipelines"][0]["runs"]["CI"]
        self.assertEqual(SHA, newest_ci["headSha"])
        self.assertEqual(REF, newest_ci["headBranch"])
        self.assertEqual("completed", newest_ci["status"])
        newest_ci_fixture = find_run(
            runs_document, ".github/workflows/ci.yml", 10
        )
        self.assertEqual(
            {
                "jobId": newest_ci_fixture["id"] * 1000 + 1,
                "status": "completed",
                "conclusion": "success",
                "startedAt": newest_ci_fixture["run_started_at"],
                "completedAt": newest_ci_fixture["run_started_at"],
            },
            newest_ci["mandatoryJobs"]["OCR — contract tests"],
        )
        summary = AUDITOR.render_summary(evidence)
        self.assertIn("PASS (qualified)", summary)
        self.assertIn(f"`github.ref`: `{WORKFLOW_REF}`", summary)
        self.assertIn(f"`github.repository`: `{REPOSITORY}`", summary)
        self.assertIn(f"`github.repository_id`: `{REPOSITORY_ID}`", summary)
        self.assertIn(f"`github.sha`: `{WORKFLOW_SHA}`", summary)
        self.assertIn(
            f"`github.workflow_ref`: `{GITHUB_WORKFLOW_REF}`", summary
        )
        self.assertIn(
            f"`github.workflow_sha`: `{GITHUB_WORKFLOW_SHA}`", summary
        )
        for run in runs_in(runs_document):
            self.assertIn(str(run["html_url"]), summary)

    def test_nine_candidate_rounds_fail_with_each_deficit_named(self) -> None:
        runs_document, jobs_document = fixtures(9)

        evidence = audit(runs_document, jobs_document)

        self.assertEqual("fail", evidence["verdict"])
        self.assertEqual(9, evidence["cleanStreak"])
        reasons = "\n".join(evidence["reasons"])
        for workflow_name in ("CI", "Security", "Deploy smoke"):
            self.assertIn(f"{workflow_name} has 9 run(s)", reasons)
        self.assertIn("newest proven candidate pipeline streak is 9", reasons)

    def test_mid_streak_failure_resets_the_streak(self) -> None:
        runs_document, jobs_document = fixtures(10)
        failed = find_run(runs_document, ".github/workflows/ci.yml", 6)
        failed["conclusion"] = "failure"

        evidence = audit(runs_document, jobs_document)

        self.assertEqual("fail", evidence["verdict"])
        self.assertEqual(4, evidence["cleanStreak"])
        reasons = [
            reason
            for streak_break in evidence["streakBreaks"]
            for reason in streak_break["reasons"]
        ]
        self.assertTrue(any("concluded failure" in reason for reason in reasons))

    def test_ten_new_rounds_recover_after_older_failure(self) -> None:
        runs_document, jobs_document = fixtures(11)
        failed = find_run(runs_document, ".github/workflows/ci.yml", 1)
        failed["conclusion"] = "failure"

        evidence = audit(runs_document, jobs_document)

        self.assertEqual("pass", evidence["verdict"])
        self.assertEqual(10, evidence["cleanStreak"])
        self.assertTrue(all(pipeline["counted"] for pipeline in evidence["pipelines"][:10]))

    def test_interleaved_failure_and_extra_dispatch_cannot_fabricate_rounds(self) -> None:
        runs_document, jobs_document = fixtures(10)
        failed = find_run(runs_document, ".github/workflows/security.yml", 1)
        failed["conclusion"] = "failure"
        append_run(
            runs_document,
            jobs_document,
            AUDITOR.WORKFLOW_SPECS[1],
            9999,
            11,
            datetime(2026, 8, 11, 6, 30, tzinfo=timezone.utc),
        )

        evidence = audit(runs_document, jobs_document)

        self.assertEqual("fail", evidence["verdict"])
        self.assertEqual(0, evidence["cleanStreak"])
        self.assertEqual(9, len(evidence["pipelines"]))
        self.assertTrue(
            any(
                streak_break["runId"] == 9999
                for streak_break in evidence["streakBreaks"]
            )
        )

    def test_cross_month_histories_cannot_be_zipped_into_rounds(self) -> None:
        runs_document, jobs_document = fixtures(10)
        month_by_path = {
            ".github/workflows/ci.yml": 9,
            ".github/workflows/security.yml": 8,
            ".github/workflows/deploy-smoke.yml": 7,
        }
        for run in runs_in(runs_document):
            path = run["path"]
            number = run["run_number"]
            if not isinstance(path, str) or not isinstance(number, int):
                raise AssertionError("fixture run fields are invalid")
            created = datetime(
                2026, month_by_path[path], number, 6, tzinfo=timezone.utc
            )
            run["created_at"] = timestamp(created)
            run["run_started_at"] = timestamp(created + timedelta(minutes=1))
            run["updated_at"] = timestamp(created + timedelta(minutes=10))
            for job in jobs_for_run(jobs_document, run["id"]):
                job["started_at"] = timestamp(created + timedelta(minutes=2))
                job["completed_at"] = timestamp(created + timedelta(minutes=8))

        evidence = audit(runs_document, jobs_document)

        self.assertEqual("fail", evidence["verdict"])
        self.assertEqual(0, evidence["cleanStreak"])
        self.assertEqual([], evidence["pipelines"])

    def test_round_exceeding_six_hour_window_does_not_count(self) -> None:
        runs_document, jobs_document = fixtures(10)
        deploy = find_run(runs_document, ".github/workflows/deploy-smoke.yml", 10)
        deploy["created_at"] = "2026-08-10T13:00:00Z"
        deploy["run_started_at"] = "2026-08-10T13:01:00Z"
        deploy["updated_at"] = "2026-08-10T13:10:00Z"
        for job in jobs_for_run(jobs_document, deploy["id"]):
            job["started_at"] = "2026-08-10T13:02:00Z"
            job["completed_at"] = "2026-08-10T13:08:00Z"

        evidence = audit(runs_document, jobs_document)

        self.assertEqual("fail", evidence["verdict"])
        self.assertEqual(0, evidence["cleanStreak"])
        self.assertTrue(
            any(
                "six-hour coherence window" in reason
                for streak_break in evidence["streakBreaks"]
                for reason in streak_break["reasons"]
            )
        )

    def test_workflow_overlap_before_previous_jobs_complete_breaks_round(self) -> None:
        runs_document, jobs_document = fixtures(10)
        ci = find_run(runs_document, ".github/workflows/ci.yml", 10)
        ci["updated_at"] = "2026-08-10T06:32:00Z"
        jobs_for_run(jobs_document, ci["id"])[0][
            "completed_at"
        ] = "2026-08-10T06:32:00Z"

        evidence = audit(runs_document, jobs_document)

        self.assertEqual("fail", evidence["verdict"])
        self.assertEqual(0, evidence["cleanStreak"])
        self.assertTrue(
            any(
                "and all of its jobs completed" in reason
                for streak_break in evidence["streakBreaks"]
                for reason in streak_break["reasons"]
            )
        )

    def test_next_round_starting_before_prior_run_update_breaks_streak(self) -> None:
        runs_document, jobs_document = fixtures(11)
        prior_deploy = find_run(
            runs_document, ".github/workflows/deploy-smoke.yml", 1
        )
        prior_deploy["updated_at"] = "2026-08-02T06:02:00Z"

        evidence = audit(runs_document, jobs_document)

        self.assertEqual("fail", evidence["verdict"])
        self.assertEqual(9, evidence["cleanStreak"])
        self.assertTrue(
            any(
                "before Deploy smoke run" in reason
                for streak_break in evidence["streakBreaks"]
                for reason in streak_break["reasons"]
            )
        )

    def test_new_streak_cannot_overlap_preceding_failed_run(self) -> None:
        runs_document, jobs_document = fixtures(11)
        failed_deploy = find_run(
            runs_document, ".github/workflows/deploy-smoke.yml", 1
        )
        failed_deploy["conclusion"] = "failure"
        failed_deploy["updated_at"] = "2026-08-02T06:02:00Z"

        evidence = audit(runs_document, jobs_document)

        self.assertEqual("fail", evidence["verdict"])
        self.assertEqual(9, evidence["cleanStreak"])
        self.assertTrue(
            any(
                "before Deploy smoke run" in reason
                for streak_break in evidence["streakBreaks"]
                for reason in streak_break["reasons"]
            )
        )

    def test_successful_rerun_resets_the_streak(self) -> None:
        runs_document, jobs_document = fixtures(10)
        rerun = find_run(runs_document, ".github/workflows/security.yml", 10)
        rerun["run_attempt"] = 2
        find_job_set(jobs_document, rerun["id"])["run_attempt"] = 2

        evidence = audit(runs_document, jobs_document)

        self.assertEqual("fail", evidence["verdict"])
        self.assertEqual(0, evidence["cleanStreak"])
        self.assertTrue(
            any(
                "attempt 2" in reason
                for streak_break in evidence["streakBreaks"]
                for reason in streak_break["reasons"]
            )
        )

    def test_skipped_pin_policy_job_resets_the_streak(self) -> None:
        runs_document, jobs_document = fixtures(10)
        security = find_run(runs_document, ".github/workflows/security.yml", 10)
        pin_job = next(
            job
            for job in jobs_for_run(jobs_document, security["id"])
            if job.get("name") == "Workflow action pin policy"
        )
        pin_job["conclusion"] = "skipped"

        evidence = audit(runs_document, jobs_document)

        self.assertEqual("fail", evidence["verdict"])
        self.assertEqual(0, evidence["cleanStreak"])
        reasons = [
            reason
            for streak_break in evidence["streakBreaks"]
            for reason in streak_break["reasons"]
        ]
        self.assertTrue(
            any(
                "Workflow action pin policy concluded skipped" in reason
                for reason in reasons
            )
        )
        security_streak_break = next(
            streak_break
            for streak_break in evidence["streakBreaks"]
            if streak_break["runId"] == security["id"]
        )
        self.assertEqual(
            {
                "jobId": security["id"] * 1000 + 1,
                "status": "completed",
                "conclusion": "skipped",
                "startedAt": security["run_started_at"],
                "completedAt": security["run_started_at"],
            },
            security_streak_break["mandatoryJobs"]["Workflow action pin policy"],
        )

    def test_missing_mandatory_job_resets_the_streak(self) -> None:
        runs_document, jobs_document = fixtures(10)
        ci_run = find_run(runs_document, ".github/workflows/ci.yml", 10)
        jobs = jobs_for_run(jobs_document, ci_run["id"])
        jobs[:] = [job for job in jobs if job.get("name") != "OCR — contract tests"]
        set_job_total(jobs_document, ci_run["id"])

        evidence = audit(runs_document, jobs_document)

        self.assertEqual("fail", evidence["verdict"])
        reasons = [
            reason
            for streak_break in evidence["streakBreaks"]
            for reason in streak_break["reasons"]
        ]
        self.assertTrue(any("missing mandatory job" in reason for reason in reasons))

    def test_wrong_ref_at_same_sha_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(10)
        find_run(runs_document, ".github/workflows/ci.yml", 10)["head_branch"] = "main"

        with self.assertRaisesRegex(AUDITOR.InputError, "unexpected run"):
            audit(runs_document, jobs_document)

    def test_extra_different_sha_on_qualification_ref_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(10)
        extra = append_run(
            runs_document,
            jobs_document,
            AUDITOR.WORKFLOW_SPECS[0],
            9998,
            11,
            datetime(2026, 8, 11, 6, tzinfo=timezone.utc),
        )
        extra["head_sha"] = OTHER_SHA

        with self.assertRaisesRegex(AUDITOR.InputError, "do not match resolved commit"):
            audit(runs_document, jobs_document)

    def test_non_dispatch_event_resets_the_streak(self) -> None:
        runs_document, jobs_document = fixtures(10)
        find_run(runs_document, ".github/workflows/security.yml", 10)["event"] = "push"

        evidence = audit(runs_document, jobs_document)

        self.assertEqual("fail", evidence["verdict"])
        self.assertEqual(0, evidence["cleanStreak"])
        self.assertTrue(
            any(
                "used event push" in reason
                for streak_break in evidence["streakBreaks"]
                for reason in streak_break["reasons"]
            )
        )

    def test_in_progress_run_with_success_conclusion_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(10)
        run = find_run(runs_document, ".github/workflows/ci.yml", 10)
        run["status"] = "in_progress"

        with self.assertRaisesRegex(AUDITOR.InputError, "non-completed status"):
            audit(runs_document, jobs_document)

    def test_in_progress_job_with_success_conclusion_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(10)
        deploy = find_run(runs_document, ".github/workflows/deploy-smoke.yml", 10)
        jobs_for_run(jobs_document, deploy["id"])[0]["status"] = "in_progress"

        with self.assertRaisesRegex(AUDITOR.InputError, "non-completed status"):
            audit(runs_document, jobs_document)

    def test_old_in_progress_run_cannot_age_out_of_the_gate(self) -> None:
        runs_document, jobs_document = fixtures(11)
        find_run(runs_document, ".github/workflows/ci.yml", 1)[
            "status"
        ] = "in_progress"

        with self.assertRaisesRegex(AUDITOR.InputError, "non-completed status"):
            audit(runs_document, jobs_document)

    def test_old_in_progress_job_cannot_age_out_of_the_gate(self) -> None:
        runs_document, jobs_document = fixtures(11)
        ci = find_run(runs_document, ".github/workflows/ci.yml", 1)
        jobs_for_run(jobs_document, ci["id"])[0]["status"] = "in_progress"

        with self.assertRaisesRegex(AUDITOR.InputError, "non-completed status"):
            audit(runs_document, jobs_document)

    def test_unknown_run_status_cannot_age_out_of_the_gate(self) -> None:
        runs_document, jobs_document = fixtures(11)
        find_run(runs_document, ".github/workflows/ci.yml", 1)[
            "status"
        ] = "definitely-not-a-github-status"

        with self.assertRaisesRegex(AUDITOR.InputError, "unrecognised status"):
            audit(runs_document, jobs_document)

    def test_nonmandatory_in_progress_job_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(10)
        ci = find_run(runs_document, ".github/workflows/ci.yml", 1)
        run_id = ci["id"]
        if not isinstance(run_id, int):
            raise AssertionError("fixture run id is invalid")
        completed_at = datetime.fromisoformat(
            str(ci["run_started_at"]).replace("Z", "+00:00")
        )
        extra = job_fixture(run_id, 999999, "Runner cleanup", completed_at)
        extra["status"] = "in_progress"
        jobs_for_run(jobs_document, run_id).append(extra)
        set_job_total(jobs_document, run_id)

        with self.assertRaisesRegex(AUDITOR.InputError, "non-completed status"):
            audit(runs_document, jobs_document)

    def test_each_terminal_failure_conclusion_resets_the_streak(self) -> None:
        for conclusion in ("failure", "timed_out", "cancelled", "startup_failure"):
            with self.subTest(conclusion=conclusion):
                runs_document, jobs_document = fixtures(10)
                failed = find_run(runs_document, ".github/workflows/ci.yml", 10)
                failed["conclusion"] = conclusion

                evidence = audit(runs_document, jobs_document)

                self.assertEqual("fail", evidence["verdict"])
                self.assertEqual(0, evidence["cleanStreak"])

    def test_unrelated_workflows_are_ignored(self) -> None:
        runs_document, jobs_document = fixtures(10)
        runs_in(runs_document).append(
            {
                "id": 999999,
                "path": ".github/workflows/main-red-alert.yml",
                "name": "CI",
            }
        )
        set_run_total(runs_document)

        evidence = audit(runs_document, jobs_document)

        self.assertEqual("pass", evidence["verdict"])

    def test_workflow_path_not_display_name_is_the_identity(self) -> None:
        runs_document, jobs_document = fixtures(10)
        for run in runs_in(runs_document):
            run["name"] = "Misleading display name"
            run["path"] = f"{run['path']}@refs/tags/{REF}"

        evidence = audit(runs_document, jobs_document)

        self.assertEqual("pass", evidence["verdict"])


class CandidateSoakFailClosedTest(unittest.TestCase):
    def test_ten_green_rounds_created_before_the_tag_are_unusable(self) -> None:
        runs_document, jobs_document = fixtures(10)
        tag_document = tag_binding_fixture()
        for snapshot in tag_document.values():
            snapshot["tag"]["tagger"]["date"] = "2026-09-01T00:00:00Z"

        with self.assertRaisesRegex(AUDITOR.InputError, "predates qualification tag"):
            audit(runs_document, jobs_document, tag_document=tag_document)

    def test_one_pre_tag_round_cannot_age_out_before_ten_new_rounds(self) -> None:
        runs_document, jobs_document = fixtures(11)
        tag_document = tag_binding_fixture()
        for snapshot in tag_document.values():
            snapshot["tag"]["tagger"]["date"] = "2026-08-01T23:00:00Z"

        with self.assertRaisesRegex(AUDITOR.InputError, "predates qualification tag"):
            audit(runs_document, jobs_document, tag_document=tag_document)

    def test_candidate_commit_not_reachable_from_main_is_unusable(self) -> None:
        for status in ("ahead", "diverged"):
            with self.subTest(status=status):
                runs_document, jobs_document = fixtures(10)

                with self.assertRaisesRegex(
                    AUDITOR.InputError, "not reachable from main"
                ):
                    audit_with_main(
                        runs_document,
                        jobs_document,
                        main_binding_fixture(status=status),
                    )

    def test_candidate_commit_at_main_head_is_usable(self) -> None:
        runs_document, jobs_document = fixtures(10)

        evidence = audit_with_main(
            runs_document,
            jobs_document,
            main_binding_fixture(status="identical", main_sha=SHA),
        )

        self.assertEqual("pass", evidence["verdict"])
        self.assertEqual(SHA, evidence["mainBinding"]["mainSha"])
        self.assertEqual("identical", evidence["mainBinding"]["compareStatus"])

    def test_duplicate_run_number_within_one_workflow_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(10)
        find_run(runs_document, ".github/workflows/ci.yml", 2)["run_number"] = 1

        with self.assertRaisesRegex(AUDITOR.InputError, "run number 1 appears more than once"):
            audit(runs_document, jobs_document)

    def test_run_workflow_provenance_is_retained_in_evidence(self) -> None:
        runs_document, jobs_document = fixtures(10)
        ci = find_run(runs_document, ".github/workflows/ci.yml", 10)
        ci["path"] = f".github/workflows/ci.yml@refs/tags/{REF}"
        ci["referenced_workflows"] = [
            {
                "path": f"itkla/connex/.github/workflows/shared.yml@{SHA}",
                "sha": SHA,
                "ref": f"refs/tags/{REF}",
            }
        ]

        evidence = audit(runs_document, jobs_document)

        ci_evidence = evidence["pipelines"][0]["runs"]["CI"]
        self.assertEqual(ci["path"], ci_evidence["path"])
        self.assertEqual(ci["referenced_workflows"], ci_evidence["referencedWorkflows"])

    def test_failed_evidence_records_both_tag_snapshots(self) -> None:
        tag_document = tag_binding_fixture()
        tag_document["end"] = tag_snapshot("f" * 40, OTHER_SHA)
        evidence = AUDITOR.failed_evidence(
            REF,
            10,
            REPOSITORY,
            REPOSITORY_ID,
            REPOSITORY_ID,
            False,
            WORKFLOW_REF,
            WORKFLOW_SHA,
            GITHUB_WORKFLOW_REF,
            GITHUB_WORKFLOW_SHA,
            tag_document,
            main_binding_fixture(),
            "regression fixture",
        )

        self.assertEqual(
            {
                "start": {
                    "tagObjectId": TAG_OBJECT_ID,
                    "commitSha": SHA,
                    "taggerDate": timestamp(TAGGED_AT),
                },
                "end": {
                    "tagObjectId": "f" * 40,
                    "commitSha": OTHER_SHA,
                    "taggerDate": timestamp(TAGGED_AT),
                },
            },
            evidence["tagBinding"],
        )

    def test_fork_repository_provenance_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(10)

        with self.assertRaisesRegex(AUDITOR.InputError, "must be false"):
            audit(runs_document, jobs_document, repository_fork=True)

    def test_noncanonical_repository_name_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(10)

        with self.assertRaisesRegex(AUDITOR.InputError, "github.repository must"):
            audit(runs_document, jobs_document, repository="someone/connex")

    def test_noncanonical_repository_id_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(10)

        with self.assertRaisesRegex(AUDITOR.InputError, "github.repository_id must"):
            audit(runs_document, jobs_document, repository_id=42)

    def test_event_repository_id_must_match_the_pinned_id(self) -> None:
        runs_document, jobs_document = fixtures(10)

        with self.assertRaisesRegex(AUDITOR.InputError, "repository.id must"):
            audit(runs_document, jobs_document, event_repository_id=42)

    def test_lightweight_qualification_tag_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(10)
        tag_document = tag_binding_fixture()
        start = tag_document["start"]
        if not isinstance(start, dict) or not isinstance(start.get("ref"), dict):
            raise AssertionError("fixture tag binding is invalid")
        start["ref"]["object"] = {"type": "commit", "sha": SHA}

        with self.assertRaisesRegex(AUDITOR.InputError, "not an annotated tag"):
            audit(runs_document, jobs_document, tag_document=tag_document)

    def test_tag_move_during_collection_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(10)
        tag_document = tag_binding_fixture()
        tag_document["end"] = tag_snapshot("f" * 40, OTHER_SHA)

        with self.assertRaisesRegex(AUDITOR.InputError, "changed during collection"):
            audit(runs_document, jobs_document, tag_document=tag_document)

    def test_relevant_run_snapshot_change_during_collection_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(10)
        end_pages = copy.deepcopy(runs_document["end"])
        if not isinstance(end_pages, list):
            raise AssertionError("fixture end run pages are invalid")
        runs_document["end"] = end_pages
        end_runs = end_pages[0]["workflow_runs"]
        if not isinstance(end_runs, list) or not isinstance(end_runs[0], dict):
            raise AssertionError("fixture end runs are invalid")
        end_runs[0]["updated_at"] = "2026-08-01T06:11:00Z"

        with self.assertRaisesRegex(AUDITOR.InputError, "changed during evidence"):
            audit(runs_document, jobs_document)

    def test_required_runs_below_ten_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(10)

        with self.assertRaisesRegex(AUDITOR.InputError, "at least 10"):
            audit(runs_document, jobs_document, required_runs=9)

    def test_dispatch_may_raise_required_runs_and_records_effective_value(self) -> None:
        runs_document, jobs_document = fixtures(11)

        evidence = audit(runs_document, jobs_document, required_runs=11)

        self.assertEqual("pass", evidence["verdict"])
        self.assertEqual(11, evidence["requestedRuns"])
        self.assertEqual(11, evidence["requiredRuns"])

    def test_non_main_auditor_workflow_provenance_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(1)

        with self.assertRaisesRegex(AUDITOR.InputError, "is not allowed"):
            audit(
                runs_document,
                jobs_document,
                workflow_ref="refs/heads/modified-auditor",
            )

    def test_noncanonical_workflow_file_ref_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(10)

        with self.assertRaisesRegex(AUDITOR.InputError, "github.workflow_ref must"):
            audit(
                runs_document,
                jobs_document,
                github_workflow_ref=(
                    "someone/connex/.github/workflows/candidate-soak.yml@refs/heads/main"
                ),
            )

    def test_auditor_and_workflow_definition_shas_must_match(self) -> None:
        runs_document, jobs_document = fixtures(10)

        with self.assertRaisesRegex(AUDITOR.InputError, "must be equal"):
            audit(
                runs_document,
                jobs_document,
                github_workflow_sha="d" * 40,
            )

    def test_non_qualification_candidate_ref_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(1)
        for run in runs_in(runs_document):
            run["head_branch"] = "main"

        with self.assertRaisesRegex(AUDITOR.InputError, "ref must match"):
            audit(runs_document, jobs_document, ref="main")

    def test_empty_workflow_run_list_is_unusable(self) -> None:
        pages = [{"total_count": 0, "workflow_runs": []}]
        with self.assertRaisesRegex(AUDITOR.InputError, "no relevant workflow runs"):
            audit({"start": pages, "end": pages}, [])

    def test_non_object_run_page_is_unusable(self) -> None:
        with self.assertRaisesRegex(
            AUDITOR.InputError, "start runs page 1 must be a JSON object"
        ):
            audit({"start": [[{"id": 1}]], "end": [[{"id": 1}]]}, [])

    def test_truncated_run_pages_are_unusable(self) -> None:
        runs_document, jobs_document = fixtures(1)
        pages = runs_document["start"]
        if not isinstance(pages, list):
            raise AssertionError("fixture run pages are invalid")
        page = pages[0]
        if not isinstance(page, dict):
            raise AssertionError("fixture run page is invalid")
        page["total_count"] = 4

        with self.assertRaisesRegex(AUDITOR.InputError, "has 3 workflow_runs, expected 4"):
            audit(runs_document, jobs_document)

    def test_truncated_job_pages_are_unusable(self) -> None:
        runs_document, jobs_document = fixtures(1)
        ci_run = find_run(runs_document, ".github/workflows/ci.yml", 1)
        page_set = find_job_set(jobs_document, ci_run["id"])
        pages = page_set["pages"]
        if not isinstance(pages, list) or not isinstance(pages[0], dict):
            raise AssertionError("fixture job page is invalid")
        pages[0]["total_count"] = len(jobs_for_run(jobs_document, ci_run["id"])) + 1

        with self.assertRaisesRegex(AUDITOR.InputError, "jobs, expected"):
            audit(runs_document, jobs_document)

    def test_duplicate_job_id_masking_an_omitted_record_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(1)
        ci = find_run(runs_document, ".github/workflows/ci.yml", 1)
        jobs = jobs_for_run(jobs_document, ci["id"])
        completed_at = datetime.fromisoformat(
            str(ci["run_started_at"]).replace("Z", "+00:00")
        )
        jobs.extend(
            (
                job_fixture(
                    int(ci["id"]),
                    int(ci["id"]) * 1000 + 100,
                    "Runner preparation",
                    completed_at,
                ),
                job_fixture(
                    int(ci["id"]),
                    int(ci["id"]) * 1000 + 101,
                    "Runner cleanup",
                    completed_at,
                ),
            )
        )
        set_job_total(jobs_document, ci["id"])
        jobs[-1] = dict(jobs[-2])

        with self.assertRaisesRegex(AUDITOR.InputError, "id .* appears more than once"):
            audit(runs_document, jobs_document)

    def test_duplicate_job_id_across_run_page_sets_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(1)
        ci = find_run(runs_document, ".github/workflows/ci.yml", 1)
        security = find_run(runs_document, ".github/workflows/security.yml", 1)
        ci_job_id = jobs_for_run(jobs_document, ci["id"])[0]["id"]
        jobs_for_run(jobs_document, security["id"])[0]["id"] = ci_job_id

        with self.assertRaisesRegex(AUDITOR.InputError, "more than one page set"):
            audit(runs_document, jobs_document)

    def test_duplicate_run_id_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(1)
        ci = find_run(runs_document, ".github/workflows/ci.yml", 1)
        security = find_run(runs_document, ".github/workflows/security.yml", 1)
        security["id"] = ci["id"]

        with self.assertRaisesRegex(AUDITOR.InputError, "id .* appears more than once"):
            audit(runs_document, jobs_document)

    def test_run_missing_completion_timestamp_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(1)
        find_run(runs_document, ".github/workflows/ci.yml", 1).pop("updated_at")

        with self.assertRaisesRegex(AUDITOR.InputError, "invalid updated_at"):
            audit(runs_document, jobs_document)

    def test_job_missing_completion_timestamp_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(1)
        ci = find_run(runs_document, ".github/workflows/ci.yml", 1)
        jobs_for_run(jobs_document, ci["id"])[0].pop("completed_at")

        with self.assertRaisesRegex(AUDITOR.InputError, "invalid completed_at"):
            audit(runs_document, jobs_document)

    def test_job_missing_start_timestamp_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(1)
        ci = find_run(runs_document, ".github/workflows/ci.yml", 1)
        jobs_for_run(jobs_document, ci["id"])[0].pop("started_at")

        with self.assertRaisesRegex(AUDITOR.InputError, "invalid started_at"):
            audit(runs_document, jobs_document)

    def test_job_start_after_completion_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(1)
        ci = find_run(runs_document, ".github/workflows/ci.yml", 1)
        jobs_for_run(jobs_document, ci["id"])[0][
            "started_at"
        ] = "2026-08-01T06:02:00Z"

        with self.assertRaisesRegex(AUDITOR.InputError, "completed before it started"):
            audit(runs_document, jobs_document)

    def test_job_start_before_run_window_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(1)
        ci = find_run(runs_document, ".github/workflows/ci.yml", 1)
        jobs_for_run(jobs_document, ci["id"])[0][
            "started_at"
        ] = "2026-08-01T06:00:00Z"

        with self.assertRaisesRegex(AUDITOR.InputError, "started before the run"):
            audit(runs_document, jobs_document)

    def test_job_completion_after_run_window_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(1)
        ci = find_run(runs_document, ".github/workflows/ci.yml", 1)
        jobs_for_run(jobs_document, ci["id"])[0][
            "completed_at"
        ] = "2026-08-01T06:11:00Z"

        with self.assertRaisesRegex(AUDITOR.InputError, "after the run update"):
            audit(runs_document, jobs_document)

    def test_required_run_missing_status_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(1)
        find_run(runs_document, ".github/workflows/ci.yml", 1).pop("status")

        with self.assertRaisesRegex(AUDITOR.InputError, "missing or invalid status"):
            audit(runs_document, jobs_document)

    def test_required_job_missing_status_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(1)
        ci_run = find_run(runs_document, ".github/workflows/ci.yml", 1)
        jobs_for_run(jobs_document, ci_run["id"])[0].pop("status")

        with self.assertRaisesRegex(AUDITOR.InputError, "missing or invalid status"):
            audit(runs_document, jobs_document)

    def test_jobs_document_without_run_page_set_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(1)
        ci_run = find_run(runs_document, ".github/workflows/ci.yml", 1)
        jobs_document[:] = [
            page_set
            for page_set in jobs_document
            if not isinstance(page_set, dict) or page_set.get("run_id") != ci_run["id"]
        ]

        with self.assertRaisesRegex(AUDITOR.InputError, "no page set for required run"):
            audit(runs_document, jobs_document)

    def test_job_attempt_must_match_run_attempt(self) -> None:
        runs_document, jobs_document = fixtures(1)
        ci_run = find_run(runs_document, ".github/workflows/ci.yml", 1)
        find_job_set(jobs_document, ci_run["id"])["run_attempt"] = 2

        with self.assertRaisesRegex(AUDITOR.InputError, "expected 1"):
            audit(runs_document, jobs_document)

    def test_ambiguous_creation_order_is_unusable(self) -> None:
        runs_document, jobs_document = fixtures(1)
        ci = find_run(runs_document, ".github/workflows/ci.yml", 1)
        security = find_run(runs_document, ".github/workflows/security.yml", 1)
        security["created_at"] = ci["created_at"]
        security["run_started_at"] = "2026-08-01T06:02:00Z"

        with self.assertRaisesRegex(AUDITOR.InputError, "global order is ambiguous"):
            audit(runs_document, jobs_document)

    def test_late_rerun_start_cannot_hide_before_the_counted_window(self) -> None:
        runs_document, jobs_document = fixtures(11)
        old_ci = find_run(runs_document, ".github/workflows/ci.yml", 1)
        old_ci["run_attempt"] = 2
        old_ci["run_started_at"] = "2026-08-12T06:00:00Z"
        old_ci["updated_at"] = "2026-08-12T06:10:00Z"
        for job in jobs_for_run(jobs_document, old_ci["id"]):
            job["started_at"] = "2026-08-12T06:02:00Z"
            job["completed_at"] = "2026-08-12T06:08:00Z"
        find_job_set(jobs_document, old_ci["id"])["run_attempt"] = 2

        with self.assertRaisesRegex(AUDITOR.InputError, "contradict global creation order"):
            audit(runs_document, jobs_document)

    def test_cli_writes_pass_and_fail_evidence(self) -> None:
        runs_document, jobs_document = fixtures(10)
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            runs_path = directory / "runs.json"
            jobs_path = directory / "jobs.json"
            tag_path = directory / "tag.json"
            main_path = directory / "main.json"
            output_path = directory / "evidence.json"
            summary_path = directory / "summary.md"
            runs_path.write_text(json.dumps(runs_document), encoding="utf-8")
            jobs_path.write_text(json.dumps(jobs_document), encoding="utf-8")
            tag_path.write_text(json.dumps(tag_binding_fixture()), encoding="utf-8")
            main_path.write_text(json.dumps(main_binding_fixture()), encoding="utf-8")
            command = [
                sys.executable,
                str(SCRIPT),
                "--runs-json",
                str(runs_path),
                "--jobs-json",
                str(jobs_path),
                "--tag-json",
                str(tag_path),
                "--main-json",
                str(main_path),
                "--ref",
                REF,
                "--repository",
                REPOSITORY,
                "--repository-id",
                str(REPOSITORY_ID),
                "--event-repository-id",
                str(REPOSITORY_ID),
                "--repository-fork",
                "false",
                "--workflow-ref",
                WORKFLOW_REF,
                "--workflow-sha",
                WORKFLOW_SHA,
                "--github-workflow-ref",
                GITHUB_WORKFLOW_REF,
                "--github-workflow-sha",
                GITHUB_WORKFLOW_SHA,
                "--required-runs",
                "10",
                "--output",
                str(output_path),
                "--summary",
                str(summary_path),
            ]

            passing = subprocess.run(command, check=False, capture_output=True, text=True)
            self.assertEqual(0, passing.returncode, passing.stderr)
            passing_evidence = json.loads(output_path.read_text())
            self.assertEqual(REF, passing_evidence["ref"])
            self.assertEqual(
                WORKFLOW_REF,
                passing_evidence["workflowProvenance"]["github.ref"],
            )
            self.assertIn("Resolved SHA", summary_path.read_text())

            runs_path.write_text("{}", encoding="utf-8")
            failing = subprocess.run(command, check=False, capture_output=True, text=True)
            self.assertEqual(1, failing.returncode)
            failed_evidence = json.loads(output_path.read_text())
            self.assertEqual("fail", failed_evidence["verdict"])
            self.assertIn("Input unusable", failed_evidence["reasons"][0])
            self.assertEqual(
                {
                    "start": {
                        "tagObjectId": TAG_OBJECT_ID,
                        "commitSha": SHA,
                        "taggerDate": timestamp(TAGGED_AT),
                    },
                    "end": {
                        "tagObjectId": TAG_OBJECT_ID,
                        "commitSha": SHA,
                        "taggerDate": timestamp(TAGGED_AT),
                    },
                },
                failed_evidence["tagBinding"],
            )


class CandidateSoakParityTest(unittest.TestCase):
    def test_mandatory_jobs_equal_every_dispatch_leaf_and_aggregate_gate(self) -> None:
        declared_job_names: set[str] = set()
        for workflow in AUDITOR.WORKFLOW_SPECS:
            path = ROOT / workflow.path
            document = yaml.safe_load(path.read_text(encoding="utf-8"))
            self.assertEqual(workflow.display_name, document["name"])
            expected = required_dispatch_job_names(document)
            self.assertEqual(expected, set(workflow.mandatory_jobs))
            declared_job_names.update(expected)

        self.assertEqual(declared_job_names, set(AUDITOR.MANDATORY_JOBS))

    def test_candidate_workflow_is_dispatch_only_read_only_and_pinned(self) -> None:
        text = CANDIDATE_WORKFLOW.read_text(encoding="utf-8")
        document = yaml.safe_load(text)
        triggers = document.get("on", document.get(True))

        self.assertEqual({"workflow_dispatch"}, set(triggers))
        self.assertEqual({"contents": "read", "actions": "read"}, document["permissions"])
        self.assertNotIn("secrets.", text)
        self.assertEqual(
            {"ref", "required_runs"}, set(triggers["workflow_dispatch"]["inputs"])
        )
        uses = [
            step["uses"]
            for job in document["jobs"].values()
            for step in job["steps"]
            if "uses" in step
        ]
        for value in uses:
            with self.subTest(uses=value):
                self.assertRegex(value, r"^[^/@]+/[^/@]+@[0-9a-f]{40}$")

    def test_candidate_workflow_collects_only_relevant_complete_page_sets(self) -> None:
        document = yaml.safe_load(CANDIDATE_WORKFLOW.read_text(encoding="utf-8"))
        steps = document["jobs"]["audit"]["steps"]
        by_name = {step.get("name"): step for step in steps}
        bind = by_name["Bind the canonical repository and candidate tag"]
        resolve = bind["run"]
        collect = by_name["Collect workflow run and job evidence"]["run"]
        reresolve = by_name["Re-resolve the candidate tag after collection"]["run"]
        audit_step = by_name["Audit the consecutive candidate runs"]
        summary = by_name["Publish the candidate soak step summary"]
        upload = by_name["Upload candidate soak evidence"]
        gate = by_name["Require the candidate soak gate to pass"]

        self.assertIn("candidate-soak-v", resolve)
        self.assertIn('REQUIRED_RUNS >= 10', resolve)
        self.assertIn('REPOSITORY_NAME" == "itkla/connex', resolve)
        self.assertIn('REPOSITORY_ID" == "1222010579', resolve)
        self.assertIn('REPOSITORY_FORK" == "false', resolve)
        self.assertIn("git/ref/tags/${encoded_ref}", resolve)
        self.assertIn('resolved_type" == "tag', resolve)
        self.assertIn("git/tags/${tag_object_id}", resolve)
        self.assertNotIn("/commits/", resolve)
        self.assertIn("git/ref/heads/main", resolve)
        self.assertIn("compare/main...${resolved_sha}", resolve)
        self.assertEqual(
            "${{ runner.temp }}/candidate-soak-main.json",
            bind["env"]["MAIN_FILE"],
        )
        self.assertIn("actions/runs", collect)
        self.assertNotIn("head_sha=", collect)
        self.assertGreaterEqual(collect.count('"repos/itkla/connex/actions/runs"'), 2)
        self.assertIn("--paginate --slurp", collect)
        for workflow_path in (
            ".github/workflows/ci.yml",
            ".github/workflows/security.yml",
            ".github/workflows/deploy-smoke.yml",
        ):
            self.assertIn(workflow_path, collect)
        self.assertIn(
            "{run_id: $run_id, run_attempt: $run_attempt, pages: $pages[0]}",
            collect,
        )
        self.assertIn("attempts/${run_attempt}/jobs?per_page=100", collect)
        self.assertIn("git/ref/tags/${encoded_ref}", reresolve)
        self.assertIn("git/tags/${end_tag_object_id}", reresolve)
        self.assertIs(True, audit_step["continue-on-error"])
        self.assertEqual(
            "${{ github.repository }}", audit_step["env"]["REPOSITORY_NAME"]
        )
        self.assertEqual(
            "${{ github.repository_id }}", audit_step["env"]["REPOSITORY_ID"]
        )
        self.assertEqual(
            "${{ github.event.repository.fork }}",
            audit_step["env"]["REPOSITORY_FORK"],
        )
        self.assertEqual("${{ github.ref }}", audit_step["env"]["WORKFLOW_REF"])
        self.assertEqual("${{ github.sha }}", audit_step["env"]["WORKFLOW_SHA"])
        self.assertEqual(
            "${{ github.workflow_ref }}",
            audit_step["env"]["WORKFLOW_FILE_REF"],
        )
        self.assertEqual(
            "${{ github.workflow_sha }}",
            audit_step["env"]["WORKFLOW_FILE_SHA"],
        )
        self.assertEqual(
            "${{ runner.temp }}/candidate-soak-main.json",
            audit_step["env"]["MAIN_FILE"],
        )
        for argument in (
            "--tag-json",
            "--main-json",
            "--repository",
            "--repository-id",
            "--event-repository-id",
            "--repository-fork",
            "--workflow-ref",
            "--workflow-sha",
            "--github-workflow-ref",
            "--github-workflow-sha",
        ):
            self.assertIn(argument, audit_step["run"])
        self.assertIn("$GITHUB_STEP_SUMMARY", summary["run"])
        self.assertEqual("always()", upload["if"])
        self.assertIn("candidate-soak-evidence.json", upload["with"]["path"])
        self.assertEqual("always()", gate["if"])
        self.assertIn("steps.soak.outcome", str(gate))

    def test_security_workflow_registers_candidate_soak_regression(self) -> None:
        document = yaml.safe_load((WORKFLOWS / "security.yml").read_text(encoding="utf-8"))
        commands = {step.get("run") for step in document["jobs"]["action-pins"]["steps"]}
        self.assertIn("python .github/scripts/test_candidate_soak.py", commands)

    def test_release_document_defines_the_proven_round_contract(self) -> None:
        text = RELEASE_DOCUMENT.read_text(encoding="utf-8")
        normalized = " ".join(text.split()).lower()
        for required_text in (
            "candidate-soak.yml",
            "six-hour window",
            "`ci` → `security` → `deploy smoke`",
            "head_branch",
            "total_count",
            "workflow_dispatch",
            "codeql alert-blocking",
            "current `main` head",
            "candidate-soak-evidence.json",
            "candidate-soak-vmajor.minor.patch-tc.n",
            "`github.ref`",
            "refs/heads/main",
            "`github.repository`",
            "`github.repository_id`",
            "1222010579",
            "`github.workflow_ref`",
            "`github.workflow_sha`",
            "tag object id",
            "tagger.date",
            "annotated tag",
            "compare/main...<resolved-candidate-sha>",
            "`mainbinding`",
            "referenced_workflows",
            "started_at",
            "completed_at",
            "updated_at",
            "compiled and dispatch-default minimum is `10`",
            "#1226",
            "#857",
        ):
            with self.subTest(required_text=required_text):
                self.assertIn(required_text, normalized)


if __name__ == "__main__":
    unittest.main()
