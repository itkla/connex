# Connex On-Prem Encryption Default-On Runbook

This runbook defines the supported customer-operated encryption posture for
Connex deployments. It is for on-prem or customer-operated environments where
the customer controls infrastructure, database administration, key custody,
backup custody, and support access.

Connex must not require a modified application package to run with encryption
default-on. Operators wire encryption through MySQL, storage, KMS/keyring
systems, environment variables, and deployment policy outside the Connex WAR.

## Supported Modes

Use one of these modes before running Connex migrations in a customer-operated
environment.

| Mode | Required controls | Suitable for |
| --- | --- | --- |
| Customer-managed volume encryption | Customer-controlled encryption for MySQL data directory, redo/undo/binlog storage, temporary storage, backup staging, application logs, and any attached object/file storage. Keys are held in the customer's cloud KMS, HSM, storage platform, or equivalent. | Baseline on-prem posture and environments where MySQL tablespace encryption is unavailable. |
| MySQL/InnoDB data-at-rest encryption | MySQL keyring component loaded before InnoDB initialization, encrypted Connex schemas/tablespaces by default, encrypted redo logs, encrypted undo logs, and encrypted binary logs where supported. | Preferred database-level posture for customer-operated MySQL. |
| Centralized key manager | MySQL Enterprise or platform integration with a customer-controlled key manager such as Vault, HSM, KMIP, cloud KMS, or equivalent. | Regulated deployments that require centralized custody, key audit, and revocation workflows. |
| File keyring with encrypted host storage | MySQL file-based keyring stored on customer-encrypted host storage with strict filesystem permissions and independent backup custody. | Small on-prem installs where Enterprise/KMS integration is unavailable. Not a compliance-grade substitute for centralized key management. |

The customer may combine volume encryption and MySQL/InnoDB encryption. For
regulated environments, prefer centralized key management over a local file
keyring.

## MySQL Configuration Pattern

Install and configure exactly one MySQL keyring component before encrypted
InnoDB tablespaces exist. MySQL documents that keyring components should be
loaded by manifest, not only by `INSTALL COMPONENT`, because InnoDB may need
the keyring during early server startup.

Use a version-specific MySQL manifest/configuration for the selected keyring
provider, then set the encryption defaults in MySQL configuration:

```ini
[mysqld]
default_table_encryption=ON
table_encryption_privilege_check=ON
innodb_redo_log_encrypt=ON
innodb_undo_log_encrypt=ON
binlog_encryption=ON
require_secure_transport=ON
```

`binlog_encryption` depends on MySQL version/edition and the selected keyring
provider. If the server cannot enable it, the deployment must protect binary
logs with customer-managed volume encryption and restrict binlog access.

For the file keyring component, keep the manifest and keyring configuration
owned by the MySQL runtime user, readable only by that user, and outside the
Connex source tree. Store the keyring backing file on encrypted customer-owned
storage and include it in the recovery plan.

Example file-keyring manifest:

```json
{
  "components": "file://component_keyring_file"
}
```

Example file-keyring component configuration:

```json
{
  "path": "/var/lib/mysql-keyring/component_keyring_file.keys",
  "read_only": false
}
```

For Vault/HSM/KMIP/KMS-backed keyrings, keep provider credentials, TLS trust,
network policy, and service identity outside the Connex WAR. Connex operators
must not bake keyring credentials into application artifacts or public images.
Provider-backed keyrings are version- and edition-specific: MySQL 8.4 documents
provider-backed plugins such as `keyring_hashicorp`, `keyring_okv`, and
`keyring_aws`; newer MySQL releases may document provider-backed components.
Use the exact plugin or component name and load path from the deployed MySQL
version. Component keyrings use a manifest/configuration file. Plugin keyrings
must be loaded early enough for InnoDB, typically with MySQL's plugin startup
options such as `early-plugin-load`, and use plugin-specific configuration
variables/files.

Example centralized-provider checklist:

