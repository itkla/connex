# Connex SaaS Storage Encryption Runbook

This runbook defines the production storage-encryption baseline for
Connex-operated SaaS in AWS `ap-northeast-1`. It covers customer-data storage
surfaces: primary databases, backups, snapshots, read replicas, restore
targets, object storage, operational exports, support artifacts, and logs that
may contain customer data.

This is a storage-layer control. It is not end-to-end encryption,
zero-knowledge encryption, or customer-managed database key custody. The Connex
backend processes customer CRM content in plaintext while providing the
service. Pooled SaaS infrastructure keys are controlled by Connex and AWS.

## Required Baseline

Hosted SaaS production cannot onboard customer data until every applicable row
in the evidence register below has an owner, an encryption mode, a key
controller, a verification command or console evidence link, and a fresh
verification timestamp.

| Surface | Required encryption mode | Key controller | Required evidence |
| --- | --- | --- | --- |
| Primary RDS or Aurora database | Storage encryption enabled at database instance or cluster creation, using the Connex production AWS account's KMS key for the deployment environment. | Connex and AWS. | `StorageEncrypted=true`, expected `KmsKeyId`, region `ap-northeast-1`, and no unencrypted production database resources. |
| Automated backups and point-in-time recovery | Inherited from the encrypted source database, with a retention period that meets policy and restore drills proving restored targets stay encrypted. | Connex and AWS. | Positive `BackupRetentionPeriod`, expected earliest/latest restorable times, retention-policy evidence, and a restored target using the expected KMS key. |
| Manual snapshots and snapshot copies | Encrypted and private. Same-region snapshots use the expected source key boundary; cross-region copies, if ever approved, specify a destination-region KMS key. | Connex and AWS. | Snapshot inventory with `Encrypted=true`, expected `KmsKeyId`, `restore` attributes limited to approved account ids and never `all`, copy policy, and retention owner. |
| Read replicas | Encrypted. Same-region replicas keep the expected key boundary; cross-region replicas, if ever approved, use the destination-region KMS key recorded in the evidence. | Connex and AWS. | Replica inventory with encryption and key evidence before replication receives traffic. |
| Restored, blue-green, or migration targets | Encrypted before traffic or customer data moves. Restore/repointing fails closed if encryption mode or key evidence is missing. | Connex and AWS. | Pre-cutover checklist comparing source and target encryption mode, key ARN, region, and restore timestamp. |
| S3 buckets for attachments, imports, exports, backup staging, and support artifacts | Bucket default encryption using SSE-KMS or DSSE-KMS with Connex-managed KMS keys for production buckets. Bucket policy denies non-TLS access and, where service integrations supply encryption headers, rejects a non-KMS mode or unexpected KMS key; writes without encryption headers are acceptable only when the verified bucket default applies the required mode and key. | Connex and AWS. | Bucket encryption configuration with the expected KMS key ARN, bucket policy, strict KMS-mode/key continuous control, replication encryption config where used, object sample checks, and retention lifecycle policy. |
| Existing S3 objects after a bucket encryption change | Rewritten or batch-copied under the required bucket encryption mode. | Connex and AWS. | Inventory or batch-operation evidence that no production object remains under an older or missing encryption mode. |
| CloudWatch Logs groups and stored query results that may contain customer data | Production application, audit, security, worker, and database log groups must associate the Connex production KMS key unless a group is proven to contain no customer data. Stored CloudWatch Logs Insights query results must separately associate that key through the account-level `query-result:*` resource identifier. | Connex and AWS. | Log group inventory with `kmsKeyId`, retention days, and owner; a completed query result showing the expected `encryptionKey`; exception record for any group without KMS association. |
| AWS Backup vaults and recovery points, if used | Backup vaults use the Connex production KMS key; recovery points are encrypted. Cross-region copies use the destination vault key. | Connex and AWS. | Vault key ARN, recovery-point encryption inventory, copy policy, Vault Lock posture if enabled, and restore drill evidence. |
| CI artifacts, ticket attachments, local debug dumps, and ad hoc operator files | Customer data is prohibited. If an emergency exception is approved, the artifact must be stored only in the encrypted support-artifact bucket with a ticket, owner, retention date, and deletion evidence. | Connex and AWS for approved storage; operator owns deletion evidence. | Exception ticket, object key, KMS key, retention date, and deletion verification. |
| Customer CSV/API exports | Plaintext at generation. Any server-side staging storage uses the encrypted export bucket and short retention. After download, the customer is responsible for protection. | Connex and AWS for staging; customer after download. | Export bucket encryption and lifecycle evidence; customer-facing wording that exports are plaintext when generated. |

