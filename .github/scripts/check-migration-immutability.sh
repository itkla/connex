#!/usr/bin/env bash

set -euo pipefail

BASE_SHA="${1:?base commit is required}"
HEAD_SHA="${2:-HEAD}"
MIGRATION_ROOT="backend/src/main/resources/db/migration"

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
done < <(git ls-tree -r --name-only "$BASE_SHA" -- "$MIGRATION_ROOT")

if [ "${#changed[@]}" -gt 0 ]; then
  echo "Shipped Flyway migrations are immutable. Modified, deleted, renamed, or type-changed:" >&2
  printf '%s\n' "${changed[@]}" >&2
  echo "Forward-only: add a new migration instead of editing an applied one." >&2
  exit 1
fi

highest_base_version=-1
while IFS= read -r path_name; do
  file_name="${path_name##*/}"
  if [[ "$file_name" =~ ^V([0-9]+)__.*\.sql$ ]]; then
    version=$((10#${BASH_REMATCH[1]}))
    if [ "$version" -gt "$highest_base_version" ]; then
      highest_base_version="$version"
    fi
  fi
done < <(git ls-tree -r --name-only "$BASE_SHA" -- "$MIGRATION_ROOT")

backdated=()
invalid_names=()
while IFS= read -r path_name; do
  file_name="${path_name##*/}"
  if [[ "$file_name" =~ ^V([0-9]+)__.+\.sql$ ]]; then
    version=$((10#${BASH_REMATCH[1]}))
    if [ "$version" -le "$highest_base_version" ]; then
      backdated+=("$path_name")
    fi
  elif [[ "$file_name" = V*.sql ]]; then
    invalid_names+=("$path_name")
  fi
done < <(git diff --no-renames --name-only --diff-filter=A "$BASE_SHA" "$HEAD_SHA" -- "$MIGRATION_ROOT")

if [ "${#invalid_names[@]}" -gt 0 ]; then
  echo "New Flyway migrations must use the integer V<number>__description.sql naming convention:" >&2
  printf '%s\n' "${invalid_names[@]}" >&2
  exit 1
fi

if [ "${#backdated[@]}" -gt 0 ]; then
  echo "New Flyway migrations must use a version above the base branch maximum V${highest_base_version}:" >&2
  printf '%s\n' "${backdated[@]}" >&2
  echo "Choose the next available version after updating from the base branch." >&2
  exit 1
fi

echo "No shipped migrations modified, deleted, renamed, or type-changed."
echo "No new migrations backdate the base branch lineage."
