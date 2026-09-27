# Breached-password screening

Connex screens every password before it reaches the password encoder. The production write paths
are public registration, permission-gated account creation, initial-owner bootstrap, and
forgot-password redemption. Invite acceptance only joins an existing account and does not set a
password. Connex currently has no authenticated password-change or administrative password-reset
endpoint; any future password write must use `PasswordCredentialService`, and an architecture test
fails if another production class calls the password encoder directly.

## Remote source

`CONNEX_BREACHED_PASSWORD_SOURCE=REMOTE` is the default. Connex computes the candidate's SHA-1
digest in process, sends only its first five hexadecimal characters to the fixed
`https://api.pwnedpasswords.com/range/` endpoint, and compares the 35-character suffix locally.
The request includes `Add-Padding: true` and a fixed Connex application identifier. The password,
complete digest, suffix, response rows, and match count are never logged, audited, or persisted.

The client refuses redirects and JVM proxy routing. Connection and read deadlines are two and
three seconds. A single request gets at most two attempts, at most four range requests may be in
flight, and admission is capped at twenty screening operations per second per backend replica. A
retried screening operation can make one additional outbound request. HTTP 429 is not
retried. An oversized or malformed range response is unavailable rather than an implicit clean
result. Unexpected redirects and non-rate-limit 4xx responses fail closed.

## Availability decisions

Availability behavior is fixed in code and has no disable setting.

| Credential write | HIBP timeout, 429, or 5xx | Offline file unavailable after startup |
|---|---|---|
| Registration, administrative account creation, bootstrap owner | Fail closed | Fail closed |
| Forgot-password reset for an account that is privileged anywhere | Fail closed | Fail closed |
| Forgot-password reset for a non-privileged account | Fail open, with a sanitized audit that commits atomically with the new password | Fail closed |

Remote availability events record only the flow, fixed reason classification, and decision. A
known breach is always rejected. Offline corruption or runtime replacement never fails open,
because it represents loss of the operator-controlled security source rather than a transient
third-party outage.

A fail-open decision is appended in the reset's own transaction just before it commits, so the new
password is never stored without it; a fail-closed refusal is appended independently after the
refused transaction rolls back. Neither is appended independently while the reset holds the account
row, which would wait on that lock when the redeemer is signed in as the account owner (see
[`backend/LOCKING.md`](backend/LOCKING.md#account-recovery-and-emailed-credential-tokens)).

## Length limit

The password encoder is BCrypt, which reads at most 72 bytes of input. New passwords are therefore
limited to 8–72 characters at the API boundary (the complexity pattern is ASCII-only, so characters
and bytes coincide), and `PasswordCredentialService` rejects any candidate over 72 UTF-8 bytes with
a `PASSWORD_TOO_LONG` field error before screening, so the screened bytes are always the stored
bytes. Sign-in keeps accepting up to 255 characters so credentials stored before the limit keep
working. The bootstrap owner path skips request validation; an over-long
`connex.bootstrap.password` is logged with the limit, never the value, and provisioning is skipped.

## Offline source for restricted egress

Air-gapped and restricted-egress installations set:

```dotenv
CONNEX_BREACHED_PASSWORD_SOURCE=OFFLINE
CONNEX_BREACHED_PASSWORD_OFFLINE_FILE=/run/connex-security/breached-passwords.txt
CONNEX_BREACHED_PASSWORD_OFFLINE_SHA256=REPLACE_WITH_64_HEX_CHARACTERS
```

There is no `DISABLED` source. An unsupported source value or an invalid offline corpus prevents
startup.

The offline file is a complete local breached-password set represented as uppercase SHA-1 values,
strictly sorted and unique, with exactly one 40-character value plus LF per record. It must be a
regular, non-symbolic file. Connex streams the file at startup to validate its format and configured
SHA-256 checksum, retains that verified file handle, then uses fixed-width binary search through
the same handle without loading the corpus into heap. A runtime size, timestamp, file-identity, or
symlink change fails closed until restart.

For the Docker deployment bundle, mount the operator-managed corpus into the backend container
read-only with a Compose override:

```yaml
services:
  backend:
    volumes:
      - /srv/connex/security/breached-passwords.txt:/run/connex-security/breached-passwords.txt:ro
```

Stage a new corpus under a different host filename, calculate and configure its SHA-256, atomically
replace the mounted source, and restart the backend. Do not edit the mounted file in place: a
running process treats any change as loss of source integrity.
