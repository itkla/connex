#!/usr/bin/env python3

from __future__ import annotations

import argparse
import subprocess
from pathlib import Path, PurePosixPath


CATEGORIES = (
    "backend",
    "frontend",
    "ocr",
    "migrations",
    "backup",
    "support_bundle",
    "compose",
    "profile_boot",
    "cross_stack",
    "action_pins",
    "frontend_audit",
    "ocr_audit",
    "full",
)

ROOT_METADATA = {
    ".editorconfig",
    ".gitattributes",
    ".gitignore",
    ".mailmap",
    "LICENSE",
    "LICENSE.txt",
}

DOCUMENTATION_BASENAMES = {
    "AGENTS.md",
    "CHANGELOG.md",
    "CLAUDE.md",
    "CODE_OF_CONDUCT.md",
    "CONTRIBUTING.md",
    "README.md",
    "README.mdx",
    "SECURITY.md",
}

FRONTEND_DEPENDENCY_FILES = {
    "frontend/.npmrc",
    "frontend/bun.lock",
    "frontend/bun.lockb",
    "frontend/npm-shrinkwrap.json",
    "frontend/package-lock.json",
    "frontend/package.json",
    "frontend/pnpm-lock.yaml",
    "frontend/pnpm-workspace.yaml",
    "frontend/yarn.lock",
}

OCR_DEPENDENCY_FILES = {
    "ocr/pyproject.toml",
    "ocr/poetry.lock",
    "ocr/uv.lock",
}


def empty_categories() -> dict[str, bool]:
    return {category: False for category in CATEGORIES}


def is_documentation(path: str) -> bool:
    pure_path = PurePosixPath(path)
    if path.startswith("docs/"):
        return True
    if len(pure_path.parts) == 1 and pure_path.suffix.lower() in {".md", ".mdx", ".rst"}:
        return True
    return pure_path.name in DOCUMENTATION_BASENAMES


def is_production_backend_path(path: str) -> bool:
    if path.startswith("backend/src/test/"):
        return False
    return path.startswith("backend/")


def is_ocr_dependency_path(path: str) -> bool:
    name = PurePosixPath(path).name
    return (
        path in OCR_DEPENDENCY_FILES
        or name == "requirements.txt"
        or name.endswith("requirements.lock")
        or name.endswith("requirements.txt")
    )


def force_full(categories: dict[str, bool]) -> None:
    for category in CATEGORIES:
        categories[category] = True


def classify_paths(paths: list[str], event_name: str = "pull_request") -> tuple[dict[str, bool], list[str]]:
    categories = empty_categories()
    reasons: list[str] = []

    if event_name != "pull_request":
        force_full(categories)
        reasons.append(f"{event_name} runs the complete trusted suite")
        return categories, reasons

    if not paths:
        force_full(categories)
        reasons.append("the changed-file set was empty or unavailable")
        return categories, reasons

    for raw_path in paths:
        path = raw_path.strip().replace("\\", "/")
        pure_path = PurePosixPath(path)

        if not path or pure_path.is_absolute() or ".." in pure_path.parts:
            force_full(categories)
            reasons.append(f"unsafe or invalid changed path: {raw_path!r}")
            continue

        if is_documentation(path) or path in ROOT_METADATA:
            continue

        if (
            path.startswith(".github/workflows/")
            or path.startswith(".github/actions/")
            or path.startswith(".github/scripts/")
            or path == ".github/dependabot.yml"
            or path == ".gitleaks.toml"
        ):
            force_full(categories)
            reasons.append(f"CI or security policy changed: {path}")
            continue

        if path.startswith("backend/"):
            categories["backend"] = True
            categories["cross_stack"] = True
            if is_production_backend_path(path):
                categories["profile_boot"] = True
            if path.startswith("backend/src/main/resources/db/migration/"):
                categories["migrations"] = True
            continue

        if path.startswith("frontend/"):
            categories["frontend"] = True
            categories["cross_stack"] = True
            if path in FRONTEND_DEPENDENCY_FILES:
                categories["frontend_audit"] = True
            continue

        if path.startswith("ocr/"):
            categories["ocr"] = True
            if is_ocr_dependency_path(path):
                categories["ocr_audit"] = True
            continue

        if path.startswith("deploy/backup/"):
            categories["backup"] = True
            continue

        if path.startswith("deploy/support-bundle/"):
            categories["support_bundle"] = True
            continue

        if path.startswith("deploy/"):
            categories["compose"] = True
            categories["profile_boot"] = True
            continue

        force_full(categories)
        reasons.append(f"unclassified path fails safe to the complete suite: {path}")

    return categories, reasons


def changed_paths(base_sha: str, head_sha: str) -> list[str]:
    if not base_sha or not head_sha:
        raise ValueError("both base and head SHAs are required for pull-request classification")

    completed = subprocess.run(
        [
            "git",
            "diff",
            "--name-only",
            "-z",
            "--no-renames",
            "--diff-filter=ACMRD",
            f"{base_sha}...{head_sha}",
        ],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return [path.decode("utf-8") for path in completed.stdout.split(b"\0") if path]


def write_outputs(output_path: Path, categories: dict[str, bool]) -> None:
    with output_path.open("a", encoding="utf-8") as output:
        for category in CATEGORIES:
            output.write(f"{category}={'true' if categories[category] else 'false'}\n")


def write_summary(
    summary_path: Path | None,
    event_name: str,
    base_sha: str,
    head_sha: str,
    paths: list[str],
    categories: dict[str, bool],
    reasons: list[str],
) -> None:
    if summary_path is None:
        return

    enabled = [category for category in CATEGORIES if categories[category] and category != "full"]
    mode = "complete" if categories["full"] else "selective"
    lines = [
        "### CI change classification",
        "",
        f"- Mode: **{mode}**",
        f"- Event: `{event_name}`",
    ]
    if base_sha:
        lines.append(f"- Base: `{base_sha}`")
    if head_sha:
        lines.append(f"- Head: `{head_sha}`")
    lines.append(f"- Selected categories: {', '.join(f'`{item}`' for item in enabled) if enabled else 'none'}")
    if reasons:
        lines.append("- Reasons:")
        lines.extend(f"  - {reason}" for reason in reasons)
    if paths:
        lines.append("- Changed paths:")
        lines.extend(f"  - `{path}`" for path in paths[:50])
        if len(paths) > 50:
            lines.append(f"  - … and {len(paths) - 50} more")
    lines.append("")

    with summary_path.open("a", encoding="utf-8") as summary:
        summary.write("\n".join(lines))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Classify Connex changes into CI impact categories")
    parser.add_argument("--event-name", required=True)
    parser.add_argument("--base-sha", default="")
    parser.add_argument("--head-sha", default="")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--summary", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    paths: list[str] = []
    reasons: list[str] = []

    if args.event_name == "pull_request":
        try:
            paths = changed_paths(args.base_sha, args.head_sha)
            categories, reasons = classify_paths(paths, args.event_name)
        except (OSError, subprocess.CalledProcessError, ValueError, UnicodeDecodeError) as error:
            categories = empty_categories()
            force_full(categories)
            reasons = [f"change detection failed closed: {error}"]
    else:
        categories, reasons = classify_paths(paths, args.event_name)

    write_outputs(args.output, categories)
    write_summary(
        args.summary,
        args.event_name,
        args.base_sha,
        args.head_sha,
        paths,
        categories,
        reasons,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