## AWS Verification Commands

Run these checks before production launch, after material infrastructure
changes, and after every restore drill. Store command output or console
evidence in the deployment evidence record.

### RDS or Aurora

Repeat the inventory for every AWS account that can hold production, backup,
support, or disaster-recovery data. Enumerate every enabled Region; the expected
result is that customer-data resources exist only in the approved processing
Region.

```bash
ENABLED_REGIONS=$(aws ec2 describe-regions \
  --all-regions \
  --region ap-northeast-1 \
  --query "Regions[?OptInStatus!='not-opted-in'].RegionName" \
  --output text)

for REGION in $ENABLED_REGIONS; do
  aws rds describe-db-instances \
    --region "$REGION" \
    --query 'DBInstances[].{DBInstanceIdentifier:DBInstanceIdentifier,Engine:Engine,StorageEncrypted:StorageEncrypted,KmsKeyId:KmsKeyId,BackupRetentionPeriod:BackupRetentionPeriod,EarliestRestorableTime:EarliestRestorableTime,LatestRestorableTime:LatestRestorableTime,DBClusterIdentifier:DBClusterIdentifier,ReadReplicaSourceDBInstanceIdentifier:ReadReplicaSourceDBInstanceIdentifier}'

  aws rds describe-db-clusters \
    --region "$REGION" \
    --query 'DBClusters[].{DBClusterIdentifier:DBClusterIdentifier,Engine:Engine,StorageEncrypted:StorageEncrypted,KmsKeyId:KmsKeyId,BackupRetentionPeriod:BackupRetentionPeriod,EarliestRestorableTime:EarliestRestorableTime,LatestRestorableTime:LatestRestorableTime,ReplicationSourceIdentifier:ReplicationSourceIdentifier}'

  aws rds describe-db-instance-automated-backups --region "$REGION"
  aws rds describe-db-cluster-automated-backups --region "$REGION"

  aws rds describe-db-snapshots \
    --region "$REGION" \
    --snapshot-type manual \
    --query 'DBSnapshots[].{DBSnapshotIdentifier:DBSnapshotIdentifier,DBInstanceIdentifier:DBInstanceIdentifier,Encrypted:Encrypted,KmsKeyId:KmsKeyId,SnapshotCreateTime:SnapshotCreateTime}'

  aws rds describe-db-cluster-snapshots \
    --region "$REGION" \
    --snapshot-type manual \
    --query 'DBClusterSnapshots[].{DBClusterSnapshotIdentifier:DBClusterSnapshotIdentifier,DBClusterIdentifier:DBClusterIdentifier,StorageEncrypted:StorageEncrypted,KmsKeyId:KmsKeyId,SnapshotCreateTime:SnapshotCreateTime}'

  for DB_SNAPSHOT_ID in $(aws rds describe-db-snapshots \
    --region "$REGION" \
    --snapshot-type manual \
    --query 'DBSnapshots[].DBSnapshotIdentifier' \
    --output text); do
    aws rds describe-db-snapshot-attributes \
      --region "$REGION" \
      --db-snapshot-identifier "$DB_SNAPSHOT_ID" \
      --query 'DBSnapshotAttributesResult.DBSnapshotAttributes'
  done

  for DB_CLUSTER_SNAPSHOT_ID in $(aws rds describe-db-cluster-snapshots \
    --region "$REGION" \
    --snapshot-type manual \
    --query 'DBClusterSnapshots[].DBClusterSnapshotIdentifier' \
    --output text); do
    aws rds describe-db-cluster-snapshot-attributes \
      --region "$REGION" \
      --db-cluster-snapshot-identifier "$DB_CLUSTER_SNAPSHOT_ID" \
      --query 'DBClusterSnapshotAttributesResult.DBClusterSnapshotAttributes'
  done
done
```

Fail production readiness if any production RDS/Aurora resource returns a
missing or false encryption field, an unexpected key, or a region outside the
approved processing location. For a standalone RDS instance
(`DBClusterIdentifier` is empty), fail if `BackupRetentionPeriod=0`, retention
is below policy, or restorable-time evidence is missing. For Aurora and other
cluster-backed instances, evaluate retention and restorable times on the DB
cluster instead; do not apply the standalone-instance retention gate to its
member instances. Include retained automated backups for deleted instances and
clusters, and fail unexpected encryption, key, account, or Region posture.
Inspect the sharing attributes of every manual instance and cluster snapshot;
fail if any `restore` values contain `all` or an unapproved AWS account id.

