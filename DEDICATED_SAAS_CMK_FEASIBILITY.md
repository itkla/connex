# Dedicated SaaS CMK Feasibility

Status: feasibility decision for [#376], tied to the dedicated database
architecture in [#313]. This is not a shipped product commitment. Dedicated
SaaS customer-managed key language must not be used in contracts, marketing,
security questionnaires, or implementation issues unless this document's
implementation gates are satisfied and the customer agreement names the mode.

## Decision

Connex-operated pooled SaaS does not support customer-managed database keys.
In this document, customer-managed key means the Connex customer has the
contracted ability to control or disable the database storage key. It does not
mean merely that Connex uses an AWS customer managed KMS key in a Connex-owned
AWS account.

The planned dedicated per-organization database tier may be sold today only as
dedicated database isolation plus the hosted storage-encryption posture defined
in [SAAS_STORAGE_ENCRYPTION_RUNBOOK.md](SAAS_STORAGE_ENCRYPTION_RUNBOOK.md).
It is not a customer-managed-key tier by default.

True customer-managed database key custody is supportable for Connex-operated
SaaS only if the dedicated tier is implemented as an infrastructure unit whose
database storage, snapshots, backups, replicas, and restore targets can all be
created and verified under that organization's selected KMS key. The current
[#313] `CREATE DATABASE cnx_<random>` shape on a shared database server is a
dedicated database/catalog boundary, not a per-organization storage-key
boundary.

Customers that require direct key custody before that hosted implementation
exists should use the customer-operated/on-prem model in
[ON_PREM_ENCRYPTION_RUNBOOK.md](ON_PREM_ENCRYPTION_RUNBOOK.md).

## Feasibility By Topology

| Topology | Feasibility | Customer-facing posture |
| --- | --- | --- |
| Pooled SaaS database | Not feasible. Multiple organizations share the same database/storage key boundary. | "Hosted pooled SaaS uses Connex/cloud-controlled storage encryption when the `SAAS_STORAGE_ENCRYPTION_RUNBOOK.md` evidence gate is satisfied; it does not provide customer-managed database keys." |
| Dedicated schema/catalog on a shared DB instance or cluster | Not a true per-org CMK boundary. For AWS RDS, this is an architectural inference from encryption being configured for DB instance/cluster storage resources, logs, backups, read replicas, and snapshots; a separate logical database name does not by itself create a separate KMS key boundary. | "Dedicated database isolation reduces shared-database blast radius, but the storage key is still Connex/cloud controlled unless a separate infrastructure-level CMK mode is implemented." |
| Dedicated DB instance or cluster per organization | Technically feasible on AWS RDS/Aurora when created with a customer-controlled or contractually dedicated KMS key and grant model. Operationally supportable only after per-org provisioning, grants, key-policy checks, backup/snapshot/restore handling, monitoring, revocation runbooks, and the hosted storage evidence gate exist. | "Dedicated CMK is available only for contracted dedicated infrastructure whose key, backups, replicas, and restore process are verified per organization. The running Connex app still processes plaintext." |
| Customer-operated/on-prem | Supported outside Connex-operated SaaS. The customer controls the database/storage/keyring/KMS/HSM/KMIP/Vault layer and backup keys. | "Customer-operated deployments give the customer infrastructure and key custody; the running app still processes plaintext inside that environment." |

## Implementation Gates

A Connex-operated dedicated CMK tier is not supportable until all of these are
implemented and verified:

- A per-org infrastructure boundary exists for database storage encryption. A
  dedicated logical schema on a shared encrypted server is insufficient for a
  per-org storage-key claim.
- Provisioning chooses the encryption mode and KMS key at database
  instance/cluster creation time, before Connex migrations run.
- The provisioning credential can create the database resource and KMS grant,
  but the application credential cannot alter encryption, key policy, backups,
  snapshots, or replica posture.
- The registry described in [#313] records the key owner, encryption mode, KMS
  key identifier or alias, KMS account/region, revocation semantics, backup
  key mode, snapshot-copy policy, restore validation state, and evidence
  timestamp for each dedicated organization.
- Backup, snapshot, read-replica, restore, blue-green, and repointing flows
  fail closed if the target encryption mode or KMS key does not match the
  organization's contracted posture.
- Key disablement, revocation, grant removal, and recovery are runbooked and
  tested for the selected provider. For AWS RDS, disabling the KMS key can make
  the DB instance inaccessible; revoking the original caller's key access after
  RDS has created its grant does not necessarily stop a running database.
- Security docs and the DPA name the exact mode: Connex-managed dedicated
  database encryption, customer-managed KMS key for dedicated infrastructure,
  or customer-operated/on-prem key custody.

## Registry Requirements For #313

The dedicated placement registry should carry at least these fields or
equivalent structured values:

| Field | Purpose |
| --- | --- |
| `org_id` | Customer boundary for the dedicated database placement. |
| `placement_mode` | `shared`, `dedicated_database`, or future `dedicated_cmk`. |
| `database_handle` | Random non-customer-derived handle such as `cnx_<random>`. |
| `storage_encryption_mode` | `provider_managed`, `connex_managed_cmk`, `customer_managed_cmk`, or `customer_operated`. |
| `key_controller` | Connex, Connex cloud provider, customer, or customer-operated environment. |
| `kms_provider` | Provider such as AWS KMS, external KMS/HSM/KMIP/Vault, or none. |
| `kms_key_ref` | Non-secret key ARN/alias/id reference where applicable. |
| `kms_key_region` | Region/account boundary for provider-managed keys. |
| `revocation_supported` | Whether customer action can make the dedicated environment unavailable. |
| `revocation_effect` | Expected lockout and recovery behavior. |
| `backup_encryption_mode` | Backup/snapshot key handling for the dedicated organization. |
| `snapshot_copy_policy` | Whether copied snapshots must retain or change key. |
| `restore_validation_state` | Last restore/blue-green/repointing encryption validation result. |
| `evidence_checked_at` | Last verification timestamp for the stored posture. |

## Restore And Repointing Rules

- Restores must create a new target under the organization's recorded
  encryption posture before traffic moves.
- Blue-green and repointing flows must compare source and target
  `storage_encryption_mode`, `kms_key_ref`, `kms_key_region`,
  `backup_encryption_mode`, and `revocation_supported`.
- Same-region replicas must preserve the expected key boundary. Cross-region
  replicas or copied snapshots must specify a destination-region key and update
  the registry only after verification.
- Logical exports remain plaintext at generation unless a contracted export
  process encrypts the artifact before customer or operator access.

## Allowed Wording

- "Dedicated database isolation is a tier-specific tenant-isolation control."
- "Dedicated CMK is available only if the signed order form and deployment
  evidence identify a per-organization infrastructure key boundary."
- "Dedicated CMK changes storage custody and revocation behavior; it does not
  make the running Connex application blind to data."
- "Customers requiring direct key custody without a hosted dedicated-CMK
  contract should use customer-operated/on-prem deployment."

## Blocked Wording

- "All dedicated databases have customer-managed keys."
- "Dedicated database means customer-owned encryption keys."
- "Customer revocation is available for hosted SaaS by default."
- "CMK makes Connex unable to process or view plaintext while providing the
  service."

## References

- AWS RDS encryption overview: https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Overview.Encryption.html
- Connex SaaS storage encryption runbook: [SAAS_STORAGE_ENCRYPTION_RUNBOOK.md](SAAS_STORAGE_ENCRYPTION_RUNBOOK.md)
- Connex encryption guarantee matrix: [ENCRYPTION_GUARANTEE_MATRIX.md](ENCRYPTION_GUARANTEE_MATRIX.md)
- Customer-operated encryption runbook: [ON_PREM_ENCRYPTION_RUNBOOK.md](ON_PREM_ENCRYPTION_RUNBOOK.md)

[#313]: https://github.com/itkla/connex/issues/313
[#371]: https://github.com/itkla/connex/issues/371
[#376]: https://github.com/itkla/connex/issues/376
