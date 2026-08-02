# Connex — Internal Operations Runbook

> **Status:** Internal operator reference for the Connex operations team. It describes what the code
> at this commit actually does, not what the product roadmap intends. Where this runbook and a
> signed customer agreement disagree, **the agreement governs**.
> **Access and encryption claims** must come from [ENCRYPTION_GUARANTEE_MATRIX.md](ENCRYPTION_GUARANTEE_MATRIX.md);
> do not paraphrase them from here.
>
> **Owner:** {{SECURITY_OWNER}} · **Last reviewed:** {{DATE}} · Review at least every 6 months and
> after any change to provisioning, teardown, or the fail-closed startup set.

Related: [DEPLOYMENT.md](DEPLOYMENT.md) for the operator deployment runbook,
[DEPLOYMENT_EDITIONS.md](DEPLOYMENT_EDITIONS.md) for profile semantics,
[CONTROLLED_PARTNER_ADMISSION.md](CONTROLLED_PARTNER_ADMISSION.md) for the per-engagement checklist
that consumes this document.

## Admin-only provisioning

Connex is provisioned by operators, not by the people who sign up. That posture is produced by
**two independent flags**, and the common misreading is to assume one of them does the other's job.

| Flag | Env var | Default | What it actually gates |
|---|---|---|---|
| `connex.signup.mode` | `CONNEX_SIGNUP_MODE` | **`open`** | Whether **self-service account registration** is accepted at all |
| `connex.workspaces.allow-self-service-creation` | `CONNEX_WORKSPACES_ALLOW_CREATION` | **`false`** | Whether a user may **create a workspace** — both on register and via `POST /api/workspaces` |

**With shipped defaults, self-serve registration is open and workspace creation is not.** Anyone who
can reach the instance can create an *account*; that account lands with **zero workspaces**. Nothing
in the product is reachable from there. `application.yml` states the intent plainly: workspace
creation "defaults to invite-only for the guided-pilot GTM; the dev profile re-enables
self-service."

The two refusals are distinct and produce different messages:

```text
Self-service registration is disabled on this instance     # signup mode is not `open`
Workspace creation is disabled on this instance            # allow-self-service-creation is false
```

`AuthService.register` only auto-provisions a workspace when the self-service flag is on. When it is
off, the frontend sees an empty workspace list and redirects the new account to `/onboarding`, which
offers exactly two moves: create a workspace (which will `403` on this posture) or paste an invite
link. **The invite is the real path — but do not tell anyone to paste a shareable link into that
box.** It parses only the marker `/invite/` and then routes to `/invite/{token}`, so an emailed
invite URL works while a **shareable link (`/invite-link/{token}`) does not**: the marker never
matches, the parser keeps the first segment of the pasted URL, and the tester lands on
`/invite/https:` and is told the invite does not exist. Pasting only the bare token of a shareable
link fails the same way, on the wrong route. **Have them open the shareable URL directly instead**
(that is exactly the URL Settings → Members copies). This mismatch is **tracked**; until it is
fixed, the paste box is for emailed invites only.

**This does not vary by deployment profile in code.** Neither flag appears in
`FORBIDDEN_KEYS_BY_PROFILE`, so no profile forces either value. It varies only by **env template**:
both [`deploy/silo.env.example`](../deploy/silo.env.example) and
[`deploy/onprem.env.example`](../deploy/onprem.env.example) set
`CONNEX_WORKSPACES_ALLOW_CREATION=false` — but
[`deploy/eval.env.example`](../deploy/eval.env.example) declares
`CONNEX_DEPLOYMENT_PROFILE=silo` and sets `CONNEX_WORKSPACES_ALLOW_CREATION=true` (it also activates
the `dev` Spring profile), so "it is a silo template" is not evidence that self-service is off.
An instance configured by hand rather than from a template gets whatever the operator set — check
it, do not assume it.

### `invite` and `domain` signup modes are declared, not implemented

`connex.signup.mode` documents three values — `open`, `invite`, `domain` — and
`CONNEX_SIGNUP_ALLOWED_DOMAINS` exists alongside them. **Only `open` is implemented.** The guard is a
single comparison against `open` — case-insensitive and trimmed, so `OPEN` and `" Open "` also open
registration — and **any** other value, including `invite`, `domain`, or a typo, simply refuses
self-service registration outright. There is no invite-gated registration flow and no
domain-allowlist enforcement behind those names. Set `CONNEX_SIGNUP_MODE` to something other than
`open` when you want registration closed — but do not describe the instance as running "invite mode"
as if that were a distinct behaviour, and do not rely on `CONNEX_SIGNUP_ALLOWED_DOMAINS` to restrict
anything today.

### The two non-HTTP bypass paths

Workspace creation has exactly two paths that are not the self-service HTTP endpoint. Both are
audited; neither is exposed to an anonymous caller.

**1. Bootstrap runner — the first owner on a fresh instance.**

`BootstrapRunner` runs on `ApplicationReadyEvent` and calls a package-private
`WorkspaceService.createWorkspaceForBootstrap` through `AuthService.provisionBootstrapOwner`,
bypassing the self-service flag entirely.

```dotenv
CONNEX_BOOTSTRAP_ENABLED=true
CONNEX_BOOTSTRAP_USERNAME=
CONNEX_BOOTSTRAP_EMAIL=
CONNEX_BOOTSTRAP_PASSWORD=
CONNEX_BOOTSTRAP_DISPLAY_NAME=
CONNEX_BOOTSTRAP_TIMEZONE=UTC
```

Its behaviour matters more than its configuration:

- It is **gated on `connex.bootstrap.enabled`, which the `saas` profile forbids** — setting it
  `true` under `saas` fails startup. It is available on `silo` and `on-prem`.
- It is a **no-op as soon as any login-capable user exists** (`countUsers() > 0`), so leaving it on
  does not repeatedly provision. Turn it off anyway; `onprem.env.example` ships it `false` with the
  note "set false after the first login exists".
- It **never aborts startup**. Misconfiguration is logged and skipped. A blank username, email, or
  password produces a `WARN` and no owner; a thrown exception produces an `ERROR` and no owner. In
  both cases the instance boots normally with **no owner at all**. Grep the boot log rather than
  assuming it worked — and match **both** phrasings, because the blank-credential `WARN` reads
  "Bootstrap is enabled but username, email, and password are not all set" and contains no
  "Bootstrap owner" substring:

  ```bash
  journalctl -u <backend-unit> | grep -iE 'Bootstrap owner|Bootstrap is enabled'
  ```

- The bootstrap password is read from the environment and is **never logged**.
- The bean is conditional on `connex.maintenance.mode` being `off`.