### S3

```bash
aws s3api get-bucket-encryption --bucket "$BUCKET"
aws s3api get-bucket-location --bucket "$BUCKET"
aws s3api get-bucket-policy --bucket "$BUCKET"
aws s3api get-bucket-lifecycle-configuration --bucket "$BUCKET"
aws s3api get-bucket-replication --bucket "$BUCKET" \
  --query 'ReplicationConfiguration.{Role:Role,Rules:Rules[].{Status:Status,Destination:Destination.{Bucket:Bucket,Account:Account,StorageClass:StorageClass,ReplicaKmsKeyID:EncryptionConfiguration.ReplicaKmsKeyID}}}'
aws s3api head-object --bucket "$BUCKET" --key "$SAMPLE_KEY" \
  --query '{ServerSideEncryption:ServerSideEncryption,SSEKMSKeyId:SSEKMSKeyId,BucketKeyEnabled:BucketKeyEnabled}'

aws s3api head-bucket \
  --bucket "$DESTINATION_BUCKET" \
  --expected-bucket-owner "$EXPECTED_DESTINATION_ACCOUNT_ID"
aws s3api get-bucket-location \
  --bucket "$DESTINATION_BUCKET" \
  --expected-bucket-owner "$EXPECTED_DESTINATION_ACCOUNT_ID"
```

Fail production readiness if a bucket holding customer data has no default
encryption, lacks the required KMS mode, allows unencrypted writes, allows
non-TLS access, lacks lifecycle retention for staging/export/support objects,
has pre-existing objects outside the required encryption mode, is outside the
approved Region, or replicates to an unapproved account, Region, or KMS key.
For every enabled replication rule, derive `DESTINATION_BUCKET` from the
destination ARN, verify ownership with the approved destination account id,
verify the destination bucket's Region, and verify the replica KMS key's full
ARN, account, and Region. A bucket ARN alone does not prove its owner or Region.

### CloudWatch Logs

```bash
for REGION in $ENABLED_REGIONS; do
  aws logs describe-log-groups \
    --region "$REGION" \
    --query 'logGroups[].{logGroupName:logGroupName,kmsKeyId:kmsKeyId,retentionInDays:retentionInDays,creationTime:creationTime}'
done

aws logs associate-kms-key \
  --region "$LOGS_REGION" \
  --resource-identifier "arn:aws:logs:$LOGS_REGION:$AWS_ACCOUNT_ID:query-result:*" \
  --kms-key-id "$LOGS_KMS_KEY_ARN"

aws logs get-query-results \
  --region "$LOGS_REGION" \
  --query-id "$QUERY_ID" \
  --query '{Status:status,EncryptionKey:encryptionKey}'
```

Fail production readiness if a production application, audit, security, worker,
or database log group may contain customer data and has no KMS key association
or no retention owner. The log-group association does not cover stored query
results: associate the `query-result:*` resource before launch, run a
representative Logs Insights query, wait for it to complete, set `QUERY_ID`, and
verify that `EncryptionKey` is the expected key ARN in every applicable account
and Region. A current `kmsKeyId` applies only to events ingested after that key
was associated; it does not re-encrypt older events. Evidence must prove the
association preceded the earliest retained event, or prove that all
pre-association events expired or were deleted. Keep every historical KMS key
needed to read retained events available until those events expire. Treat logs
as customer-data-adjacent even when the application is designed not to log
personal data.

### AWS Backup

```bash
for REGION in $ENABLED_REGIONS; do
  aws backup list-backup-vaults \
    --region "$REGION" \
    --query 'BackupVaultList[].{BackupVaultName:BackupVaultName,BackupVaultArn:BackupVaultArn,EncryptionKeyArn:EncryptionKeyArn,Locked:Locked}'

  for VAULT in $(aws backup list-backup-vaults \
    --region "$REGION" \
    --query 'BackupVaultList[].BackupVaultName' \
    --output text); do
    aws backup list-recovery-points-by-backup-vault \
      --region "$REGION" \
      --backup-vault-name "$VAULT" \
      --query 'RecoveryPoints[].{RecoveryPointArn:RecoveryPointArn,ResourceType:ResourceType,ResourceArn:ResourceArn,IsEncrypted:IsEncrypted,EncryptionKeyArn:EncryptionKeyArn,EncryptionKeyType:EncryptionKeyType,CreationDate:CreationDate,Status:Status}'
  done
done
```

