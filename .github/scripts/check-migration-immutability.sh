#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

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
if [[ "$HEAD_SHA" = -* ]]; then
  echo "The head revision must not begin with an option prefix." >&2
  exit 1
fi
git cat-file -e "${HEAD_SHA}^{commit}"
test -n "$(git merge-base "$BASE_SHA" "$HEAD_SHA")"

tree_entry() {
  git ls-tree "$1" -- ":(literal)$2"
}

versioned_migration_version() {
  local file_name="$1"

  if [[ "${file_name}/" =~ ^V([0-9]+)__[a-z0-9_]+\.sql/$ ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
    return 0
  fi
  return 1
}

repeatable_migration_name_is_valid() {
  local file_name="$1"

  [[ "${file_name}/" =~ ^R__[a-z0-9_]+\.sql/$ ]]
}

tree_entry_is_regular_file() {
  local revision="$1"
  local path_name="$2"

  [[ "$(tree_entry "$revision" "$path_name")" = "100644 blob "* ]]
}

normalize_version() {
  local version="$1"

  while [ "${#version}" -gt 1 ] && [ "${version:0:1}" = "0" ]; do
    version="${version:1}"
  done
  printf '%s\n' "$version"
}

version_is_greater() {
  local candidate="$1"
  local reference="$2"
  local index
  local candidate_digit
  local reference_digit

  if [ "${#candidate}" -gt "${#reference}" ]; then
    return 0
  fi
  if [ "${#candidate}" -lt "${#reference}" ]; then
    return 1
  fi
  for ((index = 0; index < ${#candidate}; index++)); do
    candidate_digit="${candidate:index:1}"
    reference_digit="${reference:index:1}"
    if [ "$candidate_digit" -gt "$reference_digit" ]; then
      return 0
    fi
    if [ "$candidate_digit" -lt "$reference_digit" ]; then
      return 1
    fi
  done
  return 1
}

print_paths() {
  local path_name

  for path_name in "$@"; do
    printf '  %q\n' "$path_name"
  done
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
while IFS= read -r -d '' path_name; do
  base_entry="$(tree_entry "$BASE_SHA" "$path_name")"
  head_entry="$(tree_entry "$HEAD_SHA" "$path_name")"
  if [ "$base_entry" != "$head_entry" ] \
      && ! is_identity_relocation_source "$path_name"; then
    changed+=("$path_name")
  fi
done < <(git ls-tree -r -z --name-only "$BASE_SHA" -- "$MIGRATION_ROOT")

if [ "${#changed[@]}" -gt 0 ]; then
  echo "Shipped Flyway migrations are immutable. Modified, deleted, renamed, or type-changed:" >&2
  print_paths "${changed[@]}" >&2
  echo "Forward-only: add a new migration instead of editing an applied one." >&2
  exit 1
fi

base_max="0"
invalid_base_names=()
while IFS= read -r -d '' path_name; do
  file_name="${path_name##*/}"
  if version_digits="$(versioned_migration_version "$file_name")"; then
    version="$(normalize_version "$version_digits")"
    if version_is_greater "$version" "$base_max"; then
      base_max="$version"
    fi
  elif ! repeatable_migration_name_is_valid "$file_name"; then
    invalid_base_names+=("$path_name")
  fi
  if ! tree_entry_is_regular_file "$BASE_SHA" "$path_name"; then
    invalid_base_names+=("$path_name")
  fi
done < <(git ls-tree -r -z --name-only "$BASE_SHA" -- "$MIGRATION_ROOT")

if [ "${#invalid_base_names[@]}" -gt 0 ]; then
  echo "Base revision migration files must be regular files named V{integer}__{snake_case}.sql or R__{snake_case}.sql:" >&2
  print_paths "${invalid_base_names[@]}" | sort -u >&2
  exit 1
fi

invalid_added_names=()
non_monotonic_additions=()
duplicate_versions=()
declare -A head_version_paths=()
while IFS= read -r -d '' path_name; do
  file_name="${path_name##*/}"
  if version_digits="$(versioned_migration_version "$file_name")"; then
    version="$(normalize_version "$version_digits")"
    if [ -n "${head_version_paths[$version]:-}" ]; then
      duplicate_versions+=("${head_version_paths[$version]}" "$path_name")
    else
      head_version_paths[$version]="$path_name"
    fi
    if [ -z "$(tree_entry "$BASE_SHA" "$path_name")" ] \
        && ! version_is_greater "$version" "$base_max"; then
      non_monotonic_additions+=("$path_name")
    fi
  elif ! repeatable_migration_name_is_valid "$file_name"; then
    invalid_added_names+=("$path_name")
  fi
  if ! tree_entry_is_regular_file "$HEAD_SHA" "$path_name"; then
    invalid_added_names+=("$path_name")
  fi
done < <(git ls-tree -r -z --name-only "$HEAD_SHA" -- "$MIGRATION_ROOT")

if [ "${#invalid_added_names[@]}" -gt 0 ]; then
  echo "Migration files must be regular files named V{integer}__{snake_case}.sql or R__{snake_case}.sql:" >&2
  print_paths "${invalid_added_names[@]}" | sort -u >&2
  exit 1
fi

if [ "${#duplicate_versions[@]}" -gt 0 ]; then
  echo "Flyway migration versions must be unique across every migration folder:" >&2
  print_paths "${duplicate_versions[@]}" | sort -u >&2
  exit 1
fi

if [ "${#non_monotonic_additions[@]}" -gt 0 ]; then
  echo "New Flyway migrations must be greater than base revision maximum V${base_max}:" >&2
  print_paths "${non_monotonic_additions[@]}" >&2
  exit 1
fi

if [ "$identity_relocation" = true ]; then
  echo "Recognized the exact byte-identical V123/V124 to V127/V128 lineage correction."
  echo "All other shipped migrations are unchanged."
else
  echo "No shipped migrations modified, deleted, renamed, or type-changed."
fi
echo "All new Flyway migrations are greater than base revision maximum V${base_max}."
