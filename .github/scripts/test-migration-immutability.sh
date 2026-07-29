#!/usr/bin/env bash

set -euo pipefail

CHECKER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-migration-immutability.sh"
SOURCE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROOT="$(mktemp -d)"
trap 'rm -rf "$ROOT"' EXIT

new_repository() {
  local name="$1"
  local plane="${2:-tenant}"
  local version="${3:-1}"
  local repository="$ROOT/$name"
  local migration_root="$repository/backend/src/main/resources/db/migration"
  mkdir -p "$migration_root/control" "$migration_root/tenant"
  git -C "$repository" init -q
  git -C "$repository" config user.name test
  git -C "$repository" config user.email test@example.test
  : > "$migration_root/control/.gitkeep"
  : > "$migration_root/tenant/.gitkeep"
  printf 'SELECT 1;\n' > "$migration_root/$plane/V${version}__base.sql"
  git -C "$repository" add backend/src/main/resources/db/migration
  git -C "$repository" commit -qm base
  printf '%s\n' "$repository"
}

new_identity_relocation_repository() {
  local name="$1"
  local repository="$ROOT/$name"
  local migration_root="$repository/backend/src/main/resources/db/migration/tenant"
  local control_root="$repository/backend/src/main/resources/db/migration/control"
  mkdir -p "$migration_root" "$control_root"
  git -C "$repository" init -q
  git -C "$repository" config user.name test
  git -C "$repository" config user.email test@example.test
  : > "$control_root/.gitkeep"
  : > "$migration_root/.gitkeep"
  cp \
    "$SOURCE_ROOT/backend/src/main/resources/db/migration/tenant/V127__canonical_identity.sql" \
    "$migration_root/V123__canonical_identity.sql"
  cp \
    "$SOURCE_ROOT/backend/src/main/resources/db/migration/tenant/V128__identity_collision_report.sql" \
    "$migration_root/V124__identity_collision_report.sql"
  cp \
    "$SOURCE_ROOT/backend/src/main/resources/db/migration/control/V126__tenant_teardown_control_guards.sql" \
    "$control_root/V126__tenant_teardown_control_guards.sql"
  git -C "$repository" add backend/src/main/resources/db/migration
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

repository="$(new_repository lower-cross-plane control 126)"
base="$(git -C "$repository" rev-parse HEAD)"
printf 'SELECT 123;\n' \
  > "$repository/backend/src/main/resources/db/migration/tenant/V123__late.sql"
git -C "$repository" add backend/src/main/resources/db/migration/tenant/V123__late.sql
git -C "$repository" commit -qm lower-cross-plane
expect_failure "$repository" "$base"

repository="$(new_repository equal-version control 126)"
base="$(git -C "$repository" rev-parse HEAD)"
printf 'SELECT 126;\n' \
  > "$repository/backend/src/main/resources/db/migration/tenant/V126__duplicate.sql"
git -C "$repository" add backend/src/main/resources/db/migration/tenant/V126__duplicate.sql
git -C "$repository" commit -qm equal-version
expect_failure "$repository" "$base"

repository="$(new_repository sequential-higher control 126)"
base="$(git -C "$repository" rev-parse HEAD)"
printf 'SELECT 127;\n' \
  > "$repository/backend/src/main/resources/db/migration/tenant/V127__first.sql"
printf 'SELECT 128;\n' \
  > "$repository/backend/src/main/resources/db/migration/control/V128__second.sql"
git -C "$repository" add \
  backend/src/main/resources/db/migration/tenant/V127__first.sql \
  backend/src/main/resources/db/migration/control/V128__second.sql
git -C "$repository" commit -qm sequential-higher
(cd "$repository" && bash "$CHECKER" "$base" HEAD >/dev/null)

repository="$(new_repository lower-reverse-plane tenant 200)"
base="$(git -C "$repository" rev-parse HEAD)"
printf 'SELECT 199;\n' \
  > "$repository/backend/src/main/resources/db/migration/control/V199__late.sql"
git -C "$repository" add backend/src/main/resources/db/migration/control/V199__late.sql
git -C "$repository" commit -qm lower-reverse-plane
expect_failure "$repository" "$base"

repository="$(new_repository duplicate-additions control 126)"
base="$(git -C "$repository" rev-parse HEAD)"
printf 'SELECT 127;\n' \
  > "$repository/backend/src/main/resources/db/migration/control/V127__first.sql"
printf 'SELECT 127;\n' \
  > "$repository/backend/src/main/resources/db/migration/tenant/V127__second.sql"
git -C "$repository" add \
  backend/src/main/resources/db/migration/control/V127__first.sql \
  backend/src/main/resources/db/migration/tenant/V127__second.sql
git -C "$repository" commit -qm duplicate-additions
expect_failure "$repository" "$base"

repository="$(new_repository invalid-version-name control 126)"
base="$(git -C "$repository" rev-parse HEAD)"
printf 'SELECT 127;\n' \
  > "$repository/backend/src/main/resources/db/migration/tenant/V127.1__invalid.sql"
git -C "$repository" add backend/src/main/resources/db/migration/tenant/V127.1__invalid.sql
git -C "$repository" commit -qm invalid-version-name
expect_failure "$repository" "$base"

repository="$(new_repository trailing-newline-name control 126)"
base="$(git -C "$repository" rev-parse HEAD)"
relative_path=$'backend/src/main/resources/db/migration/tenant/V127__trailing.sql\n'
printf 'SELECT 127;\n' > "$repository/$relative_path"
git -C "$repository" add -- "$relative_path"
git -C "$repository" commit -qm trailing-newline-name
expect_failure "$repository" "$base"

repository="$(new_repository inert-migration-names control 126)"
base="$(git -C "$repository" rev-parse HEAD)"
printf 'SELECT 127;\n' \
  > "$repository/backend/src/main/resources/db/migration/tenant/V127__uppercase.SQL"
printf 'SELECT 128;\n' \
  > "$repository/backend/src/main/resources/db/migration/tenant/V128__backup.sql.bak"
git -C "$repository" add backend/src/main/resources/db/migration/tenant
git -C "$repository" commit -qm inert-migration-names
expect_failure "$repository" "$base"

repository="$(new_repository symbolic-link-migration control 126)"
base="$(git -C "$repository" rev-parse HEAD)"
ln -s /dev/null \
  "$repository/backend/src/main/resources/db/migration/tenant/V127__symbolic_link.sql"
git -C "$repository" add \
  backend/src/main/resources/db/migration/tenant/V127__symbolic_link.sql
git -C "$repository" commit -qm symbolic-link-migration
expect_failure "$repository" "$base"

repository="$(new_repository unauthorized-metadata control 126)"
base="$(git -C "$repository" rev-parse HEAD)"
mkdir -p "$repository/backend/src/main/resources/db/migration/extra"
: > "$repository/backend/src/main/resources/db/migration/extra/.gitkeep"
git -C "$repository" add backend/src/main/resources/db/migration/extra/.gitkeep
git -C "$repository" commit -qm unauthorized-metadata
expect_failure "$repository" "$base"

repository="$(new_repository modified-metadata control 126)"
base="$(git -C "$repository" rev-parse HEAD)"
printf 'not empty\n' \
  > "$repository/backend/src/main/resources/db/migration/control/.gitkeep"
git -C "$repository" add backend/src/main/resources/db/migration/control/.gitkeep
git -C "$repository" commit -qm modified-metadata
expect_failure "$repository" "$base"

repository="$(new_repository control-character-name control 126)"
base="$(git -C "$repository" rev-parse HEAD)"
relative_path=$'backend/src/main/resources/db/migration/tenant/V100__evil\n.sql'
printf 'SELECT 100;\n' > "$repository/$relative_path"
git -C "$repository" add -- "$relative_path"
git -C "$repository" commit -qm control-character-name
expect_failure "$repository" "$base"

repository="$(new_repository non-ascii-name control 126)"
base="$(git -C "$repository" rev-parse HEAD)"
relative_path=$'backend/src/main/resources/db/migration/tenant/V100__caf\xc3\xa9.sql'
printf 'SELECT 100;\n' > "$repository/$relative_path"
git -C "$repository" add -- "$relative_path"
git -C "$repository" commit -qm non-ascii-name
expect_failure "$repository" "$base"

repository="$(new_repository leading-zero-duplicate control 126)"
base="$(git -C "$repository" rev-parse HEAD)"
printf 'SELECT 127;\n' \
  > "$repository/backend/src/main/resources/db/migration/control/V127__first.sql"
printf 'SELECT 127;\n' \
  > "$repository/backend/src/main/resources/db/migration/tenant/V0127__second.sql"
git -C "$repository" add \
  backend/src/main/resources/db/migration/control/V127__first.sql \
  backend/src/main/resources/db/migration/tenant/V0127__second.sql
git -C "$repository" commit -qm leading-zero-duplicate
expect_failure "$repository" "$base"

repository="$(new_repository large-version control 128)"
base="$(git -C "$repository" rev-parse HEAD)"
printf 'SELECT 18446744073709551743;\n' \
  > "$repository/backend/src/main/resources/db/migration/tenant/V18446744073709551743__future.sql"
git -C "$repository" add \
  backend/src/main/resources/db/migration/tenant/V18446744073709551743__future.sql
git -C "$repository" commit -qm large-version
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

repository="$(new_identity_relocation_repository exact-identity-relocation)"
base="$(git -C "$repository" rev-parse HEAD)"
git -C "$repository" mv \
  backend/src/main/resources/db/migration/tenant/V123__canonical_identity.sql \
  backend/src/main/resources/db/migration/tenant/V127__canonical_identity.sql
git -C "$repository" mv \
  backend/src/main/resources/db/migration/tenant/V124__identity_collision_report.sql \
  backend/src/main/resources/db/migration/tenant/V128__identity_collision_report.sql
git -C "$repository" commit -qm exact-identity-relocation
(cd "$repository" && bash "$CHECKER" "$base" HEAD >/dev/null)

repository="$(new_identity_relocation_repository partial-identity-relocation)"
base="$(git -C "$repository" rev-parse HEAD)"
git -C "$repository" mv \
  backend/src/main/resources/db/migration/tenant/V123__canonical_identity.sql \
  backend/src/main/resources/db/migration/tenant/V127__canonical_identity.sql
git -C "$repository" commit -qm partial-identity-relocation
expect_failure "$repository" "$base"

repository="$(new_identity_relocation_repository modified-identity-relocation)"
base="$(git -C "$repository" rev-parse HEAD)"
git -C "$repository" mv \
  backend/src/main/resources/db/migration/tenant/V123__canonical_identity.sql \
  backend/src/main/resources/db/migration/tenant/V127__canonical_identity.sql
git -C "$repository" mv \
  backend/src/main/resources/db/migration/tenant/V124__identity_collision_report.sql \
  backend/src/main/resources/db/migration/tenant/V128__identity_collision_report.sql
printf '\nSELECT 127;\n' \
  >> "$repository/backend/src/main/resources/db/migration/tenant/V127__canonical_identity.sql"
git -C "$repository" add backend/src/main/resources/db/migration/tenant
git -C "$repository" commit -qm modified-identity-relocation
expect_failure "$repository" "$base"
