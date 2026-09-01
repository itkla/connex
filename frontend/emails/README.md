# Connex transactional emails (React Email)

Authoring workspace for Connex's transactional emails. Components here are the
**source of truth**; they render to inline-styled, email-client-safe HTML under
`backend/src/main/resources/templates/emails/*.html`, which the backend's
`EmailTemplateRenderer` fills in at send time.

This is a standalone package (not part of the Next.js app) so React Email's
dependencies never touch the app bundle. It is **build-time only** — nothing here
ships to any runtime.

## Templates

| Component | Rendered file | Sent by |
| --- | --- | --- |
| `Invite.tsx` | `invite.en.html` | `InviteEmailService` |
| `VerifyEmail.tsx` | `verify-email.en.html` | `SmtpRegistrationVerificationEmailService` |
| `PasswordReset.tsx` | `password-reset.en.html`, `password-reset.ja.html` | `SmtpPasswordResetEmailService` |
| `EmailChange.tsx` | `email-change.en.html`, `email-change.ja.html` | `SmtpEmailChangeEmailService` |
| `Test.tsx` | `test.en.html`, `test.ja.html` | `WorkspaceMailConfigService#sendTest` |
| `NotificationEmail.tsx` | `notification.en.html` | `EmailNotificationDispatcher` |
| `ReportDelivery.tsx` | `report-delivery.en.html`, `report-delivery.ja.html` | `ReportDeliveryScheduler` |
| `DocumentSignature.tsx` | `document-signature.en.html`, `document-signature.ja.html` | `SmtpDocumentSignatureEmailService` |

Every backend template is generated from this workspace. Do not hand-edit the
HTML under `backend/src/main/resources/templates/emails/`; `pnpm render`
overwrites it.

## Design system

`theme.ts` holds the tokens (they mirror `frontend/app/globals.css`: the neutral
ramp, the `#73d200` brand lime, the corner radius) and the progressive-enhancement
stylesheet. `Layout.tsx` holds the shell plus the shared blocks every template
composes from: `Heading`, `Subline`, `Lead`, `Panel`/`PanelRow`, `Cta`, `Divider`
and `Fallback`.

Conventions worth keeping:

- **The accent is for actions, not decoration.** Lime appears on the brand mark,
  the primary button, and the report figure rules. Nothing else.
- **Facts belong in a `Panel`, prose in a `Lead`.** Expiry windows, roles,
  addresses and document titles are panel rows, which keeps headlines short and
  stops caller-supplied strings from overflowing the heading.
- **Pass one interpolated string to `Lead`/`Heading`.** Multiple JSX children make
  React insert `<!-- -->` separators mid-sentence in the rendered HTML.
- **Inline styles must stand on their own.** The `<style>` block in `theme.ts`
  only adds dark-mode and narrow-viewport overrides; clients that strip head
  styles still get the intended light-mode design.

## Placeholders

The backend substitutes `{{token}}` markers (HTML-escaping each value). Each
component defaults its props to the matching `{{token}}` string, so a plain
render produces the template. Keep prop names in sync with the tokens the backend
services pass (e.g. `Invite` → `workspaceName`, `inviterName`, `role`, `acceptUrl`).

## Develop

```bash
pnpm install          # esbuild's build (needed by tsx) is pre-approved in pnpm-workspace.yaml
pnpm render           # compile the templates into backend resources
```

The live-preview script is currently unavailable because its CLI dependency is
not installed; restoration is tracked in GitHub issue #836.

## Regenerate the backend templates

```bash
pnpm render           # writes every registered backend email template
pnpm render -- test.ja.html  # writes only the named template
```

Commit both the components and the regenerated HTML. After changing a template,
re-render and confirm the `{{token}}` markers survived (`grep '{{' ...`) and that
they still match the `Map.of(...)` keys the sending service passes.

The renderer pretty-prints, so element content can wrap onto its own line. Backend
tests that assert on template output should normalise whitespace rather than pin
exact tag adjacency (see `ReportDeliverySchedulerTest#markup`).

## Notes

- `@react-email/components` currently carries a generic npm "no longer supported"
  deprecation notice; `pnpm audit` reports no vulnerabilities. It remains the
  standard React Email import and is build-only. Revisit if React Email
  consolidates the package.
- Password reset, email-change verification, SMTP test, and scheduled report
  delivery select the English or Japanese template from the recipient's persisted
  account locale. Invitation, registration verification, and notification emails
  retain their current English-only behavior.
