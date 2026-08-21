# Backend Migration Contract

This document is authoritative for Connex Flyway/schema changes. Read it before adding or changing a migration.

Tenancy and plane architecture is defined in `docs/MULTITENANCY_PLAN.md`; this document focuses on migration mechanics and schema invariants.

## Lineages and placement

Schema changes use Flyway under `backend/src/main/resources/db/migration/`.

- Tenant/org-data tables belong in the tenant migration lineage.
- Control-plane tables belong in the control migration lineage.
- The historical interleaved root lineage is frozen history; do not add new migrations there unless the repository's migration architecture explicitly changes.
- `TablePlaneRegistry` and architecture tests define/enforce table placement. A table must have one deliberate plane.
- No foreign key crosses the tenant/control plane wall in either direction. Validate cross-plane references in the service layer instead.

Read `docs/MULTITENANCY_PLAN.md` before deciding a new table's plane.

## Versioning

Versioned migrations use `V{next}__{snake_case_description}.sql`. Repeatable migrations use `R__{snake_case_description}.sql` only for definitions intentionally designed to be repeatable.

Connex uses one global version sequence across migration folders:

1. Determine the highest existing `V` across all migration lineages.
2. Use the next sequence number.
3. Rebase before merge.
4. If `main` gained a conflicting/higher migration, renumber every still-unmerged migration in the branch so its version again exceeds the latest `main` maximum.

Never edit or renumber a migration that has been applied/released. Migrations are forward-only.

CI/architecture tests validate additions against the pull-request base and the global lineage; fix the migration rather than weakening the gate.

## Tenant lifecycle obligations

A new org-data table is not complete merely because it has `workspace_id`.

- Add workspace scoping from day one.
- Enroll org-data tables in `TablePlaneRegistry.ORG_DATA_TABLES` and the corresponding `TenantLifecycleRegistry` declaration so export, teardown, and residual verification remain complete.
- Assess mappers that read person/subject data against `ProcessingRestrictionRegistry` where required by the APPI architecture.
- Workspace-owned holdings physically stored on the control plane still require explicit export, teardown, and residual-verification treatment through the control-workspace lifecycle registries/state declarations.

The lifecycle/APPI architecture tests are the enforcement surface. Do not add a table while leaving lifecycle ownership ambiguous.

## MySQL collation and composite foreign keys

Explicitly pin compatible charset/collation on `VARCHAR` columns participating in a composite foreign key when environments may inherit different database defaults.

Long-lived catalogs may have been created under a different `utf8mb4` collation than fresh environments. MySQL rejects composite FKs whose string columns have incompatible collations, and DDL auto-commit can leave Flyway partially applied/wedged.

Use the repository's current canonical collation for both parent key columns and every referencing child column. `V114__saved_view_record_type_collation.sql` is the reference pattern.

Do not rely on CI's fresh scratch database default to prove legacy-catalog compatibility.

## MyBatis/schema coordination

- Mapper Java interfaces live under `backend/src/main/java/.../mappers/`.
- Mapper XML lives under `backend/src/main/resources/mappers/`.
- Keep all SQL in mappers and use `#{}` bindings for values.
- Schema and mapper changes should land together when one depends on the other.
- New tenant-scoped queries filter on the resolved workspace/tenant and preserve plane boundaries.

## Migration checklist

Before merge:

- Correct tenant/control lineage selected.
- Version is globally next after rebasing onto current `main`.
- No applied migration edited or renumbered.
- Table has explicit plane placement.
- No cross-plane foreign key.
- Tenant/workspace lifecycle/export/teardown obligations are registered.
- Processing restrictions assessed where subject/person data is involved.
- Composite FK string columns have compatible explicit collation where needed.
- MyBatis mappings use parameter binding and tenant scoping.
- Relevant migration, plane, tenancy, and lifecycle architecture tests pass locally through targeted selectors; exhaustive suite is CI-owned.