```text
Provider type: Vault, HSM, KMIP, KMS, or cloud KMS
MySQL artifact: documented plugin or component name for this MySQL version
Load path: manifest for a component, early plugin load for a plugin
Provider credentials: customer secret manager, not Connex artifact
Provider TLS trust: customer CA/cert path, rotated by customer
Backend policy: scoped to the MySQL server identity and Connex environment
Recovery evidence: provider policy, identity, and key metadata restored in drill
```

Create or alter the Connex schema with an encryption default before Flyway
runs. Replace `connexdb` with the deployment database name:

```sql
CREATE SCHEMA IF NOT EXISTS connexdb DEFAULT ENCRYPTION = 'Y';
ALTER SCHEMA connexdb DEFAULT ENCRYPTION = 'Y';
```

The Connex database user should not have `TABLE_ENCRYPTION_ADMIN` in normal
operation. That keeps migrations from silently overriding the required schema
encryption default when `table_encryption_privilege_check=ON`.

## Connex Runtime Configuration

Configure Connex through environment/configuration only:

- `CONNEX_DB_URL` uses MySQL Connector/J verified TLS in non-dev deployments:
  `sslMode=VERIFY_CA` or `sslMode=VERIFY_IDENTITY`.
- `CONNEX_DB_USERNAME` and `CONNEX_DB_PASSWORD` are provisioned from the
  customer's secret manager.
- `CONNEX_SECRET_STORE_KEY_ID`, `CONNEX_SECRET_STORE_MASTER_KEY`,
  `CONNEX_SECRET_STORE_KEYS_*`, and `CONNEX_SECRET_STORE_DISABLED_KEY_IDS`
  follow `SECRET_STORE_KEY_LIFECYCLE_RUNBOOK.md`.
- `CONNEX_AUDIT_INTEGRITY_HMAC_SECRET` is customer-held secret material when
  audit tamper-evidence is enabled.
- No secret belongs in a WAR, container image, repository, migration file,
  frontend `NEXT_PUBLIC_*` variable, or customer export.

## Preflight Verification

Run this checklist before onboarding data and after each database restore.

1. Confirm the selected keyring component loads before InnoDB startup.
2. Confirm the customer can restart MySQL without operator-supplied Connex
   secrets and with the expected key manager available.
3. Confirm required MySQL encryption variables:

   ```sql
   SHOW VARIABLES LIKE 'default_table_encryption';
   SHOW VARIABLES LIKE 'table_encryption_privilege_check';
   SHOW VARIABLES LIKE 'innodb_redo_log_encrypt';
   SHOW VARIABLES LIKE 'innodb_undo_log_encrypt';
   SHOW VARIABLES LIKE 'binlog_encryption';
   SHOW VARIABLES LIKE 'require_secure_transport';
   ```

4. Confirm `default_table_encryption` and `table_encryption_privilege_check`
   are both `ON`.
5. Confirm the Connex schema has `DEFAULT ENCRYPTION = 'Y'` before Flyway
   migrations run. For an existing empty schema, run `ALTER SCHEMA ... DEFAULT
   ENCRYPTION = 'Y'` before migration.
6. Run Connex Flyway migrations only after the server and schema defaults are
   verified.
7. Verify created Connex tablespaces/tables with the deployed MySQL version's
   metadata. For MySQL versions exposing `INFORMATION_SCHEMA.INNODB_TABLESPACES`
   encryption state, the query should return no `N` rows for Connex
   tablespaces:

   ```sql
   SELECT NAME, SPACE_TYPE, ENCRYPTION
   FROM INFORMATION_SCHEMA.INNODB_TABLESPACES
   WHERE NAME LIKE 'connexdb/%'
     AND ENCRYPTION <> 'Y';
   ```

   Also inspect table-level options where available:

   ```sql
   SELECT TABLE_SCHEMA, TABLE_NAME, CREATE_OPTIONS
   FROM INFORMATION_SCHEMA.TABLES
   WHERE TABLE_SCHEMA = 'connexdb'
     AND CREATE_OPTIONS LIKE '%ENCRYPTION%';
   ```

   Use the deployment's MySQL metadata model if these views differ.
8. Confirm the Connex application starts with verified database TLS.
9. Confirm a full backup and isolated restore can recover the database and
   keyring/key-manager state without Connex engineering access.