Fail production readiness if a backup vault that stores customer-data recovery
points has no expected KMS key, if any recovery point is unencrypted, or if a
cross-region copy lacks destination-key evidence. Repeat the inventory in every
applicable AWS account and enabled Region; a vault name is scoped to both.

### AWS Config And Security Hub

Enable AWS Config managed rules, or an equivalent continuously evaluated
control, for at least:

| Rule | Purpose |
| --- | --- |
| `rds-storage-encrypted` with the expected `kmsKeyId` parameter | DB instances must have storage encryption enabled with the expected key. |
| `rds-cluster-encrypted-at-rest` | Baseline only: RDS/Aurora DB clusters must have storage encryption enabled. |
| Custom AWS Config rule or equivalent continuously evaluated policy for `AWS::RDS::DBCluster.StorageEncrypted` and the expected `KmsKeyId` | RDS/Aurora DB clusters must be encrypted with the environment's expected key; the managed cluster rule has no expected-key parameter. |
| `db-instance-backup-enabled` with `backupRetentionMinimum=<approved days>` and `checkReadReplicas=true` | RDS DB instances, including read replicas, must have automated backups enabled and retained for policy. Do not substitute the different `backupRetentionPeriod` equality-style input. |
| `rds-cluster-backup-retention-check` with `minimumBackupRetentionPeriod=<approved days>` | RDS/Aurora DB clusters must retain automated backups for policy. |
| `rds-snapshot-encrypted` | RDS snapshots must be encrypted. |
| `s3-bucket-server-side-encryption-enabled` | Baseline only: S3 buckets must have some default encryption or a policy that denies unencrypted writes. |
| Custom AWS Config rule or equivalent continuously evaluated policy for S3 KMS mode and key | Production customer-data buckets must use SSE-KMS or DSSE-KMS with the expected Connex-managed KMS key; SSE-S3/AES256 must fail. |
| `cloudwatch-log-group-encrypted` with the expected `KmsKeyId` parameter | Customer-data-adjacent log groups must remain associated with the expected KMS key. |
| `backup-recovery-point-encrypted` | AWS Backup recovery points must be encrypted. |

The managed `s3-bucket-server-side-encryption-enabled` rule is not evidence of
the required KMS posture by itself: it also accepts SSE-S3/AES256 and has no
parameter for an expected KMS key. The strict S3 control must inspect the bucket
default encryption algorithm and key ARN. The bucket policy must prevent callers
from selecting a disallowed encryption mode or key; a request that omits those
headers is acceptable only when the verified bucket default applies the
required KMS mode and key.

Production readiness requires no open noncompliant finding for customer-data
resources. Any suppression must name the surface, data classification, owner,
expiry, and compensating control.

## Restore And Replica Rules

- Never restore customer data into an unencrypted database, snapshot, bucket,
  or backup vault.
- A restore target must be verified before migrations, workers, or traffic run
  against it.
- Same-region RDS replicas must keep the expected encrypted key boundary for
  the source resource. Cross-region replicas or snapshot copies require an
  explicit destination-region key and updated evidence before they are used.
- Blue-green, migration, failover, and rollback plans must compare source and
  target encryption mode, key ARN, region, backup policy, and log group KMS
  posture before traffic moves.
- Restore drills must record the source artifact, target resource, KMS key,
  verifier, timestamp, and cleanup/deletion evidence for temporary resources.

## Exports And Support Artifacts

Exports are boundary-crossing artifacts. CSV, API, logical SQL dumps, debug
bundles, customer-provided reproduction files, and support extracts are
plaintext at generation unless a separate encryption step is applied before
storage or transfer.

Production rules:

- Do not place customer data in CI artifacts, chat, email attachments, local
  desktops, shared drives, or tickets.
- If support needs a customer-data artifact, store it only in the encrypted
  support-artifact bucket with a ticket ID, purpose, approver, owner, retention
  date, and deletion evidence.
- Export staging buckets must have short lifecycle retention and encrypted
  object evidence.
- Customer-facing docs and the DPA must state that downloaded exports are the
  customer's responsibility to protect.
