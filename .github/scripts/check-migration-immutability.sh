#!/usr/bin/env bash

set -euo pipefail

BASE_SHA="${1:?base commit is required}"
HEAD_SHA="${2:-HEAD}"

if [[ ! "$BASE_SHA" =~ ^[0-9a-f]{40}$ ]] \
    || [ "$BASE_SHA" = "0000000000000000000000000000000000000000" ]; then
  echo "A valid base commit is required for migration immutability checks." >&2
  exit 1
fi
git cat-file -e "${BASE_SHA}^{commit}"
git cat-file -e "${HEAD_SHA}^{commit}"
test -n "$(git merge-base "$BASE_SHA" "$HEAD_SHA")"

changed=()
while IFS= read -r path_name; do
  base_entry="$(git ls-tree "$BASE_SHA" -- "$path_name")"
  head_entry="$(git ls-tree "$HEAD_SHA" -- "$path_name")"
  if [ "$base_entry" != "$head_entry" ]; then
    changed+=("$path_name")
  fi
done < <(git ls-tree -r --name-only "$BASE_SHA" -- 'backend/src/main/resources/db/migration')

if [ "${#changed[@]}" -gt 0 ]; then
  echo "Shipped Flyway migrations are immutable. Modified, deleted, renamed, or type-changed:" >&2
  printf '%s\n' "${changed[@]}" >&2
  echo "Forward-only: add a new migration instead of editing an applied one." >&2
  exit 1
fi

echo "No shipped migrations modified, deleted, renamed, or type-changed."