10. Confirm logical exports, CSV exports, and backup artifacts are encrypted
   before leaving the customer-controlled environment.
11. Record the key owner, recovery owner, rotation cadence, last restore-drill
   date, and lockout contact in the customer's deployment evidence.

Do not onboard production data if MySQL can create Connex tables while
`default_table_encryption=OFF`, if `table_encryption_privilege_check=OFF`, if
the Connex schema default is not encrypted, if any Connex tablespace verifies
as unencrypted, if the keyring cannot survive restart, or if backup encryption
is not proven.

## Backup, Restore, And Export Requirements

Physical backups must preserve encrypted tablespaces and the keyring or
key-manager metadata needed to restore them. Keyring backups and database
backups must be encrypted and access-controlled separately so that loss of one
artifact does not expose customer data alone.

Logical backups such as `mysqldump`, MySQL Shell dumps, CSV exports, and Connex
API exports contain plaintext data when generated. Encrypt them immediately
with customer-controlled keys before writing them to shared storage, ticket
attachments, email, object storage, or removable media.

Every production environment needs an isolated restore drill that proves:

- The database backup restores.
- The keyring/key-manager state restores.
- Connex can start without modifying the application package.
- A withheld or revoked customer key prevents access as expected.
- A restored customer key brings the service back without plaintext export.

## Key Revocation And Lockout Semantics

Customer-operated database key revocation is intentionally disruptive. If the
customer revokes, withholds, disables, or deletes the MySQL keyring/KMS/HSM/KMIP
key needed for encrypted tablespaces, MySQL may fail startup, fail recovery, or
refuse access to encrypted data. Connex cannot bypass that lockout and must not
fall back to a Connex-held database key unless the customer explicitly
contracted and configured that fallback.

Full-volume encryption has different live-system semantics. Revoking a volume
or disk key typically prevents attach, restart, restore, or snapshot
decryption, but it may not immediately stop a database process that is already
running on a mounted decrypted volume. The deployment evidence must state which
mode is used and whether revocation affects live access, restart, restore, or
backup decryptability.

This differs from the Connex secret store. Secret-store key revocation affects
never-searched integration credentials stored through `SecretStore`. It does
not encrypt or revoke searchable CRM database rows.

Restoring the exact required key material and key identity should restore
access. Replacing it with unrelated key material cannot decrypt old encrypted
database artifacts.

## Operations Checklist

- Select the encryption mode and key owner before installation.
- Install the keyring component or volume encryption before the first Connex
  migration.
- Enable `default_table_encryption=ON`,
  `table_encryption_privilege_check=ON`, encrypted Connex schema defaults, and
  redo/undo/binlog encryption where supported.
- Verify every Connex tablespace/table is encrypted before onboarding data.
- Store all keyring/KMS/Vault/HSM credentials outside Connex artifacts.
- Enforce verified database TLS for Connex connections.
- Encrypt and access-control physical backups, logical dumps, and exports.
- Test restore and key-withholding behavior before production onboarding.
- Document rotation, revocation, and emergency support access in the customer
  runbook.

## References

- Encryption guarantee matrix: `ENCRYPTION_GUARANTEE_MATRIX.md`
- Secret-store key lifecycle: `SECRET_STORE_KEY_LIFECYCLE_RUNBOOK.md`
- MySQL InnoDB data-at-rest encryption: https://dev.mysql.com/doc/refman/8.0/en/innodb-data-encryption.html
- MySQL InnoDB data-at-rest encryption FAQ: https://dev.mysql.com/doc/refman/8.4/en/faqs-tablespace-encryption.html
- MySQL keyring component installation: https://dev.mysql.com/doc/refman/en/keyring-component-installation.html
- MySQL file-based keyring component: https://dev.mysql.com/doc/refman/en/keyring-file-component.html
- MySQL backup and recovery overview: https://dev.mysql.com/doc/refman/8.0/en/backup-and-recovery.html
- MySQL `mysqldump`: https://dev.mysql.com/doc/refman/8.0/en/mysqldump.html

[#373]: https://github.com/itkla/connex/issues/373