**2. Admin user creation.** `UserController.createUser` calls `AuthService.register` directly. It is
permission-gated and **unaffected by `connex.signup.mode`** — an admin can create accounts on an
instance where self-service registration is refused. It still only auto-creates a workspace when
`allow-self-service-creation` is on, so on the normal posture an admin-created user also lands with
zero workspaces and must be invited into one.

**It cannot bootstrap an empty instance.** `POST /api/users` calls the no-argument
`requirePermission(MEMBER_MANAGE)`, which resolves against the caller's **active workspace** — and
with no workspace resolved it falls back to the caller's default membership and throws
`The authenticated user does not belong to a workspace`. The caller must already be an admin
somewhere, so this path extends an existing tenant; it never creates the first one.

### First owner on a `saas`-shaped instance — the only working path

Put these three facts together and one deployment shape has no obvious way in: the bootstrap runner
is **forbidden under `saas`**, admin user creation needs a pre-existing membership, and workspace
creation is refused while `allow-self-service-creation` is `false`. On `silo` and `on-prem` use the
bootstrap runner. On `saas`, the working sequence is a **temporary, deliberate flag flip**:

1. Confirm `CONNEX_SIGNUP_MODE` is `open` (the shipped default).
2. Set **`CONNEX_WORKSPACES_ALLOW_CREATION=true`** and restart. The flag is bound at startup, so a
   restart is required — twice, once each way.
3. Have the intended first owner **self-register in the app**. Registration mints their user, and
   because no tenant context is resolved for a fresh account, `provisionWorkspace` creates a **new
   organization**, records them as its **founding `org_member` owner**, and adds them as workspace
   `owner`. That founding row is what later authorizes export and teardown.
4. Set **`CONNEX_WORKSPACES_ALLOW_CREATION=false`** and restart again.
5. Verify: the account appears as `owner` in Settings → Members **and** as organization `owner` at
   **Organization → Members**. Everyone else joins by invite from here.

**Keep the open window short and record it.** Between steps 2 and 4 any account that can reach the
instance can register *and* provision itself an organization, so do this before the instance is
publicly reachable, or behind an access control you already trust.

## Workspace policy

### Roles

There are three built-in workspace roles — `MEMBER`, `ADMIN`, `OWNER`, in ascending privilege order.
The role is stored lowercase on `workspace_member.role`. Their permission bundles are
**`private static final` Java sets and are not runtime-editable**; there is no migration that seeds
them and no API that changes them.

| | `MEMBER` | `ADMIN` | `OWNER` |
|---|---|---|---|
| Grantable permissions held | 25 | 42 | 43 (all) |
| Record work — create/update/delete on companies, people, deals, activities, notes, tasks, attachments | yes (except `COMPANY_DELETE`) | yes | yes |
| `REPORT_READ/CREATE/UPDATE/DELETE`, `GOAL_READ`, `CAMPAIGN_VIEW` | yes | yes | yes |
| `COMPANY_DELETE`, `PIPELINE_MANAGE`, `TAG_MANAGE`, `PRODUCT_MANAGE`, `GOAL_MANAGE` | — | yes | yes |
| `DOCUMENT_MANAGE`, `DOCUMENT_APPROVE`, `CUSTOM_FIELD_MANAGE`, `SHARE_MANAGE`, `RULE_MANAGE` | — | yes | yes |
| `MEMBER_MANAGE`, `WORKSPACE_SETTINGS`, `AUDIT_READ` | — | yes | yes |
| `CAMPAIGN_MANAGE`, `CAMPAIGN_SEND`, `CONSENT_MANAGE` | — | yes | yes |
| **`AI_USE`** | **—** | **yes** | **yes** |
| **`ROLE_MANAGE`** | — | **—** | **yes** |

Three things in that table catch operators out:

- **`ADMIN` differs from `OWNER` by exactly one permission: `ROLE_MANAGE`.** Custom-role CRUD and
  custom-role assignment are therefore owner-only by default.
- **`MEMBER` does not have `AI_USE`.** A plain member sees no AI features at all, on any instance.
  This is the single most common "AI is broken" false report.
- **`MEMBER` does not have `AUDIT_READ` or `REPORT_*` management beyond its own bundle** — but it
  *does* have full report CRUD and record CRUD. Members are not read-only.
