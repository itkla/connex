# Connex Secret Store Key Lifecycle Runbook

This runbook covers the central secret store used for never-searched integration
secrets: workspace SMTP passwords, organization SSO OIDC client secrets, SAML SP
private keys, and future credentials stored through `SecretStore`.

The secret store uses envelope encryption. Each row has its own data key, and
the configured key-encryption key wraps that data key. Operators rotate by
adding a new active key while keeping old key material in the keyring until all
rows have moved.

## Key Metadata

Every deployment key should have operator-owned metadata:

- `key-id`: stable identifier stored with each encrypted row.
- `version`: human rotation version, such as `1`, `2`, or `2026-07`.
- `algorithm`: current key wrap algorithm, `AES-GCM`.
- `owner`: accountable team or customer.
- `scope`: `instance` today; future dedicated-tier or on-prem CMK keys may use
  an organization/customer scope.
- `created-at`, `rotated-at`, `disabled-at`: operational timestamps.

Configure non-secret metadata under `connex.secret-store.metadata.<key-id>`.
Never store key material in metadata.

## Health Checks

Before onboarding a customer, confirm diagnostics are healthy:

- Workspace secrets: `GET /api/workspaces/{workspaceId}/secret-store/diagnostics`
  as a user with `WORKSPACE_SETTINGS`.
- Organization secrets: `GET /api/orgs/{orgId}/secret-store/diagnostics` as an
  org admin or owner.

The response is metadata-only. It must show `healthy=true`, `available=true`,
zero `missingKeySecrets`, zero `disabledKeySecrets`, zero `mismatchedSecrets`,
and zero `unsupportedAlgorithmSecrets`.

`staleSecrets` may be non-zero during a planned rotation while old keys remain
configured and readable. It should return to zero before removing old key
material.

## Normal Rotation

1. Generate a new 32-byte random AES key and Base64-encode it.
2. Set `CONNEX_SECRET_STORE_KEY_ID` to the new key id.
3. Set `CONNEX_SECRET_STORE_MASTER_KEY` to the new Base64 key.
4. Keep each prior key under `connex.secret-store.keys.<old-key-id>`.
5. Deploy. New writes use the new key id. Reads of old rows succeed through the
   keyring.
6. Leave `CONNEX_SECRET_STORE_LAZY_REWRAP_ENABLED=true` so successful reads
   opportunistically rewrap old rows.
7. To accelerate rotation, temporarily set
   `CONNEX_SECRET_STORE_BATCH_REWRAP_ON_STARTUP=true` and choose a bounded
   `CONNEX_SECRET_STORE_BATCH_REWRAP_LIMIT`.
8. Check diagnostics until `staleSecrets=0` across relevant workspaces and orgs.
9. Remove old key material only after diagnostics report no rows using it.

No plaintext export is required. Rewrap decrypts each secret only inside the
application process and writes a newly wrapped row to the active key id.

## Rotation Failure And Rollback

If the deployment cannot unwrap old rows:

1. Keep the app version deployed if it otherwise boots, but stop batch rewrap by
   setting `CONNEX_SECRET_STORE_BATCH_REWRAP_ON_STARTUP=false`.
2. Restore the previous `CONNEX_SECRET_STORE_KEY_ID` and
   `CONNEX_SECRET_STORE_MASTER_KEY`, keeping any new key in the keyring.
3. Check diagnostics. `mismatchedSecrets` means the configured key material does
   not match rows carrying that key id.
4. Do not delete old key material until all stale rows are rewrapped or the data
   owner accepts that affected secrets are unrecoverable.

If a row has already rewrapped to the new key before rollback, keeping the new
key in `connex.secret-store.keys.<new-key-id>` preserves access.

## Key Compromise

For suspected key compromise:

1. Add the compromised key id to `CONNEX_SECRET_STORE_DISABLED_KEY_IDS`.
2. Deploy immediately. Rows using that key id fail closed with a sanitized
   "encrypted secret unavailable" response.
3. Use diagnostics to list affected secret metadata by scope and purpose.
4. Rotate affected third-party credentials at the source system, then save the
   replacement through Connex so it is stored under the active key.
5. After replacement, verify diagnostics show zero `disabledKeySecrets`.
6. Remove the compromised key material from the keyring after all affected rows
   have been replaced or deleted.

Disabling a key before replacing rows intentionally makes those integration
secrets unavailable. That is the correct fail-closed posture for compromise.

## Lost Key Material

If key material is lost and no backup exists:

1. Do not attempt to reconstruct or export plaintext. It is not recoverable from
   the database.
2. Configure the active key correctly so new secrets can be stored.
3. Use diagnostics to identify rows with `missingKeySecrets`.
4. Ask the relevant administrator to re-enter or regenerate each affected
   credential in the upstream system.
5. Delete or overwrite unrecoverable rows after replacement.

## Customer-Owned Key Revocation Semantics

Current Connex SaaS uses instance-scoped secret-store keys. Future on-prem or
dedicated-tier customer-managed keys should follow the same semantics:

- Revoking a customer-owned key makes that customer's wrapped secrets
  unavailable immediately.
- Connex must fail closed and must not fall back to a platform key unless the
  customer explicitly configured that fallback.
- The unavailable data is limited to material encrypted under the revoked key:
  SMTP credentials, SSO OIDC client secrets, SAML SP private keys, and future
  never-searched credentials. Searchable CRM fields are not encrypted by this
  secret store.
- Restoring the exact key material with the same key id restores access. A new
  key cannot decrypt old rows; affected credentials must be re-entered or
  rotated upstream.

## Audit Expectations

Secret use, failed use, and rewrap operations are audited with scope, purpose,
secret id, and key ids where relevant. Audit entries never include plaintext,
ciphertext, wrapped data keys, or key material.