- Tenant teardown and termination workflows in [#105] must include export
  staging deletion and backup-retention evidence.

## Evidence Register Template

Create one evidence record per production environment. Keep evidence outside
the application repository.

| Field | Value |
| --- | --- |
| Environment | `prod` / `staging` / restore drill |
| AWS accounts inventoried | |
| Enabled Regions inventoried | |
| Approved customer-data Region | `ap-northeast-1` |
| Database resource ids | |
| Database encryption mode and key ARN | |
| Backup/PITR retention and encryption evidence | |
| Manual snapshot encryption and sharing-attribute evidence | |
| Replica/restore target evidence | |
| S3 customer-data buckets, Regions, replication destinations, and key ARNs | |
| Strict S3 KMS mode/key control status | |
| Log groups, KMS association times, earliest retained events, query results, and current/historical key ARNs | |
| AWS Backup vaults and key ARNs | |
| Config/Security Hub rule status | |
| Export/support bucket lifecycle policy | |
| Last restore drill | |
| Exceptions with expiry | |
| Verified by | |
| Verified at | |

## Launch Gate

Do not launch hosted SaaS production or claim hosted at-rest encryption as
shipped unless:

1. The evidence register is complete for the production environment.
2. AWS Config or equivalent continuous checks are enabled for RDS instances and
   clusters, automated-backup retention, snapshots, strict S3 KMS mode/key,
   CloudWatch log-group KMS association, and AWS Backup encryption.
3. Every database, backup, snapshot, replica, restore target, bucket, log group,
   stored log-query result, and customer-data artifact path has an expected
   encryption mode and owner.
4. At least one restore drill proves encrypted backups restore into encrypted
   targets.
5. Customer-facing wording matches
   [ENCRYPTION_GUARANTEE_MATRIX.md](ENCRYPTION_GUARANTEE_MATRIX.md): storage
   encryption is not E2EE, zero-knowledge, or customer-managed key custody for
   pooled SaaS.

## References

- Connex encryption guarantee matrix: [ENCRYPTION_GUARANTEE_MATRIX.md](ENCRYPTION_GUARANTEE_MATRIX.md)
- Dedicated SaaS CMK feasibility: [DEDICATED_SAAS_CMK_FEASIBILITY.md](DEDICATED_SAAS_CMK_FEASIBILITY.md)
- Customer-operated encryption runbook: [ON_PREM_ENCRYPTION_RUNBOOK.md](ON_PREM_ENCRYPTION_RUNBOOK.md)
- AWS RDS encryption overview: https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Overview.Encryption.html
- AWS RDS retained automated backups: https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_WorkingWithAutomatedBackups.Retaining.html
- AWS Aurora retained automated backups: https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/Aurora.Managing.Backups.Retaining.html
- AWS S3 server-side encryption: https://docs.aws.amazon.com/AmazonS3/latest/userguide/serv-side-encryption.html
- AWS S3 SSE-KMS bucket-policy controls: https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingKMSEncryption.html
- AWS CloudWatch Logs KMS encryption: https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/encrypt-log-data-kms.html
- AWS CloudWatch Logs Insights query-result encryption: https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/CloudWatchLogs-Insights-Query-Encrypt.html
- AWS Backup encryption: https://docs.aws.amazon.com/aws-backup/latest/devguide/encryption.html
- AWS Config `rds-storage-encrypted`: https://docs.aws.amazon.com/config/latest/developerguide/rds-storage-encrypted.html
- AWS Config `rds-cluster-encrypted-at-rest`: https://docs.aws.amazon.com/config/latest/developerguide/rds-cluster-encrypted-at-rest.html
- AWS Config `db-instance-backup-enabled`: https://docs.aws.amazon.com/config/latest/developerguide/db-instance-backup-enabled.html
- AWS Config `rds-cluster-backup-retention-check`: https://docs.aws.amazon.com/config/latest/developerguide/rds-cluster-backup-retention-check.html
- AWS Config `rds-snapshot-encrypted`: https://docs.aws.amazon.com/config/latest/developerguide/rds-snapshot-encrypted.html
- AWS Config `s3-bucket-server-side-encryption-enabled`: https://docs.aws.amazon.com/config/latest/developerguide/s3-bucket-server-side-encryption-enabled.html
- AWS Config `cloudwatch-log-group-encrypted`: https://docs.aws.amazon.com/config/latest/developerguide/cloudwatch-log-group-encrypted.html
- AWS Config `backup-recovery-point-encrypted`: https://docs.aws.amazon.com/config/latest/developerguide/backup-recovery-point-encrypted.html
- AWS RDS snapshot sharing attributes: https://docs.aws.amazon.com/cli/latest/reference/rds/describe-db-snapshot-attributes.html
- AWS RDS cluster snapshot sharing attributes: https://docs.aws.amazon.com/cli/latest/reference/rds/describe-db-cluster-snapshot-attributes.html

[#105]: https://github.com/itkla/connex/issues/105
[#371]: https://github.com/itkla/connex/issues/371