- **`MEMBER` holds `CAMPAIGN_VIEW` but not `CAMPAIGN_MANAGE`, and the campaign UI does not respect
  that.** Campaign detail renders its **Delete** button unconditionally, so **every default member**
  sees a control that returns `403` when pressed — the exact case in
  [#959](https://github.com/itkla/connex/issues/959). Recognize this report on sight ("Delete on a
  campaign does nothing / says forbidden") instead of investigating the instance: the role is
  correct, the button should not be there. The fix is a frontend permission guard, not a role
  change; do not grant `CAMPAIGN_MANAGE` to work around it.

Two permission constants exist but are **inert and non-grantable**: `SSO_MANAGE` (SSO is authorized
org-side instead) and `WORKSPACE_DELETE` (no endpoint uses it). They are retained only so stored
custom-role rows still parse. Do not build a process around either.

### Custom roles

Custom roles are runtime-editable and are the escape hatch when a built-in bundle does not fit.

```text
GET    /api/workspaces/{workspaceId}/roles
GET    /api/workspaces/{workspaceId}/roles/built-in
POST   /api/workspaces/{workspaceId}/roles
PUT    /api/workspaces/{workspaceId}/roles/{roleId}
DELETE /api/workspaces/{workspaceId}/roles/{roleId}
```

- All five require **`ROLE_MANAGE`** — owner-only by default. Mutations additionally require step-up.
- UI: **Settings → Roles**.
- **A custom role replaces the built-in bundle entirely.** A member with a custom role assigned gets
  that role's exact permission set; the built-in bundle for their `MEMBER`/`ADMIN`/`OWNER` role no
  longer applies. Assigning an under-specified custom role is how people accidentally remove access.
- Deleting a custom role nulls the reference, so its members fall back to their built-in role rather
  than losing everything.
- **No one can confer a permission they do not themselves hold.** This cap is enforced on role
  **create** and **update**, on custom-role assignment, and on built-in role change. Non-grantable
  values are rejected on input and silently dropped on read.
- **Deletion is the exception, and it is not a cap at all.** `deleteRole` runs the same
  authorization lock but passes an **empty** requested-permission set into the grantable check, so
  the check iterates nothing: **any `ROLE_MANAGE` holder can delete a custom role that grants
  permissions they do not hold.** The blast radius is a privilege *reduction* — deleted members fall
  back to their built-in bundle — but do not describe deletion to a customer as
  permission-capped, and treat "someone deleted the role that held our elevated access" as a
  reachable state for any `ROLE_MANAGE` holder.
- `GET /api/permissions` lists grantable names; `GET /api/permissions/effective` returns the calling
  member's effective set — that endpoint is the fastest way to settle a "why can't I do X" ticket.

### Membership states

There are exactly **two** states on `workspace_member.status`: **`active`** and **`pending`**. There
is no suspended, disabled, or removed state — removal deletes the row.

| State | Can see workspace content? | Notes |
|---|---|---|
| `active` | **Yes** | Every auth and RBAC read filters on `status = 'active'`. |
| `pending` | **No** | Tenant resolution rejects with `Not a member of workspace …` and the effective permission set is empty. |

`pending` rows are still visible in the settings roster (so admins can see who has not accepted), in
the user's own pending list (`GET /api/workspaces/pending`), and to @-mention lookup. That
mention-visibility helper is explicitly documented as **never to be used for auth or RBAC**.

**Which path produces which state is not intuitive:**

| How the person joins | Resulting state |
|---|---|
| Redeems an emailed token invite | **`active` immediately** |
| Redeems a shareable invite link | **`active` immediately** |
| Added by an admin when the email **already belongs to a Connex user** | **`pending`** — they must accept |
| **Emailed an invite** at an address that **already belongs to a Connex user** | **`pending`** — and **no email is sent** |

**That last row is the trap.** `createInvite` checks the address first: when it already belongs to a
Connex account it silently switches to `addPendingMember` and returns a member instead of an invite
— **no token row, no email, no link to hand over**. The invitee gets only an in-app
`workspace.join` notification and a row in their own pending list, neither of which they will see
until they happen to sign in. From the admin's side the roster just shows `pending`, which looks
identical to an invite that was emailed and ignored. **So a `pending` row does not mean "they
ignored the email" — on this path there was never an email.** Chase it by telling the person
directly to sign in and accept at **Settings → Membership**.

`pending → active` happens only via the invitee's own `POST /api/workspaces/{id}/accept`, which
re-applies the organization email-domain ceiling on activation. Declining deletes the row. A user
whose only memberships are pending can log in but has no accessible workspace.

Note that `workspace_invite.status` (`pending` / `accepted` / `revoked`) is a **different** thing
from membership status; invite links use a separate revoked flag. Do not conflate them when reading
the roster.

### Who can invite

Every membership mutation gates on **`MEMBER_MANAGE`** — owner and admin by default, never a plain
member — **and every one of them additionally requires step-up**:

| Action | Endpoint | Permission |
|---|---|---|
| Create / revoke token invites | `POST`/`DELETE` `/api/workspaces/{id}/invites` | `MEMBER_MANAGE` |
| Create / revoke invite links | `POST`/`DELETE` `/api/workspaces/{id}/invite-links` | `MEMBER_MANAGE` |
| Add an existing user directly | `POST /api/workspaces/{id}/members` | `MEMBER_MANAGE` |
| Remove a member | `DELETE /api/workspaces/{id}/members/{userId}` | `MEMBER_MANAGE` |
| Change a built-in role | `PATCH /api/workspaces/{id}/members/{userId}` (`role`) | `MEMBER_MANAGE` |
| Assign a custom role | `PATCH /api/workspaces/{id}/members/{userId}` (`roleId`) | **`ROLE_MANAGE`** |
| Create a brand-new user account | `POST /api/users` | `MEMBER_MANAGE` |

Reading the roster (`GET /api/workspaces/{id}/members`) needs only membership, not `MEMBER_MANAGE`.
**Listing invites is permission-only** — `GET /api/workspaces/{id}/invites` and
`GET /api/workspaces/{id}/invite-links` need `MEMBER_MANAGE` but **no step-up**. Only create and
revoke are step-up gated, so an admin without a passkey can still see what is outstanding; they
simply cannot issue or withdraw anything.

Guardrails worth knowing before you promise something to a customer:

- **An invite can never grant `owner`.** Both token invites and invite links reject anything other
  than `member` or `admin`: `Role must be member or admin`. Owner is conferred only by an existing
  owner changing a member's role.
- **Promoting to owner, or demoting an owner, requires the actor to hold the `OWNER` role itself** —
  not merely `MEMBER_MANAGE`.
- **The last owner cannot be demoted or removed**: `A workspace must keep at least one owner`.

### Owner, and the organization layer above it

**There is no `workspace.owner_id` column.** Workspace ownership is just the `owner` role on an
active membership row.

Organization ownership is a **separate** concept: `org_member.org_role` is constrained to `owner` or
`admin`, deliberately so that workspace roles cannot reach organization-scoped configuration. The
founding owner is set when the organization is created.

This distinction decides who can perform the offboarding calls:

| Operation | Required |
|---|---|
| Tenant export | Organization **administrator** + step-up |
| Workspace teardown, organization teardown | Organization **owner** + step-up + slug confirmation |
| SSO configuration | Organization admin (org-side, **not** the inert `SSO_MANAGE` permission) |

**No `Permission` constant gates teardown at all** — do not go looking for one.

**How an `org_member` row comes to exist — there are exactly two ways.** This is the question every
offboarding rehearsal actually turns on, and neither way is a workspace operation:

| Path | Who it produces |
|---|---|
| **Founding owner at organization creation** — `provisionWorkspace` mints an organization whenever the creator has no administrative tenant context, and calls `addFoundingOwner` | The first `owner`, set once, automatically |
| **Organization → Members** — `POST`/`PUT` `/api/orgs/{orgId}/members`, **org-owner-only plus step-up**, target must already be a Connex account (`No Connex account uses that email address`) | Every subsequent `owner` or `admin` |

> **A workspace owner who joined by invite has no `org_member` row at all** — invite redemption and
> `addMember` write workspace membership only. They will pass every workspace check, look like the
> most senior person in the tenant, and then fail **both** offboarding calls with
> `Organization administrator access required` / `Organization owner access required`.

So designate the organization owner explicitly at onboarding: either the partner's intended owner is
the account that **created** the organization, or an existing org owner adds them at
**Organization → Members**. An organization always keeps at least one owner
(`An organization must keep at least one owner`), so the founding row cannot be stranded by a later
demotion.

### Step-up requires a passkey — plan for this

"Step-up" is not a password re-prompt. `requireRecentAuthentication` checks for a WebAuthn step-up
stamp on the current session, bound to the same user id, inside the recent-authentication window
(`CONNEX_RECENT_AUTHENTICATION_WINDOW`, default **10 minutes**).

**Any login other than a passkey login clears that stamp** — password, SSO, SSO-link, and the
auto-login that runs after registration all route through the one session ceremony
(`AuthService.establishAuthenticatedSession`), which removes it. Only a passkey login or the
dedicated step-up ceremony (`POST /api/auth/webauthn/step-up/options` →
`POST /api/auth/webauthn/step-up`) sets it. Therefore:

> **A user with no registered passkey cannot complete any step-up-guarded operation** — creating an
> invite, changing a role, editing workspace mail settings, exporting a tenant, or tearing one down
> — **until they enrol one.**

Those examples are illustrative, not exhaustive. Step-up is called from 44 sites across 20 classes, so
an administrator without a passkey will also hit it on organization member management, SSO connection
setup, allowed-domain changes, the organization AI provider, delivery and connector configuration,
APPI incident records, data-subject-request handling, and connecting or disconnecting their own
Google or Microsoft account. Treat "any administrative write outside ordinary CRM records" as the
working rule rather than memorising the list.

#### Enrolling a first passkey — the unblock is self-service

The barrier is one extra ceremony, not a dead end. **First-passkey enrolment is not itself gated on
having a passkey**, so a stuck user unblocks themselves:

1. Sign in normally (password or SSO).
2. Enrol a passkey at **Account → Security** (`/account/security`).
3. Retry the operation — the passkey now satisfies step-up.

`GET /api/auth/webauthn/register/requirements` tells the client which proof the account owes;
`AuthService.requireFirstPasskeyBootstrapAuthentication` then takes one of exactly two paths:

| Account type | What authorizes the first passkey |
|---|---|
| Has a password | **The current password** (`requireCurrentPassword`) — the UI asks for it in a dialog. It is rate-limited like a login. |
| Passwordless (SSO-only) | **A freshly established session** (`requireFreshAuthenticatedSession`) — the *login* timestamp must be inside the same recent-authentication window. |

**Say the consequence out loud, because it is the opposite of what "step-up" sounds like: for a
password-backed account, step-up is not a second factor.** Whoever holds the password can sign in,
enrol their own passkey, and step up — three requests, no other credential — so a stolen password
yields full administrative capability, including tenant export and teardown. Step-up buys
phishing-resistant confirmation for accounts that are already passkey- or SSO-backed; it does not
add a factor on top of a password. Where that matters contractually, enforce SSO for the account
(`SsoEnforcedException` refuses password login) rather than relying on step-up.

**The passwordless 10-minute trap.** An SSO-only owner who has been signed in for longer than the
recent-authentication window **cannot bootstrap a passkey at all**:
`requireFreshAuthenticatedSession` tests the **login** timestamp against that same window, and its
refusal is a plain `ForbiddenException("Recent sign-in required")` with **no machine-readable
code** — so the frontend's code-based step-up handling never fires and the user sees only an
unexplained `403`. **The fix is to sign out, sign back in, and enrol immediately.** Brief SSO-only
partner owners on this before they need it; nothing in the UI says it.

The step-up failure itself is a `403` with a machine-readable code:

```json
{ "code": "RECENT_AUTHENTICATION_REQUIRED", "message": "Recent WebAuthn authentication required" }
```

**The frontend turns that into a passkey prompt only on mutating requests.** The retry helper in
`app/lib/api.ts` refuses to prompt unless the request was a mutation, so a **`GET`** that needs
step-up — and the tenant export is a `GET` — returns a bare `403` with no prompt and no in-app way
to recover. See [Offboarding](#offboarding) for the procedure that works around it.

Operationally: **make sure the customer's organization owner registers a passkey during onboarding,
not on the day they need to offboard.** Setting the window to zero or a negative value disables
step-up entirely — do not do that to work around a missing passkey.

## Tester onboarding — install and invite to first trusted insight

### Step 1 — get the tester an account and a workspace

The workspace must exist first (see provisioning above). Then choose the path that matches the
instance's mail posture.

**Before choosing a path, check that the tester can even get an account.** Redeeming an invite is
**not** a registration path: both `/invite/{token}` and `/invite-link/{token}` redirect an anonymous
visitor to **`/auth/login`** (never to `/auth/register`), and the accept endpoints act on the
signed-in user. So the tester must already have an account and be signed in when they open the link.

| Instance posture | How the tester gets an account |
|---|---|
| `CONNEX_SIGNUP_MODE=open` (shipped default) | They self-register, then open the invite. Their account lands with zero workspaces; the invite supplies the workspace. |
| **Signup mode anything else** | **They cannot self-register at all.** An admin must create the account first with `POST /api/users` — in the UI that is the **Users** page (`/users`), not Settings → Members — and then invite it. An invite link on a closed-signup instance is otherwise a dead end. |

Then choose the delivery path that matches the instance's mail posture.

| Situation | Path |
|---|---|
| Mail is configured and working | Emailed invite — `InviteService.createInvite` |
| **`connex.mail.enabled=false`, or mail is unproven** | **Shareable invite link — `InviteLinkService`** |
| The tester already has a Connex account on this instance | `addExistingMember` — adds them directly, no email round-trip, but lands them **`pending`** until they accept |

**Use the shareable link by default during a pilot.** The emailed invite is dispatched through
`MailService.sendForWorkspace`, which is `@Async` and reports nothing to the caller either way. Two
distinct silences, and the second is the common one on a pilot instance:

- a delivery attempt that throws is logged at **`ERROR`** ("Failed to send email to …") and swallowed;
- **no usable SMTP configuration at all** is logged only at **`WARN`** ("Email to … not sent: no
  usable SMTP configuration") and skipped before anything is attempted.

The invite row is created either way; `InviteEmailService` is explicit that "a mail outage never
blocks creating the invite. When no sender is configured the invite is still created; only the email
is skipped." From the operator's side both are indistinguishable from success. The link path removes
the ambiguity: you hold the credential and deliver it yourself.

**Also remember the existing-account branch:** emailing an invite to an address that already belongs
to a Connex user sends **no mail at all** and produces a `pending` member instead — see "Membership
states" above.

Shareable links are created in the UI at **Settings → Members**, or directly:

```text
POST   /api/workspaces/{workspaceId}/invite-links     body: { role, expiresInDays, maxUses }
GET    /api/workspaces/{workspaceId}/invite-links
DELETE /api/workspaces/{workspaceId}/invite-links/{linkId}
```

- Creation and revocation require the `MEMBER_MANAGE` permission **and step-up**, which means the
  operator issuing the link needs a **registered passkey** — a password-only session cannot create
  an invite link at all. See "Step-up requires a passkey" above.
- `expiresInDays` defaults to **14** when omitted or non-positive. The role is capped to `member` or
  `admin`; a link can never grant owner.
- The tester redeems at `/invite-link/{token}` — `GET /api/invite-links/{token}` previews,
  `POST /api/invite-links/{token}/accept` joins and sets the active-workspace cookie. Redemption
  makes them **`active` immediately**; there is no separate acceptance step to chase.
  **Deliver the whole URL** (`https://<host>/invite-link/{token}`) and tell them to open it. It is
  the one thing the `/onboarding` paste box cannot handle — see "Admin-only provisioning" above.
- Emailed invites use the parallel `/invite/{token}` route and are built from
  `connex.mail.app-base-url`, **never** from a request header. If that base URL is wrong, every
  emailed invite link is wrong. Their expiry is **14 days, hardcoded in the mapper SQL** — no
  property overrides it — and unlike a shareable link each one is **single-use and bound to the
  invited address**: a different signed-in user gets
  `This invite was sent to a different email address`, and a second redemption gets
  `This invite is no longer available`.
- **Tokens never reach the logs** — there is no request/access logging of paths in the backend at
  all, error-report paths are redacted by `RequestPathRedactor`, and the invite email itself is
  never logged. But a "lost" link does **not** need reissuing: the raw token is returned on create
  and is listed again by `GET /api/workspaces/{id}/invites` and
  `GET /api/workspaces/{id}/invite-links` (`MEMBER_MANAGE`, no step-up). Read it back and re-send.

**Caveat.** If `connex.registration-verification.enabled=true`, joining a domain-restricted
workspace through a shareable link additionally requires a verified email address — which needs the
verification mail flow to actually work. On an instance without working mail, leave registration
verification off or use `addExistingMember`.

### Step 2 — give the workspace something to be intelligent about

A brand-new workspace is empty, and Connex's headline surfaces are all derived from logged
interactions. Two options:

**Seeded demo fixtures.** The deterministic seeder is the fast path to a workspace that looks like a
real one. Full documentation is in [VOLUME_SEEDER.md](VOLUME_SEEDER.md); the operational contract
you must not violate:

```bash
CONNEX_DB_URL='jdbc:mysql://127.0.0.1:3313/connex_seeder?createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&sslMode=DISABLED' \
CONNEX_DB_USERNAME=connexuser \
CONNEX_DB_PASSWORD=connexpass \
bash gradlew seedData \
  -PseederProfile=small \
  -PseederSeed=853 \
  -PseederWorkspaces=1 \
  -PseederAnchorDate=2026-01-15
```

- Run from `backend/`. Inputs are `-PseederProfile=small|volume` (default `small`),
  `-PseederSeed=<long>` (default `853`), `-PseederWorkspaces=<1..100>` (default `1`),
  `-PseederAnchorDate=YYYY-MM-DD`, `-PseederAllowRemoteHost=true|false` (default `false`).
- **It must target a dedicated, disposable schema, and the name is part of the contract.** The
  catalog must **start with `connex_seed` or `cnx_seeder_`** (`must name a dedicated seeder
  catalog`), so a perfectly disposable schema called `demo_seed` is still refused. The two protected
  catalogs `connex_pub` and `connexdb` are refused outright, at startup and again against the
  effective datasource at run time. **Never point it at `connex_pub` or any staging or customer
  schema.**
- **Host rule: numeric loopback.** `127.0.0.1`, `::1`, and `0:0:0:0:0:0:0:1` pass. The hostname
  **`localhost` is refused even with `-PseederAllowRemoteHost=true`** (`must use a numeric loopback
  address`) — a detail that costs people ten minutes. Any other host needs
  `-PseederAllowRemoteHost=true` explicitly.
- **It is one-shot.** Workspace and organization slugs and seeded **usernames and user emails** are
  deterministic, so a second run **with the same seed** against a seeded schema fails on unique
  constraints **by design**. (Seeded *person* emails are fixtures, repeat across workspaces, and are
  not unique-constrained — do not use them to reason about collisions.) Each workspace commits in
  its own transaction, so a mid-run failure leaves the earlier workspaces committed: drop and
  recreate the schema, never resume.
- **`connex.deployment.profile` must be unset** for a seeder run — the seeder validator refuses a
  set profile, because a fixture-loading run is not a deployment. This is why `seeder` is exempt
  from the mandatory-profile check.
- **All seeded users share one precomputed BCrypt hash whose plaintext is `seeder-password`.** It is
  in the repository. Treat any seeded schema as public. It is a demo and CI fixture, never a
  customer environment.
- Seeding writes through the same MyBatis inserts but does **not** call entity services, rule
  triggers, notification publishers, or audit publishers — so a seeded workspace generates no
  automation and no notification traffic, and writes **no audit rows at all**: `audit_log` is empty
  on a seeded schema. That is expected, and it means a seeded workspace is useless for demonstrating
  the audit trail.
- **The anchor date is not an upper bound.** Interactions are spread backwards from it, but open
  deals are given expected close dates up to **60 days after** the anchor, so a seeded workspace has
  a forward-looking pipeline as well as a history. Pick an anchor with that in mind when you are
  demonstrating forecast or close-date behaviour.

**Real import.** For a partner evaluating on their own data, import instead of seeding, and agree
field mapping and data ownership up front.

### Step 3 — what "first trusted insight" actually requires

Relationship warmth is **computed on read** from logged interactions. There is no scheduled scoring
job to wait for and no backfill to trigger — but equally, **nothing appears until interactions
exist**. The model (`warmth-v1`) is a recency-decayed, intent-weighted sum of touches, weighted
meeting > call > email > note > task, with a **30-day half-life**, squashed to 0–100.

| Surface | What the tester must do first |
|---|---|
| Warmth bands on contacts/companies, relationship map colouring | Log real interactions. Bands are hot ≥ 60, warm ≥ 35, cool ≥ 15. With a 30-day half-life, a single old touch will not read hot. |
| Cooling / decay signals on the dashboard | Accumulate history. Cooling compares a **21-day recent window** against a **120-day prior window**, so a workspace younger than roughly four months cannot produce a meaningful cooling signal from live data. |
| Warm-intro paths | Populate the relationship graph — contacts, companies, employment history, deal-contact links. A sparse graph legitimately returns nothing. |
| Reports | Enough records in the reporting window for the figures to be non-trivial. |
| Deal briefs, risk rationale, intro rationale, report narrative, business-card extraction | **AI, which is off by default — see below.** |

This is why seeded fixtures matter for a demo: the seeder spreads interactions across the **prior 18
months** relative to `-PseederAnchorDate`, which is exactly the history that warmth and cooling need
and that a two-day-old pilot workspace does not have.

**Be honest about AI with testers.** Two independent things must be true before a tester sees any AI
feature:

1. **The instance kill switch must be on.** `CONNEX_AI_ENABLED` defaults to **`false`**. When it is
   off, `AiFeatureGate` denies before it even checks provider readiness, and every AI endpoint
   returns `403 Forbidden` with the body `AI features are not available`.
2. **The tester's workspace membership must carry the `AI_USE` permission.** The gate checks it per
   actor, per workspace. **A member without `AI_USE` sees nothing AI-related even on a fully
   configured instance** — and will report it as "the AI features are broken". Check the permission
   before debugging the provider.

Beyond those, the **organization** must have an enabled, fully configured BYOP provider — AI is
bring-your-own-provider configured per organization, not instance-wide, and it is not part of the
`Capability` registry. Note also that flipping `CONNEX_AI_ENABLED=true` enables **all five** AI
features unless each is individually set to `false`; an absent per-feature entry defaults on.

## Feedback and incident channels

### What the user is actually holding

This is the single most common support-flow mistake, so internalize it before the first ticket:

| The user is looking at | They can quote | Where it comes from |
|---|---|---|
| A broken **page** (render/boundary failure) | **`Reference: <digest>`** — a Next.js server digest | The error screen renders it in monospace, `select-all`. **This is the only correlation-like identifier any Connex UI shows.** |
| A raw API `500` (curl, devtools, an integration) | `correlationId` in the JSON body, and the `X-Correlation-Id` response header | The catch-all exception handler and the correlation filter |

**No screen renders the correlation ID.** The frontend parses it into `ApiError.correlationId` and
then never displays it. So a user reporting a broken page will hand you a **digest**, not a
correlation ID — asking them for a correlation ID will produce confusion, not an identifier.

The digest is nonetheless the join key. When the frontend error boundary fires, it best-effort
reports to `POST /api/client-errors`, and the server **stamps its own correlation ID onto that
record**. So digest → client-error log line → correlation ID → the rest of the request's server
logs.

`/api/client-errors` is bounded on purpose, which explains the gaps you will see:

- It requires a **resolved workspace membership**; a logged-out or workspace-less user's boundary
  error is rejected with `403` and **never reaches the log**.
- Caps: message 1000 characters, stack 8000, path 300, **max 5 reports per page load**, deduplicated
  on `digest ?? name:message`; the server window defaults to 20 reports per 300 seconds
  (`connex.client-errors.*`).
- A missing client-error line is therefore not evidence that nothing broke.

### Correlation IDs — the mechanics

- Header `X-Correlation-Id`; SLF4J MDC key `correlationId`; production logs are **structured ECS
  JSON**, so it lands as a real field, not free text.
- Generated as a UUID. An **inbound** value is honoured only when there is exactly one header value
  and it matches `^[A-Za-z0-9_-]{8,64}$`; otherwise a fresh one is minted. Log injection through
  this header is not possible.
- It is **always echoed on the response**, on every status, and survives async re-dispatch — a
  streamed export that fails mid-body reuses the id already sent.
- Only unexpected `500`s put it in the body. `400`/`403`/`404`/`409` responses do **not** carry it.

### Log lookup recipes

Both lookups run entirely against the deployment's own logs. Full context in the "Monitoring &
support" section of [DEPLOYMENT.md](DEPLOYMENT.md); the two commands are:

```bash
# The user quoted a correlation ID (raw API 500, or an integration's captured header)
journalctl -u <backend-unit> | grep '"correlationId":"<id>"'

# The user quoted a `Reference:` digest from a broken page
journalctl -u <backend-unit> | grep '<reference>'
```

The digest grep matches the `CLIENT`-source entry, which carries the digest, the page path, and the
client stack — and, because the server stamped it, the correlation ID to continue with.

### Support bundle

**Tracked and not yet shipped.** A support-bundle collector does not exist at this commit. This
section will be replaced with its contents, invocation, and redaction guarantees when the work
lands. Until then, do not promise a partner a bundle, and do not improvise one by collecting logs ad
hoc without agreeing scope in writing first — deployment logs contain tenant-identifying data.

## Access boundaries

### The posture

**Connex operators run bundles; Connex engineers do not get database or SSH access to customer
deployments.** For a customer-operated deployment there is nothing to get: the customer runs the
host, the database, and the keys. The observability surface is built for **the customer's**
monitoring stack, and **nothing phones home** — the default error sink is local structured `ERROR`
log lines, and no error data leaves the deployment unless the operator configures a vendor
integration themselves.

The only verbatim claim in the tree is in [`deploy/onprem.env.example`](../deploy/onprem.env.example):

```dotenv
# The customer holds all secrets and key custody; Connex has no data-plane access.
```

### What mechanically backs it

| Control | Effect |
|---|---|
| `CONNEX_DEPLOYMENT_PROFILE=on-prem` forbids `connex.mail.managed` | Instance-managed mail is transport **Connex operates**. Setting it on-prem **fails startup**, so no Connex-operated transport can sit in the path. The capability registry refuses `MANAGED_MAIL` on-prem as well — the refusal is doubled on purpose. |
| The customer generates and holds `CONNEX_SECRET_STORE_MASTER_KEY` | The envelope-encryption root for stored integration credentials never leaves the customer environment. |
| The customer generates and holds `CONNEX_AUDIT_INTEGRITY_HMAC_SECRET` | The audit hash-chain key is customer-held, so Connex cannot silently re-chain their audit log. |
| Actuator is **fully closed** instance-wide | `management.endpoints.web.exposure.exclude: "*"`, discovery disabled, JMX excluded. There is no operator-reachable actuator surface to be exposed by accident. |
| `/api/metrics` requires a scrape token | Gated on the `CONNEX_METRICS_SCRAPE_TOKEN` bearer authority alone — **an ordinary authenticated session cannot read it**, and neither can a `HEAD` request. |
| Internal-access opt-ins are **allowed** on-prem | `connex.mail.allow-internal-hosts`, `connex.sso.allow-private-issuer-hosts`, `connex.ai.allow-internal-endpoints`, and `connex.bootstrap.enabled` are forbidden only under `saas`. On-prem the customer controls the network, so reaching internal infrastructure is the point. |
| Backups are operator-run | The tooling in `deploy/backup/` runs on the customer's host against the customer's database on the customer's schedule. See [BACKUP_RESTORE.md](BACKUP_RESTORE.md). |

### What this does and does not mean — read before saying it out loud

"Zero-access" is defensible for a **customer-operated on-prem** deployment, and it means exactly
one thing: **Connex operates nothing in that environment and holds none of its keys.** It does
**not** mean the software is unable to read the data.

**The Connex backend processes customer CRM content in plaintext to provide the service — in every
deployment shape, including on-prem.** It runs inside the customer's environment and reads their
data there. Searchable CRM fields — names, companies, emails, deals, notes, addresses, custom
fields, tags, relationship graph data — are **not** protected by the secret-store envelope layer;
that layer covers **never-searched integration credentials only** (SMTP passwords, OIDC client
secrets, SAML private keys).

Consequently:

- **Hosted SaaS and a Connex-operated silo are not E2EE, not zero-knowledge, and not
  customer-only-key encrypted.** A silo is a Connex-operated environment; the "Connex cannot see our
  data" framing must be qualified to customer-operated/on-prem and must not be stretched to a silo.
- Do not tell anyone — partner, prospect, or auditor — that Connex "cannot see", "cannot access", or
  is "technically unable to" read customer data.
- Application exports are **plaintext at generation**. Protecting them after download is the
  customer's job.

All customer-facing wording on this subject comes from
[ENCRYPTION_GUARANTEE_MATRIX.md](ENCRYPTION_GUARANTEE_MATRIX.md), which is the canonical source and
carries the review lint checklist. Use its questionnaire boilerplate verbatim rather than
paraphrasing this section.

## Offboarding

The sequence is **export → verify → delete workspaces → delete organization**. There is **no CLI for
any of it** — and, less obviously, **no UI for any of it either**: nothing in the frontend calls the
export or teardown endpoints. Offboarding is hand-assembled HTTP against the deployment, performed
by the customer's own organization owner or administrator.

**Confirm before you start that the operator has a registered passkey.** Both the export and both
teardown calls are step-up-guarded, and step-up is WebAuthn-only — a password-only session fails
every one of them with `RECENT_AUTHENTICATION_REQUIRED`. Discovering this at termination, when the
account may already be the last one standing, is a bad day. If they have no passkey, enrol one
first: **Account → Security**, see "Step-up requires a passkey" above.

### 0. Get the session stamped — do this first, it is not obvious

The export is a **`GET`** (`TenantLifecycleController.export`), and the frontend's automatic passkey
prompt fires **only on mutating requests**. There is also no manual "step up now" control anywhere
in the UI, and `curl` cannot perform a WebAuthn ceremony. So there is no direct way to satisfy
step-up on the export call itself. The working procedure is indirect, and every step of it must
happen inside one recent-authentication window (default **10 minutes**):

1. **Sign in as the organization owner or administrator in a browser, with a passkey.**
2. **Perform an unrelated step-up-guarded *mutation* purely to stamp the session.** The least
   invasive is renaming one of your own passkeys at **Account → Security**
   (`PATCH /api/auth/webauthn/credentials/{credentialId}`) — it is step-up-guarded, touches no
   tenant data, and you can rename it straight back. Creating and then revoking a throwaway invite
   link (**Settings → Members**) also works. Complete the passkey prompt the frontend raises.
3. **Reuse that same authenticated session for the export.** The stamp lives on the servlet session,
   so it travels with the `JSESSIONID` cookie:
   - *Simplest:* paste the export URL into the same browser. It is a `GET` with
     `Content-Disposition: attachment`, so the ZIP just downloads.
   - *Scripted:* copy `JSESSIONID` from the browser's devtools (Application → Cookies) and replay it
     with `curl`.
4. **The stamp is not consumed by use** — every call inside the window succeeds — but it does
   expire. A long export or a careful ZIP verification will outlive it, so **re-stamp with another
   mutation before the teardown calls**.

> **This is a current product gap, not a design.** The missing export/teardown UI and the missing
> manual step-up trigger are **tracked**; until they land, the sequence above is the documented
> procedure and a tester cannot complete an offboarding without it. Do not "fix" it by setting
> `CONNEX_RECENT_AUTHENTICATION_WINDOW` to zero — that disables step-up for the whole instance.

### 1. Export

```text
GET /api/orgs/{orgId}/workspaces/{workspaceId}/export
```

With a session stamped per step 0, either open that URL in the same browser or replay the cookie:

```bash
curl -sS -OJ -b "JSESSIONID=<value-from-devtools>" \
  https://<partner-host>/api/orgs/<orgId>/workspaces/<workspaceId>/export
```

- Requires **organization administrator** access (`Organization administrator access required`) and
  **recent authentication** (step-up). The strict authorization audit record is durable before any
  response body begins.
- Streams a **ZIP** — tenant table snapshots plus managed object bytes — without materializing
  tables or object bytes in memory. Response headers are hardened: `Cache-Control: no-store`,
  `X-Content-Type-Options: nosniff`, `Cross-Origin-Resource-Policy: same-origin`, and a
  `default-src 'none'; sandbox` CSP.
- **Admission-controlled with back-pressure.** Concurrent exports take a global lease; over capacity
  the call is refused with `429`:

  ```text
  Too many tenant exports are already streaming; retry shortly
  ```

  Retry rather than escalate. Do not run several tenant exports in parallel to "save time".
- One workspace per call. An organization with several workspaces needs one export each.

### 2. Verify before deleting

Deletion is irreversible and the export is a stream — a truncated archive is a real failure mode
(hence the cancel-on-error path). **Open the ZIP, confirm the expected workspaces and objects are
present, and store it under the customer's own encryption before proceeding.** Exports are plaintext
at generation.

### 3. Tear down

```text
DELETE /api/orgs/{orgId}/workspaces/{workspaceId}     body: { "confirmation": "<workspace-slug>" }
DELETE /api/orgs/{orgId}                              body: { "confirmation": "<organization-slug>" }
```

These are mutations with a JSON body and no UI, so they need the session cookie **and** a CSRF token
from the same session — plus a step-up stamp that is still inside the window (re-stamp per step 0 if
verifying the ZIP took longer than that):

```bash
BOOTSTRAP=$(curl -sS -b "JSESSIONID=<value>" https://<partner-host>/api/auth/csrf)
curl -sS -X DELETE -b "JSESSIONID=<value>" \
  -H "$(jq -r .headerName <<<"$BOOTSTRAP"): $(jq -r .token <<<"$BOOTSTRAP")" \
  -H 'Content-Type: application/json' \
  -d '{"confirmation":"<workspace-slug>"}' \
  https://<partner-host>/api/orgs/<orgId>/workspaces/<workspaceId>
```

Both return `204`. Both are guarded four ways:

1. **Organization owner only** — not administrator. `Organization owner access required`.
2. **Recent authentication** (step-up) at the moment of the call.
3. **Case-sensitive slug confirmation.** A mismatch is a `400`:
   `Tenant confirmation does not match its slug`. The workspace call confirms the *workspace* slug;
   the organization call confirms the *organization* slug.
4. **Refused while any APPI data-subject request is still open** against that workspace or
   organization. Deleting the organization root would cascade the request rows themselves away, so
   an unfinished 開示等 obligation must be **closed first**, not erased. Expect this refusal on a
   real offboarding and plan for it — see
   [APPI_DATA_SUBJECT_REQUEST_PROCEDURE.md](APPI_DATA_SUBJECT_REQUEST_PROCEDURE.md).

The organization call tears down every workspace and then the organization root, so the per-workspace
call is for partial offboarding, not a required precursor.

### Retention and the contractual window

What survives deletion — retained audit metadata, report-snapshot retention, approval rules — is in
[GOVERNANCE_DELETION_AND_RETENTION.md](GOVERNANCE_DELETION_AND_RETENTION.md). The contractual
export-then-delete window lives in the signed DPA;
[APPI_DPA_TEMPLATE.md](APPI_DPA_TEMPLATE.md) §8 proposes **[30] days** on termination, including
removal from routine backups on their normal cycle. Confirm the executed number for the specific
customer rather than assuming the template value.

## Production fail-closed environment

Each row below **throws and prevents startup** outside the noted exemption. This is the complete
enumerated set; the meaning of each variable is in [DEPLOYMENT.md](DEPLOYMENT.md) and
[DEPLOYMENT_EDITIONS.md](DEPLOYMENT_EDITIONS.md) rather than repeated here.

| # | Variable / condition | Exact failure message | Exempt profiles |
|---|---|---|---|
| 1 | `CONNEX_DEPLOYMENT_PROFILE` | `CONNEX_DEPLOYMENT_PROFILE must be set to saas, silo, or on-prem outside dev/test/seeder` | `dev`, `test`, **`seeder`** |
| 2 | `CONNEX_AUDIT_INTEGRITY_HMAC_SECRET` (≥ 32 chars) | `CONNEX_AUDIT_INTEGRITY_HMAC_SECRET must be set outside dev/test` | `dev`, `test` only |
| 3 | `CONNEX_SECRET_STORE_MASTER_KEY` | `CONNEX_SECRET_STORE_MASTER_KEY must be set outside dev/test` | `dev`, `test` |
| 4 | `CONNEX_DB_URL` | `CONNEX_DB_URL must be set outside dev/test` | `dev`, `test` |
| 5 | `CONNEX_DB_USERNAME` | `CONNEX_DB_USERNAME must be set outside dev/test` | `dev`, `test` |
| 6 | `CONNEX_DB_PASSWORD` | `CONNEX_DB_PASSWORD must be set outside dev/test` | `dev`, `test` |
| 7 | Verified DB TLS — `sslMode` ∈ {`VERIFY_CA`, `VERIFY_IDENTITY`} | driver/URL refusals, e.g. `… must use the jdbc:mysql driver outside dev/test`, `… must not be blank outside dev/test` | `dev`, `test`; plus a narrow localhost carve-out for the systemd staging checkout at `/opt/connex-staging/backend` |
| 8 | Profile-forbidden posture keys | `connex.deployment.profile=<p> forbids: <key>=true` | `dev`, `test`, `seeder` |
| 9 | Unknown profile value | `Unsupported connex.deployment.profile=<p>` | — |
| 10 | Maintenance-mode coherence | `Legacy upload migration mode requires maintenance mode legacy-upload-migration` | — |
| 11 | `connex.readiness-file`, when set | `connex.readiness-file must have an existing absolute parent directory` | only when configured |
| 12 | Bundled public-suffix rules | `Bundled public-suffix rules failed a required matching invariant` | — |
| 13 | Local systemd staging with `dev`/`test` active | `Local systemd staging must not run with dev or test profiles active` | — |

Two related fail-closed behaviours are **not** in this table because they are defaults rather than
validators, and the profile validator's own documentation deliberately leaves them out:

- **Cookie `Secure` defaults to `true`** for both the session and workspace cookies
  (`CONNEX_SESSION_COOKIE_SECURE`, `CONNEX_WORKSPACE_COOKIE_SECURE`). An HTTPS deployment that
  forgets to configure them still never emits `JSESSIONID` over plaintext HTTP. The `dev` profile
  opts out.
- **Posture keys are read through relaxed binding**, so a spelling like `connex.mail.MANAGED` cannot
  slip past the forbidden-key check.

**Do not reach for the `dev` profile to get a stubborn instance to boot.** It simultaneously
disables the cookie `Secure` flags, permits plaintext database transport, and supplies a local-only
audit-integrity secret. An instance that boots because someone set `dev` is not the instance that
was reviewed.

## What is NOT fail-closed

Ops must not infer "it booted, therefore it is configured". The following **warn or say nothing at
all** and leave the instance running in a degraded state that looks healthy.

| Condition | What actually happens | Why it bites |
|---|---|---|
| `CONNEX_METRICS_SCRAPE_TOKEN` unset | Startup **warns**; boot continues | `GET /api/metrics` becomes unreachable **to every caller**. Monitoring silently has no metrics; nobody notices until an incident. |
| Secret-store key diagnostics report a blocking failure | Logged at `ERROR`; **boot continues** | A key-lifecycle problem is visible only in the log, not in the instance's ability to start. Alert on that `ERROR` line. See [SECRET_STORE_KEY_LIFECYCLE_RUNBOOK.md](SECRET_STORE_KEY_LIFECYCLE_RUNBOOK.md). |
| `CONNEX_SECURITY_TRUSTED_PROXIES` unset behind a proxy | **No validator at all.** `X-Forwarded-For` is never trusted | Every request appears to come from the proxy. **Per-IP rate limiting and audit source IPs become worthless**, so brute-force protection and forensic attribution are both silently defeated. Set it to your proxy/CDN/tunnel egress ranges in **any** proxied deployment. |
| `connex.delivery.public-base-url` unset with campaign delivery on | No warning | The unsubscribe link is emitted as a **relative path** in the email body, which is unusable in a mail client. See [DELIVERABILITY.md](DELIVERABILITY.md). |

One more that is reported but deliberately non-gating: `checks.auditGuard` in
`GET /api/health/ready` reports whether the append-only `audit_log` guards are visible, and a `DOWN`
there **does not** fail readiness. That is intentional — an application user without the MySQL
`TRIGGER` privilege cannot see the guards at all and must not be taken out of rotation for it.
**Alert on it; do not gate on it.**
