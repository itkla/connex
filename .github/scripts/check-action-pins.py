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


def invalid_pins(root: Path) -> list[str]:
    invalid: list[str] = []
    sources = yaml_sources(root)
    if not sources:
        return ["No workflow or local-action YAML files were found"]
    for source in sources:
        relative = source.relative_to(root)
        documents = list(yaml.safe_load_all(source.read_text(encoding="utf-8")))
        for document_index, document in enumerate(documents):
            for path, value in uses_values(document):
                location = ".".join(str(part) for part in path)
                if not isinstance(value, str):
                    invalid.append(f"{relative}:document {document_index + 1}:{location}: uses must be a string")
                elif value.startswith("./"):
                    continue
                elif FULL_COMMIT.fullmatch(value) is None:
                    invalid.append(f"{relative}:document {document_index + 1}:{location}: {value}")
    return invalid


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    invalid = invalid_pins(root)
    if invalid:
        print("External actions must use full commit SHAs:", file=sys.stderr)
        print("\n".join(invalid), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
