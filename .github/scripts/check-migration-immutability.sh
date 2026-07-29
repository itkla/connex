#!/usr/bin/env bash

set -euo pipefail

BASE_SHA="${1:?base commit is required}"
HEAD_SHA="${2:-HEAD}"
MIGRATION_ROOT="backend/src/main/resources/db/migration"
IDENTITY_OLD_PATHS=(
  "$MIGRATION_ROOT/tenant/V123__canonical_identity.sql"
  "$MIGRATION_ROOT/tenant/V124__identity_collision_report.sql"
)
IDENTITY_NEW_PATHS=(
  "$MIGRATION_ROOT/tenant/V127__canonical_identity.sql"
  "$MIGRATION_ROOT/tenant/V128__identity_collision_report.sql"
)
IDENTITY_BLOBS=(
  "51be94e5a154407d93e83cefd2a7d95196773b86"
  "0c8793885b2f3e4c6459187558192e310881c51b"
)
LINEAGE_ANCHOR_PATH="$MIGRATION_ROOT/control/V126__tenant_teardown_control_guards.sql"
LINEAGE_ANCHOR_BLOB="7339e596e4adadf910541f0d5a3e76fb166bdad9"

if [[ ! "$BASE_SHA" =~ ^[0-9a-f]{40}$ ]] \
    || [ "$BASE_SHA" = "0000000000000000000000000000000000000000" ]; then
  echo "A valid base commit is required for migration immutability checks." >&2
  exit 1
fi
git cat-file -e "${BASE_SHA}^{commit}"
git cat-file -e "${HEAD_SHA}^{commit}"
test -n "$(git merge-base "$BASE_SHA" "$HEAD_SHA")"

tree_entry() {
  git ls-tree "$1" -- "$2"
}

identity_relocation_is_exact() {
  local index
  local old_path
  local new_path
  local expected_blob

  if [ "$(tree_entry "$BASE_SHA" "$LINEAGE_ANCHOR_PATH")" \
      != "100644 blob $LINEAGE_ANCHOR_BLOB	$LINEAGE_ANCHOR_PATH" ] \
      || [ "$(tree_entry "$HEAD_SHA" "$LINEAGE_ANCHOR_PATH")" \
      != "100644 blob $LINEAGE_ANCHOR_BLOB	$LINEAGE_ANCHOR_PATH" ]; then
    return 1
  fi
  for index in "${!IDENTITY_OLD_PATHS[@]}"; do
    old_path="${IDENTITY_OLD_PATHS[$index]}"
    new_path="${IDENTITY_NEW_PATHS[$index]}"
    expected_blob="${IDENTITY_BLOBS[$index]}"
    if [ "$(tree_entry "$BASE_SHA" "$old_path")" \
        != "100644 blob $expected_blob	$old_path" ] \
        || [ -n "$(tree_entry "$HEAD_SHA" "$old_path")" ] \
        || [ -n "$(tree_entry "$BASE_SHA" "$new_path")" ] \
        || [ "$(tree_entry "$HEAD_SHA" "$new_path")" \
        != "100644 blob $expected_blob	$new_path" ]; then
      return 1
    fi
  done
}

identity_relocation=false
if identity_relocation_is_exact; then
  identity_relocation=true
fi

is_identity_relocation_source() {
  local path_name="$1"
  local old_path

  if [ "$identity_relocation" != true ]; then
    return 1
  fi
  for old_path in "${IDENTITY_OLD_PATHS[@]}"; do
    if [ "$path_name" = "$old_path" ]; then
      return 0
    fi
  done
  return 1
}

changed=()
while IFS= read -r path_name; do
  base_entry="$(tree_entry "$BASE_SHA" "$path_name")"
  head_entry="$(tree_entry "$HEAD_SHA" "$path_name")"
  if [ "$base_entry" != "$head_entry" ] \
      && ! is_identity_relocation_source "$path_name"; then
    changed+=("$path_name")
  fi
done < <(git ls-tree -r --name-only "$BASE_SHA" -- "$MIGRATION_ROOT")

if [ "${#changed[@]}" -gt 0 ]; then
  echo "Shipped Flyway migrations are immutable. Modified, deleted, renamed, or type-changed:" >&2
  printf '%s\n' "${changed[@]}" >&2
  echo "Forward-only: add a new migration instead of editing an applied one." >&2
  exit 1
fi

base_max=-1
invalid_base_names=()
while IFS= read -r path_name; do
  file_name="${path_name##*/}"
  if [[ "$file_name" =~ ^V([0-9]+)__[a-z0-9_]+\.sql$ ]]; then
    version=$((10#${BASH_REMATCH[1]}))
    if [ "$version" -gt "$base_max" ]; then
      base_max="$version"
    fi
  elif [[ "$file_name" = *.sql ]]; then
    invalid_base_names+=("$path_name")
  fi
done < <(git ls-tree -r --name-only "$BASE_SHA" -- "$MIGRATION_ROOT")

if [ "${#invalid_base_names[@]}" -gt 0 ]; then
  echo "Base revision contains SQL migrations outside the required V{integer}__{snake_case}.sql convention:" >&2
  printf '%s\n' "${invalid_base_names[@]}" >&2
  exit 1
fi

invalid_added_names=()
non_monotonic_additions=()
duplicate_versions=()
declare -A head_version_paths=()
while IFS= read -r path_name; do
  file_name="${path_name##*/}"
  if [[ "$file_name" =~ ^V([0-9]+)__[a-z0-9_]+\.sql$ ]]; then
    version=$((10#${BASH_REMATCH[1]}))
    if [ -n "${head_version_paths[$version]:-}" ]; then
      duplicate_versions+=("${head_version_paths[$version]}" "$path_name")
    else
      head_version_paths[$version]="$path_name"
    fi
    if [ -z "$(tree_entry "$BASE_SHA" "$path_name")" ] \
        && [ "$version" -le "$base_max" ]; then
      non_monotonic_additions+=("$path_name")
    fi
  elif [[ "$file_name" = *.sql ]]; then
    invalid_added_names+=("$path_name")
  fi
done < <(git ls-tree -r --name-only "$HEAD_SHA" -- "$MIGRATION_ROOT")

if [ "${#invalid_added_names[@]}" -gt 0 ]; then
  echo "SQL migrations must use V{integer}__{snake_case}.sql names:" >&2
  printf '%s\n' "${invalid_added_names[@]}" >&2
  exit 1
fi

if [ "${#duplicate_versions[@]}" -gt 0 ]; then
  echo "Flyway migration versions must be unique across every migration folder:" >&2
  printf '%s\n' "${duplicate_versions[@]}" | sort -u >&2
  exit 1
fi

if [ "${#non_monotonic_additions[@]}" -gt 0 ]; then
  echo "New Flyway migrations must be greater than base revision maximum V${base_max}:" >&2
  printf '%s\n' "${non_monotonic_additions[@]}" >&2
  exit 1
fi

if [ "$identity_relocation" = true ]; then
  echo "Recognized the exact byte-identical V123/V124 to V127/V128 lineage correction."
  echo "All other shipped migrations are unchanged."
else
  echo "No shipped migrations modified, deleted, renamed, or type-changed."
fi
echo "All new Flyway migrations are greater than base revision maximum V${base_max}."
