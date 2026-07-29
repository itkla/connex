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
printf 'SELECT 2;\n' > "$repository/backend/src/main/resources/db/migration/tenant/R__refreshed.sql"
git -C "$repository" add backend/src/main/resources/db/migration/tenant
git -C "$repository" commit -qm addition
(cd "$repository" && bash "$CHECKER" "$base" HEAD >/dev/null)

repository="$(new_repository backdated-addition)"
base="$(git -C "$repository" rev-parse HEAD)"
printf 'SELECT 0;\n' > "$repository/backend/src/main/resources/db/migration/tenant/V0__backdated.sql"
git -C "$repository" add backend/src/main/resources/db/migration/tenant/V0__backdated.sql
git -C "$repository" commit -qm backdated-addition
expect_failure "$repository" "$base"

repository="$(new_repository duplicate-version-addition)"
base="$(git -C "$repository" rev-parse HEAD)"
printf 'SELECT 2;\n' > "$repository/backend/src/main/resources/db/migration/tenant/V1__duplicate.sql"
git -C "$repository" add backend/src/main/resources/db/migration/tenant/V1__duplicate.sql
git -C "$repository" commit -qm duplicate-version-addition
expect_failure "$repository" "$base"

repository="$(new_repository moved-backdated-addition)"
printf 'SELECT 0;\n' > "$repository/backdated.sql"
git -C "$repository" add backdated.sql
git -C "$repository" commit -qm outside-migration
base="$(git -C "$repository" rev-parse HEAD)"
git -C "$repository" mv backdated.sql backend/src/main/resources/db/migration/tenant/V0__backdated.sql
git -C "$repository" commit -qm moved-backdated-addition
expect_failure "$repository" "$base"

repository="$(new_repository flyway-version-aliases)"
base="$(git -C "$repository" rev-parse HEAD)"
printf 'SELECT 0;\n' > "$repository/backend/src/main/resources/db/migration/tenant/V0.1__dotted.sql"
printf 'SELECT 0;\n' > "$repository/backend/src/main/resources/db/migration/tenant/V0_1__underscored.sql"
git -C "$repository" add backend/src/main/resources/db/migration/tenant
git -C "$repository" commit -qm flyway-version-aliases
expect_failure "$repository" "$base"

repository="$(new_repository cross-lineage-backdating)"
mkdir -p "$repository/backend/src/main/resources/db/migration/control"
printf 'SELECT 126;\n' > "$repository/backend/src/main/resources/db/migration/control/V126__control.sql"
git -C "$repository" add backend/src/main/resources/db/migration/control/V126__control.sql
git -C "$repository" commit -qm control-lineage
base="$(git -C "$repository" rev-parse HEAD)"
printf 'SELECT 123;\n' > "$repository/backend/src/main/resources/db/migration/tenant/V123__tenant.sql"
printf 'SELECT 124;\n' > "$repository/backend/src/main/resources/db/migration/tenant/V124__tenant.sql"
git -C "$repository" add backend/src/main/resources/db/migration/tenant
git -C "$repository" commit -qm cross-lineage-backdating
expect_failure "$repository" "$base"

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
