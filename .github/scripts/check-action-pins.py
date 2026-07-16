#!/usr/bin/env python3

import re
import sys
from pathlib import Path

import yaml


FULL_COMMIT = re.compile(r"[^/@\s]+(?:/[^/@\s]+)+@[0-9a-f]{40}")


def yaml_sources(root: Path) -> list[Path]:
    workflows = [
        path
        for suffix in ("*.yml", "*.yaml")
        for path in (root / ".github" / "workflows").glob(suffix)
    ]
    actions = [
        path
        for name in ("action.yml", "action.yaml")
        for path in (root / ".github" / "actions").rglob(name)
    ]
    return sorted(set(workflows + actions))


def uses_values(value: object, path: tuple[object, ...] = ()) -> list[tuple[tuple[object, ...], object]]:
    found: list[tuple[tuple[object, ...], object]] = []
    if isinstance(value, dict):
        for key, child in value.items():
            child_path = (*path, key)
            if key == "uses":
                found.append((child_path, child))
            found.extend(uses_values(child, child_path))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            found.extend(uses_values(child, (*path, index)))
    return found


def local_source(root: Path, value: str) -> tuple[Path | None, str | None]:
    candidate = (root / value).resolve()
    try:
        candidate.relative_to(root)
    except ValueError:
        return None, "local reference resolves outside the repository"
    if not candidate.exists():
        return None, "local reference does not exist"
    if candidate.is_dir():
        descriptors = [
            descriptor.resolve()
            for name in ("action.yml", "action.yaml")
            if (descriptor := candidate / name).is_file()
        ]
        if len(descriptors) != 1:
            return None, "local action must contain exactly one action.yml or action.yaml"
        try:
            descriptors[0].relative_to(root)
        except ValueError:
            return None, "local action descriptor resolves outside the repository"
        return descriptors[0], None
    if candidate.is_file() and candidate.suffix in {".yml", ".yaml"}:
        return candidate, None
    return None, "local reference must target an action directory or YAML workflow"


def invalid_pins(root: Path) -> list[str]:
    root = root.resolve()
    invalid: list[str] = []
    pending = yaml_sources(root)
    if not pending:
        return ["No workflow or local-action YAML files were found"]
    visited: set[Path] = set()
    while pending:
        source = pending.pop(0)
        try:
            resolved_source = source.resolve(strict=True)
            relative = resolved_source.relative_to(root)
        except (OSError, ValueError):
            invalid.append(f"{source}: source resolves outside the repository or does not exist")
            continue
        if resolved_source in visited:
            continue
        visited.add(resolved_source)
        documents = list(yaml.safe_load_all(resolved_source.read_text(encoding="utf-8")))
        for document_index, document in enumerate(documents):
            for path, value in uses_values(document):
                location = ".".join(str(part) for part in path)
                if not isinstance(value, str):
                    invalid.append(f"{relative}:document {document_index + 1}:{location}: uses must be a string")
                elif value.startswith("./"):
                    target, error = local_source(root, value)
                    if error is not None:
                        invalid.append(f"{relative}:document {document_index + 1}:{location}: {value}: {error}")
                    elif target is not None and target not in visited:
                        pending.append(target)
                elif FULL_COMMIT.fullmatch(value) is None:
                    invalid.append(f"{relative}:document {document_index + 1}:{location}: {value}")
    return invalid


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    invalid = invalid_pins(root)
    if invalid:
        print("Action references must resolve locally or use full commit SHAs:", file=sys.stderr)
        print("\n".join(invalid), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
