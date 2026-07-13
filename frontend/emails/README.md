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
| `PasswordReset.tsx` | `password-reset.en.html` | `SmtpPasswordResetEmailService` |
| `EmailChange.tsx` | `email-change.en.html` | `SmtpEmailChangeEmailService` |
| `Test.tsx` | `test.en.html` | `WorkspaceMailConfigService#sendTest` |
| `NotificationEmail.tsx` | `notification.en.html` | `EmailNotificationDispatcher` |
| `ReportDelivery.tsx` | `report-delivery.en.html`, `report-delivery.ja.html` | `ReportDeliveryScheduler` |

## Placeholders

The backend substitutes `{{token}}` markers (HTML-escaping each value). Each
component defaults its props to the matching `{{token}}` string, so a plain
render produces the template. Keep prop names in sync with the tokens the backend
services pass (e.g. `Invite` → `workspaceName`, `inviterName`, `role`, `acceptUrl`).

## Develop

```bash
pnpm install          # esbuild's build (needed by tsx) is pre-approved in pnpm-workspace.yaml
pnpm dev              # live preview at http://localhost:3000
```

## Regenerate the backend templates

```bash
pnpm render           # writes ../../backend/src/main/resources/templates/emails/*.en.html
```

Commit both the components and the regenerated HTML. After changing a template,
re-render and confirm the `{{token}}` markers survived (`grep '{{' ...`).

## Notes

- `@react-email/components` currently carries a generic npm "no longer supported"
  deprecation notice; `pnpm audit` reports no vulnerabilities. It remains the
  standard React Email import and is build-only. Revisit if React Email
  consolidates the package.
- Per-recipient locale selection remains future work because user profiles do not
  currently persist a locale. Scheduled reports include English and Japanese
  templates, while delivery follows the existing English default.
