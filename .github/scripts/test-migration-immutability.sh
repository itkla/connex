#!/usr/bin/env bash

set -euo pipefail

CHECKER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-migration-immutability.sh"
ROOT="$(mktemp -d)"
trap 'rm -rf "$ROOT"' EXIT

new_repository() {
  local name="$1"
  local repository="$ROOT/$name"
  mkdir -p "$repository/backend/src/main/resources/db/migration/tenant"
  git -C "$repository" init -q
  git -C "$repository" config user.name test
  git -C "$repository" config user.email test@example.test
  printf 'SELECT 1;\n' > "$repository/backend/src/main/resources/db/migration/tenant/V1__base.sql"
  git -C "$repository" add backend/src/main/resources/db/migration/tenant/V1__base.sql
  git -C "$repository" commit -qm base
  printf '%s\n' "$repository"
}

expect_failure() {
  local repository="$1"
  local base="$2"
  if (cd "$repository" && bash "$CHECKER" "$base" HEAD >/dev/null 2>&1); then
    echo "Expected migration guard failure in $repository" >&2
    exit 1
  fi
}

repository="$(new_repository addition)"
base="$(git -C "$repository" rev-parse HEAD)"
printf 'SELECT 2;\n' > "$repository/backend/src/main/resources/db/migration/tenant/V2__added.sql"
git -C "$repository" add backend/src/main/resources/db/migration/tenant/V2__added.sql
git -C "$repository" commit -qm addition
(cd "$repository" && bash "$CHECKER" "$base" HEAD >/dev/null)

repository="$(new_repository modification)"
base="$(git -C "$repository" rev-parse HEAD)"
printf 'SELECT 9;\n' > "$repository/backend/src/main/resources/db/migration/tenant/V1__base.sql"
git -C "$repository" add backend/src/main/resources/db/migration/tenant/V1__base.sql
git -C "$repository" commit -qm modification
expect_failure "$repository" "$base"

repository="$(new_repository deletion)"
base="$(git -C "$repository" rev-parse HEAD)"
git -C "$repository" rm -q backend/src/main/resources/db/migration/tenant/V1__base.sql
git -C "$repository" commit -qm deletion
expect_failure "$repository" "$base"

repository="$(new_repository rename)"
base="$(git -C "$repository" rev-parse HEAD)"
git -C "$repository" mv \
  backend/src/main/resources/db/migration/tenant/V1__base.sql \
  backend/src/main/resources/db/migration/tenant/V1__renamed.sql
git -C "$repository" commit -qm rename
expect_failure "$repository" "$base"

repository="$(new_repository type-change)"
base="$(git -C "$repository" rev-parse HEAD)"
rm "$repository/backend/src/main/resources/db/migration/tenant/V1__base.sql"
ln -s /dev/null "$repository/backend/src/main/resources/db/migration/tenant/V1__base.sql"
git -C "$repository" add backend/src/main/resources/db/migration/tenant/V1__base.sql
git -C "$repository" commit -qm type-change
expect_failure "$repository" "$base"
