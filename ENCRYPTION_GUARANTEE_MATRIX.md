# Connex Encryption Guarantee Matrix

This is the canonical wording source for customer-facing encryption, key
custody, backup, export, and plaintext-access claims. Use it when updating
`SECURITY.md`, the DPA template, security questionnaires, customer proposals,
and dedicated/on-prem architecture notes.

Status: living control matrix for issues [#369] and [#373]. It is intentionally
conservative. Do not turn a planned control into a shipped claim.

## Docs Lint Checklist

Hosted Connex is not end-to-end encrypted, zero-knowledge, or unable to read
customer data. The Connex backend processes customer CRM content in plaintext
to provide the service. Storage encryption, backup encryption, RBAC, audit, and
operator controls reduce exposure but do not remove backend plaintext access.

Use this checklist before publishing any customer-facing security statement,
DPA clause, security-questionnaire answer, marketing page, proposal, runbook,
or architecture issue:

- Do not say hosted SaaS is E2EE, end-to-end encrypted, zero-knowledge, or
  customer-only-key encrypted.
- Do not say Connex cannot see, cannot access, or is technically unable to
  decrypt hosted SaaS data.
- Do not imply searchable CRM fields are protected by the secret-store
  envelope-encryption layer. That layer covers never-searched integration
  credentials only.
- Block those phrases in review unless they are used to deny the claim, qualify
  it to a specific customer-operated deployment, or quote this matrix.
- Do distinguish storage protection from application plaintext processing.
- Do identify who controls the key for each deployment model.
- Do state whether key revocation is supported and what availability loss it
  causes.
- Do state whether exports are plaintext at generation and who must encrypt
  them after export.

## Matrix

| Deployment model | Storage encryption mode | Key controller | Customer revocation | Backend/operator plaintext access | Backup, snapshot, export posture | Allowed customer-facing wording |
| --- | --- | --- | --- | --- | --- | --- |
| Connex-operated pooled SaaS | Managed cloud database/storage encryption for database volumes, object storage, snapshots, and backups is the required production posture. Do not claim it as shipped until production evidence is attached under [#371]. | Connex and its cloud provider control infrastructure keys. Customers do not control the database storage key. | Not available for searchable CRM data. Customers can revoke third-party credentials they own, and Connex secret-store keys can fail closed for never-searched credentials, but that is not database CMK. | Connex application code processes plaintext customer CRM content. Privileged Connex production access could expose plaintext through approved operational paths, subject to access control, audit, and incident controls. | Hosted backups and snapshots must be encrypted under Connex/cloud controls before Connex can make a shipped hosted at-rest encryption claim. Customer CSV/API exports are plaintext at generation and must be protected by the customer after download. | "Connex is implementing and evidencing hosted at-rest encryption controls under #371. Connex processes customer data to provide the service. Hosted SaaS is not E2EE or zero-knowledge." |
| Dedicated per-organization database tier | Future/dedicated architecture: organization-dedicated database/storage/backup scope with a per-org storage key or CMK option where supported by the managed database provider. Track feasibility and rollout under [#313]/[#100]/[#376]. | Connex controls keys by default. Customer-managed key custody exists only if explicitly contracted and implemented for the dedicated tier. | Not available by default. If a dedicated CMK tier is implemented, customer key revocation must make the dedicated environment unavailable until the key is restored or rotated by an agreed recovery process. | The Connex backend still processes plaintext while the dedicated environment is running and keys are available. Connex operator access depends on the support and access model in the signed agreement. | Dedicated snapshots/backups must stay inside the same tenant/key boundary. Logical exports are plaintext at generation unless separately encrypted by the customer or a contracted export process. | "Dedicated database isolation and optional CMK are tier-specific controls. They reduce shared-infrastructure blast radius but do not make the running application E2EE." |
| Customer-operated or on-prem | Customer-operated MySQL/InnoDB data-at-rest encryption, full-volume encryption, or both, enabled before Connex migrations run. Keyring/KMS/HSM/KMIP/Vault custody sits outside the Connex application package. See `ON_PREM_ENCRYPTION_RUNBOOK.md`. | Customer controls infrastructure, database keyring, KMS/HSM/KMIP/Vault policy, backup keys, and Connex runtime secrets. | Supported according to the selected mode. MySQL/InnoDB keyring revocation can prevent startup, recovery, or encrypted tablespace access when the database needs the key. Full-volume key revocation usually blocks attach, restart, restore, or snapshot decryptability, but may not stop an already-running mounted database immediately. | The Connex backend processes plaintext inside the customer environment while running. Connex personnel have no routine access to the environment or keys unless the customer grants support access. | Customer is responsible for encrypted physical backups, encrypted keyring backup custody, encrypted logical dumps, and encrypted app/API exports. Plaintext exports must not be stored or transferred unencrypted. | "In customer-operated deployments, the customer controls the environment and encryption keys. Key revocation has mode-specific lockout effects; the running app still processes plaintext inside that customer environment." |

## What Each Control Buys

Storage encryption protects against loss of disks, snapshots, unmanaged backup
media, and accidental exposure of raw storage. It does not stop a running
database, application process, privileged database user, or privileged support
operator from reading plaintext through authorized service paths.

Customer-managed database keys add custody and revocation leverage over the
storage layer. They still do not make the running application blind to data
while the key is available.

The Connex secret store is narrower than database encryption. It protects
never-searched credentials such as SMTP passwords, OIDC client secrets, and
SAML private keys with envelope encryption, key IDs, rotation, disabled-key
fail-closed behavior, diagnostics, and audit. It does not encrypt searchable
CRM fields.

Exports are a boundary crossing. CSV, API, logical SQL dumps, and similar
exports are plaintext when generated unless a separate customer-controlled
encryption step is applied.

## Security Questionnaire Boilerplate

Use these answers unless a signed customer agreement has a stricter deployment
specific commitment.

| Question | Answer |
| --- | --- |
| Is Connex data encrypted at rest? | The exact guarantee depends on the deployment model in the matrix above. Hosted pooled SaaS at-rest encryption must not be described as shipped until production evidence is attached under #371. Customer-operated deployments can run MySQL/InnoDB or volume encryption with customer-held keys. |
| Does Connex support customer-managed keys? | Hosted pooled SaaS does not provide customer-managed database keys. Dedicated per-organization CMK is a tracked future/dedicated-tier control. Customer-operated deployments can use customer-controlled keyring/KMS/HSM/KMIP/Vault integrations outside the Connex application package. |
| Can Connex access plaintext customer data? | In hosted SaaS, yes: Connex backend services process plaintext customer CRM content to provide the product, and privileged operational access is controlled by policy, audit, and least privilege. In customer-operated deployments, plaintext processing happens inside the customer's environment while the app runs. |
| Is Connex E2EE or zero-knowledge? | No for hosted SaaS and no for the running Connex application. Customer-operated encryption gives the customer infrastructure/key custody, not application-layer end-to-end encryption. |
| Are backups encrypted? | Hosted backup/snapshot encryption must not be described as shipped until production evidence is attached under #371. Customer-operated backups must be encrypted by the customer, including keyring backup material. Logical dumps and application exports are plaintext at generation unless separately encrypted. |
| What happens if a customer revokes a key? | For hosted pooled SaaS searchable CRM data, customer key revocation is not available. For customer-operated MySQL/InnoDB keys, revocation or withholding can lock the database/application out when the database needs the key. For full-volume encryption, revocation usually affects attach, restart, restore, or snapshot decryptability and may not immediately stop an already-running mounted database. For Connex secret-store credentials, revocation makes only those wrapped credentials unavailable. |

## Required Cross-Links

The following workstreams must point back to this matrix before making
encryption or key-custody claims:

- `SECURITY.md` / APPI security control disclosure ([#104]).
- `APPI_DPA_TEMPLATE.md` and security questionnaire boilerplate ([#93]/[#369]).
- At-rest encryption and CMK roadmap ([#92]).
- Dedicated per-organization database architecture ([#313]).
- Deployment architecture / on-prem planning ([#100]).
- Production storage encryption evidence ([#371]).
- Dedicated-tier CMK feasibility ([#376]).
- On-prem encryption runbook ([#373]).

## References

- MySQL InnoDB data-at-rest encryption: https://dev.mysql.com/doc/refman/8.0/en/innodb-data-encryption.html
- MySQL InnoDB data-at-rest encryption FAQ: https://dev.mysql.com/doc/refman/8.4/en/faqs-tablespace-encryption.html
- MySQL keyring components: https://dev.mysql.com/doc/refman/en/keyring.html
- MySQL keyring component installation: https://dev.mysql.com/doc/refman/en/keyring-component-installation.html
- MySQL file-based keyring component: https://dev.mysql.com/doc/refman/en/keyring-file-component.html
- MySQL backup and recovery overview: https://dev.mysql.com/doc/refman/8.0/en/backup-and-recovery.html
- MySQL `mysqldump`: https://dev.mysql.com/doc/refman/8.0/en/mysqldump.html

[#92]: https://github.com/itkla/connex/issues/92
[#93]: https://github.com/itkla/connex/issues/93
[#100]: https://github.com/itkla/connex/issues/100
[#104]: https://github.com/itkla/connex/issues/104
[#313]: https://github.com/itkla/connex/issues/313
[#369]: https://github.com/itkla/connex/issues/369
[#371]: https://github.com/itkla/connex/issues/371
[#373]: https://github.com/itkla/connex/issues/373
[#376]: https://github.com/itkla/connex/issues/376
