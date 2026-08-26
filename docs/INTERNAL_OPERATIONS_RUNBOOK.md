# Connex — Internal Operations Runbook

> **Status:** Internal operator reference for the Connex operations team. It describes what the code
> at this commit actually does, not what the product roadmap intends. Where this runbook and a
> signed customer agreement disagree, **the agreement governs**.
> **Access and encryption claims** must come from [ENCRYPTION_GUARANTEE_MATRIX.md](ENCRYPTION_GUARANTEE_MATRIX.md);
> do not paraphrase them from here.
>
> **Owner:** Hunter Nakagawa, Founder · **Last reviewed:** 2026-08-13 · **Next review:** 2027-02-13 ·
> Review at least every 6 months and after any change to provisioning, teardown, or the fail-closed
> startup set.

Related: [SECURITY.md](SECURITY.md) for named security/privacy ownership and escalation,
[DEPLOYMENT.md](DEPLOYMENT.md) for the operator deployment runbook,
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
link. **The invite is the real path.** The paste box accepts either shape — an emailed invite URL
(`/invite#token=<token>`), a shareable link (`/invite-link#token=<token>`, exactly what Settings →
Members copies), or a bare token of either kind — and routes each to the page that can redeem it.
Junk that cannot be a token — a bare word, a link to some other page — is refused in the box with a
message rather than navigated somewhere that can only report the invite as missing. A token-shaped
value that is not a live invite still lands on the redemption page and is reported there as
unavailable.

**This does not vary by deployment profile in code.** Neither flag appears in
`FORBIDDEN_KEYS_BY_PROFILE`, so no profile forces either value. It varies only by **env template**:
both [`deploy/silo.env.example`](../deploy/silo.env.example) and
[`deploy/onprem.env.example`](../deploy/onprem.env.example) set
`CONNEX_WORKSPACES_ALLOW_CREATION=false` — while
[`deploy/eval.env.example`](../deploy/eval.env.example) sets it `true`, because a local evaluation
has no operator to issue the first invite. That template declares no deployment profile at all and
says why in its own comments, so it can no longer be mistaken for a silo seed; a real deployment
starts from one of the other two. An instance configured by hand rather than from a template gets
whatever the operator set — check it, do not assume it.

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
- An assigned custom role cannot be deleted. Reassign every affected member first; this makes the
  resulting built-in or replacement role explicit and subjects it to the normal grant ceiling.
- **No one can confer a permission they do not themselves hold.** This cap is enforced on role
  **create** and **update**, on custom-role assignment, and on built-in role change. Non-grantable
  values are rejected on input and silently dropped on read.
- Deletion requires `ROLE_MANAGE`, recent step-up, the locked role-mutation protocol, and zero
  assignees. Because deletion cannot change anyone's effective permissions, it does not need a
  separate grant-ceiling comparison after those checks.
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

**The frontend turns that into a passkey prompt on mutating requests.** Tenant export deliberately
starts with a mutating grant request, so **Organization → Overview → Export and teardown** raises the
same passkey ceremony as teardown and then starts the streamed download. No unrelated mutation is
needed to stamp the session.

Operationally: **make sure the customer's organization owner registers a passkey during onboarding,
not on the day they need to offboard.** Setting the window to zero or a negative value disables
step-up entirely — do not do that to work around a missing passkey.

## Tester onboarding — install and invite to first trusted insight

### Step 1 — get the tester an account and a workspace

The workspace must exist first (see provisioning above). Then choose the path that matches the
instance's mail posture.

**Before choosing a path, check that the tester can even get an account.** Redeeming an invite is
**not** a registration path: both `/invite#token=<token>` and `/invite-link#token=<token>` require
an existing signed-in user, and the accept endpoints act on that account. An anonymous visitor
cannot complete either flow and is not registered automatically, so the tester must create or
receive an account, sign in, and only then open the link.

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
- **Deliver the whole shareable URL** (`https://<host>/invite-link#token=<token>`) and tell the tester
  to open it. On first load the frontend removes the fragment and sends it to
  `POST /api/invite-links/exchange`; the backend sets the purpose-bound HttpOnly flow cookie and
  redirects to token-free `/invite-link`. The page then previews with `GET /api/invite-links` and
  submits the preview's `flowId` to `POST /api/invite-links/accept`, which joins the tester, sets the
  active-workspace cookie, and makes them **`active` immediately**. There is no separate acceptance
  step to chase. A tester who lands on `/onboarding` first can paste that same fragment URL into its
  join box.
- Emailed invites use the parallel `https://<host>/invite#token=<token>` shape and are built from
  `connex.mail.app-base-url`, **never** from a request header. Their page exchanges through
  `POST /api/invites/exchange`, previews through `GET /api/invites`, and submits the preview's
  `flowId` to `POST /api/invites/accept`. If the base URL is wrong, every emailed invite link is
  wrong. Their expiry is **14 days, hardcoded in the mapper SQL** — no property overrides it — and
  unlike a shareable link each one is **single-use and bound to the invited address**: a different
  signed-in user gets
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
Unmasked disclosure is a separate, fail-closed posture. It requires
`CONNEX_AI_UNMASKED_MODE_ENABLED=true` plus a current org-admin ZDR attestation for the exact provider
destination. A destination change invalidates the attestation and immediately restores masked mode;
credential rotation and enable/no-training changes do not change the destination. If a tester expects
streaming, confirm the provider-config response reports `privacyMode=UNMASKED` and a current
attestation before investigating the SSE transport. Bedrock remains buffered by design.

## Feedback and incident channels

### What the user is actually holding

This is the single most common support-flow mistake, so internalize it before the first ticket:

| The user is looking at | They can quote | Where it comes from |
|---|---|---|
| A broken **page** (render/boundary failure) | **`Reference: <digest>`** — a Next.js server digest | The error screen renders it in monospace, `select-all`. This is the page identifier; it is NOT the API correlation ID. |
| A failed **in-app action** (an error toast) | **`Reference: <correlationId>`** appended to the toast body | The API error mapper (`frontend/app/lib/errorMessages.ts`) appends it whenever the response carried a correlation id — today only the catch-all 500 handler emits one |
| A raw API `500` (curl, devtools, an integration) | `correlationId` in the JSON body, and the `X-Correlation-Id` response header | The catch-all exception handler and the correlation filter |
| A **workflow run** they are asking about | a **run reference** — `canonical-<id>` or `legacy-<id>` — from the "copy the run reference" button beside "Run #N" / "Earlier run #N" | `WorkflowInterventionService`/`WorkflowRunReadService` mint it; the UI shows only the number. Look it up in workflow operations at `/workflows/<workflowId>/runs/<runKey>`, **not** in the correlation logs — it is not a correlation ID |

**Which identifier you get depends on what broke.** A failed action (toast) hands you the API
correlation ID as `Reference: <id>`; the admin diagnostics and mail-deliverability panels show the
same id as "Reference ID". A broken **page** hands you a Next.js **digest** — also labeled
`Reference:` — which is not a correlation ID and cannot be looked up as one. A **run reference** is
a third thing again: it names a stored workflow run, not an incident, so it resolves in workflow
operations and never in the correlation logs. Distinguish them by what the user was doing: an action
that failed → correlation ID; a page that wouldn't render → digest; a workflow run they want traced
→ run reference. The two run sequences are independent, so always take the prefix with the number —
`canonical-42` and `legacy-42` are different runs.

When the frontend error boundary fires, it best-effort reports to `POST /api/client-errors`. The
digest, message, and stack remain only in the local log sink and never enter the database or bundle:
decimal syntax does not prove that Next.js generated a caller-controlled value. The stored metadata
contains the report request's correlation HMAC, a closed-vocabulary route template, workspace, and
time. Because reporting is a later request, that HMAC is not a causal link to an earlier audit event.

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

### Support-bundle lookup recipes

The redacted bundle is the first-line ticket path. It requires no SSH or ad-hoc SQL:

```bash
# The user quoted a correlation ID from a raw API 500 or integration
deploy/support-bundle/collect.sh ... --correlation-id <id> --output /var/tmp/bundle.zip

# Render the complete, already server-filtered archive
deploy/support-bundle/read.sh --archive /var/tmp/bundle.zip
```

The server transforms the raw correlation lookup before comparing it with new HMAC rows and legacy
raw rows. The backend-produced audit and client-error entries, plus the manifest filter, carry only
`untrustedClientAssertedCorrelationHmac`; `read.sh` therefore does not accept a raw offline filter.
The optional journal slice uses the same disclosure-HMAC derivation as current rows: the dedicated
event removes the raw MDC value before it is written, and the collector admits the HMAC only after
exact organization scoping. Correlation-filtered legacy rows normalize to that value; unfiltered
legacy rows remain independently pseudonymized. Use `serverMintedRequestId` only as the trustworthy
within-audit request pivot. A quoted framework digest can be searched only in deployment-local logs;
those user-data-bearing lines must not be copied into a support artefact.

### Tenant diagnostics — open this before you ask for logs

There is a read-only diagnostics report the tenant administrator can open themselves. It needs **no
host access, no database access, and no operator involvement**, so it is the first thing to reach for
when a ticket arrives — it answers most "X is not working" reports without anyone touching a log.

| Scope | Page | Endpoint | Gate |
|---|---|---|---|
| Workspace | **Settings → Diagnostics** (`/settings/diagnostics`) | `GET /api/workspaces/{id}/diagnostics` | `WORKSPACE_SETTINGS` |
| Organization | **Organization → Diagnostics** (`/organization/diagnostics`) | `GET /api/orgs/{id}/diagnostics` | organization **administrator** |

The workspace report covers that workspace alone; the organization report aggregates every workspace
in the organization. There is also a command-palette destination ("Diagnostics") pointing at the
workspace page, offered to owners and administrators — but the **permission gate above is the
authoritative one**, and it is enforced server-side, so a member who reaches the page without
`WORKSPACE_SETTINGS` gets an error in place of the report rather than a hidden tab.

**Opening the page contacts nothing.** Both endpoints are `GET`s over saved configuration and stored
job history; the provider section says so in the product — *"Reported from saved configuration.
Opening this page does not contact any provider."* It is therefore safe to ask a customer to open it
mid-incident. The only outbound action anywhere on the page is the mail send-test, which you trigger
explicitly.

| Section | What it answers |
|---|---|
| **Deployment and capabilities** | "The edition this instance runs as, and which capabilities it allows." Each capability carries `profileAllowed` and `available` **separately**, which is the distinction that ends most escalations: **Not in this edition** means the deployment profile forbids it and no amount of configuration will turn it on; **Unavailable** means the edition allows it and the instance has not configured it. Profile semantics in [DEPLOYMENT_EDITIONS.md](DEPLOYMENT_EDITIONS.md). |
| **Provider readiness** | AI readiness (including image input), business-card scanning and import (OCR), and per workspace: mail mode and whether it is configured, each delivery channel (implemented / ready), and each capture stream. |
| **Scheduled jobs** | "The latest run of each background job, with its most recent success and failure" — Last run, Last success, Last failure, plus how many workspaces failed the latest run. |
| **Mail deliverability** | Transport state, advisory SPF/DKIM/DMARC rows, and the send-test below. |
| **Secret store** | "Key health for stored integration credentials. Metadata only — no secret values are shown." Active key id, whether it is configured or disabled, and counts of stale, missing-key, disabled-key, mismatched and unsupported-algorithm secrets. Remediation lives in [SECRET_STORE_KEY_LIFECYCLE_RUNBOOK.md](SECRET_STORE_KEY_LIFECYCLE_RUNBOOK.md); this is an in-product read of key health and **does not replace alerting on the startup `ERROR` line** described in "What is NOT fail-closed" below. |

The report opens with a badge per **finding** — a coded, severity-tagged problem naming the workspace,
capability, provider, channel or stream it concerns. Quote the finding code in the ticket.

**A failing source degrades one section, not the page.** Every section is assembled behind a guard: a
source that throws is recorded as a `SectionFault(section, reason)` — the only reason the code emits
is `source_unavailable` — that section renders "This section could not be checked", and **the request
still returns `200`**. A half-empty report is therefore a real signal about that source, not a broken
page; read the surviving sections normally and refresh to retry.

The report body itself carries **no correlation ID**. `Reference ID <id>` appears only when the load
or the send-test **fails**, and it is taken from the error envelope's `correlationId` — the same
identifier described in "Correlation IDs — the mechanics" above, so it greps with the first recipe.
A permission failure returns `403`, which does **not** carry one; expect an error with no Reference
ID there and check the actor's permission rather than the logs.

#### Mail send-test — self-recipient, and throttled to 3 per 5 minutes

`POST /api/workspaces/{id}/mail/diagnostics/test-send`. Every constraint is enforced server-side:

- **`WORKSPACE_SETTINGS` plus WebAuthn step-up.** A password-only session fails with
  `RECENT_AUTHENTICATION_REQUIRED` — see "Step-up requires a passkey" above.
- **The recipient is the caller's own stored address, always.** There is no recipient field, and the
  actor's email must be **verified**: an unverified actor gets outcome `unconfigured` with error code
  `actor_email_unverified`, and one with no address at all gets `actor_email_unconfigured`.
- **Throttled to 3 sends per 5 minutes per (workspace, actor)** — `connex.mail.diagnostics.max-test-sends`
  (default `3`) over a `connex.mail.diagnostics.test-send-window-seconds` (default `300`) fixed
  window. Neither property is declared in `application.yml`; those defaults are inline.
- Workspace scope only. The organization page renders the section read-only and points you at the
  workspace's own page.

**The throttle does not return `429`.** A throttled attempt returns `200` carrying a normal result
whose transport outcome is `failed` with error code `rate_limited` and mode `unconfigured` — on
screen that is the same red **Failed** pill a genuinely broken transport produces, distinguished only
by the error-code row. **A tester who clicks the button four times will report that mail is broken.**
Ask how many tests they just ran, and read the error code, before touching mail configuration.

The limiter is **in-memory and single-JVM**, matching the password-reset limiter and the in-memory
session model: it bounds one tester on one instance, not a fleet, and there is no deployment-wide
limit behind it. Tracked in **#964**; treat it as a usability guard, not an abuse control.

For the DNS records this section reports and the two-send test procedure, use
[DELIVERABILITY.md](DELIVERABILITY.md) rather than working from this page alone.

#### Why job history survives a bad week

Job history comes from the `job_run` table added in `V141__job_run.sql` (a **tenant**-plane
migration). Retention is per **(`job_name`, `workspace_id`, `status`)** — 50 rows per partition — and
the `status` dimension is deliberate: it keeps last-success and last-failure independent of run
volume, so **a run of failures cannot evict the last known good run**. Do not simplify it to a flat
per-job cap. Statuses are `succeeded`, `failed` and `skipped`; detail payloads are key-whitelisted
and capped, and instance-scoped rows (no workspace) retain only `phase`.

### Support bundle

A redacted support bundle is the sanctioned way to move diagnostic state out of a deployment Connex
does not operate. Full contents, the redaction contract, and a worked investigation are in
[SUPPORT_BUNDLE.md](SUPPORT_BUNDLE.md) — read that before you ask a customer for one. What follows is
only what an operator needs at ticket time.

| | |
|---|---|
| In-product | `GET /api/orgs/{orgId}/support-bundle` — organization administrator **plus WebAuthn step-up** |
| Operator tooling | [`deploy/support-bundle/collect.sh`](../deploy/support-bundle/collect.sh) to collect, [`read.sh`](../deploy/support-bundle/read.sh) to inspect — both executable, invoked directly |
| Assembly | **Synchronous**, not streamed |
| Bounds | 64 MB uncompressed ceiling and a 30-second budget, both **fail closed**; collection queries carry a 20-second database statement timeout |
| Busy | `429` |
| Too large or too slow | `413`, with text telling you to narrow the window or add an entity filter |

**Ask for a narrower window before you ask for a bigger bundle.** The ceiling and budget are refusals,
not truncations, so a `413` means the request was too broad — shorten `since` or filter to an entity.
The statement timeout is enforced at the database, so a runaway collection query is cancelled rather
than merely noticed.

**Use only the optional closed journal projection; never improvise a log export.** On the deployment
host, `collect.sh --include-journal --journal-unit <unit>` admits only the backend's dedicated
current-tenant request-completion event, checks the server-resolved organization integer before any
secondary correlation filter, and constructs a fresh eight-field record. It never copies raw
`MESSAGE`, messages, stacks, headers, hosts, query strings, or unknown fields. Missing, malformed,
ambiguous, other-tenant, background, pre-auth, and non-allowlisted records are omitted. Exit `68`
means the journal step failed and no output archive was published. Async request completions are
also omitted because tenant resolution can change before redispatch.

**The two audit identifiers have different trust.** `audit-slice.csv` carries
`serverMintedRequestId`, the non-spoofable within-audit pivot, and
`untrustedClientAssertedCorrelationHmac`, an organization-scoped, domain-separated HMAC of the
client-settable value. The manifest repeats those provenance labels and never carries the raw
assertion. The optional journal slice uses the same derivation as current and correlation-filtered
legacy rows after exact organization scoping; it is never proof of request identity or a substitute
for `serverMintedRequestId`. A digest from a broken page is not exported because the service cannot
authenticate its framework provenance.

The audit slice carries `actorId` only and no display names, marks truncation explicitly
(`auditSliceTruncated` with a row count), and ships a declared-omissions map so a missing required
backend file is visibly a decision rather than a gap. `client-errors.json` now ships its closed
metadata projection or declares `source_failed`; it never contains digest, message, detail, or
stack. An absent journal slice instead means it was not requested; a requested journal-stage failure
exits `68` and publishes no archive.

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

## Runtime cutover

`connex.workflows.runtime.enabled` / `CONNEX_WORKFLOWS_RUNTIME_ENABLED` defaults to **`false`**. The
flag changes delivery for every workflow before any individual workflow changes owner: with it on,
the after-commit legacy listener is inert, the legacy scheduler stops executing directly, and the
durable workflow outbox delivers both legacy-owned and canonical-owned work. Runtime ownership is
the persisted `workflow.runtime_owner` value, not a process-local flag.

This section records the behavior that operators must measure before a supervised cutover. It is
not an authorization to enable the flag or change ownership; the bounded flip procedure and its
rollback sequence are a separate rollout artifact.

### Engine delta inventory

#### Trigger intake

| Behavior | Legacy owner | Canonical owner |
|---|---|---|
| Entity-change delivery | Asynchronous `AFTER_COMMIT` listener; process death can lose the fire | Trigger target is written before source commit and drained by a leased worker |
| Candidate selection | Loads all enabled entity-change rules, then filters each in Java | SQL pre-filters trigger event and excludes intake-paused workflows |
| Fan-out | Unbounded | Maximum 128 matching workflow targets; excess intake fails with `trigger_fanout_limit` |
| Delivery retry | None; a dispatch failure is logged and dropped | Up to 8 leased delivery attempts, pinned to workflow generation and version |
| Typical latency | Near-immediate after commit | Up to the scheduler delay, 5 seconds by default, plus queue time |

#### Scheduling

| Behavior | Legacy owner | Canonical owner |
|---|---|---|
| Cadence producer | `RuleScheduler` evaluates every 15 minutes | The same tick creates a durable outbox target; the worker drains it |
| Bucket identity | Production uses a UTC cadence bucket | Uses the same UTC cadence bucket |
| Record enumeration | One unbounded whole-workspace segment evaluation | Freezes the upper record id at intake and scans pages of 100, checking each match |
| Schedule with no WHEN condition | Inert: it enrolls no records | Invalid: compilation fails with `schedule_enrollment_condition_required`, blocking cutover |
| Test-seam enrollment | Unbounded | `WorkflowRuntimeService` rejects more than 128 matches; production outbox delivery pages instead |

The legacy `runSchedule(workspaceId, cadence)` test overload uses the system-default time zone. It is
not the production path and must not be used as evidence for cadence-bucket parity.

#### Actions and failure handling

| Behavior | Legacy owner | Canonical owner |
|---|---|---|
| One action fails | Records the failure, continues later actions, and ends `partial` | Stops at that node; later nodes never run and the run fails or requires intervention |
| Mutable-reference preflight | None | Checks action permission, tag existence, active assignment target, and stage/pipeline compatibility before each action |
| Record guard | Contacts only, using the visibility-scoped person query | Every supported record type, owned by the workspace and not suspended/archived; rechecked before every node |
| Retry | None | At most 3 attempts for classified transient database failures, subject to action retry safety |
| Transaction boundary | All actions in one execution block | One node effect and checkpoint per `REQUIRES_NEW`, `READ_COMMITTED` transaction |
| Traversal bound | At most 16 flattened actions | At most 50 traversed nodes |
| Delays and non-legacy/arbitrary branching | Cannot be projected | Supported, but a workflow using either can no longer roll back to the legacy engine |

Both engines call the same `RuleActionExecutor`, so a successfully admitted action has the same
mechanics. The material differences are admission, transaction boundaries, retry, and what happens
after failure.

#### Notifications

| Behavior | Legacy owner | Canonical owner |
|---|---|---|
| Dedupe scope | One `rule:{ruleId}:{entityId}:{suffix}` key shared by every notify action in a fire | One `workflow:{runId}:node:{nodeId}` key per action node |
| Two notify actions | One notification row; the second action overwrites the first | Two notification rows |
| Email consequence | One first-occurrence email claim | One first-occurrence email claim per notify node |

Notification content other than the dedupe key is produced by the same executor: type `rule`,
category, severity, title, body, actor label `Automation`, source type, and source id.

#### Run recording

| Behavior | Legacy owner | Canonical owner |
|---|---|---|
| Ledger | One `rule_execution` row with a JSON detail | `workflow_run`, `workflow_step_run`, and `workflow_step_attempt` |
| WHEN not matched | `skipped` | `succeeded` after Condition → no → End |
| Partial execution | `partial` | No equivalent; the run fails or becomes `intervention_required` |
| User history | Exposed as stable `legacy-<id>` keys | Exposed as stable `canonical-<id>` keys; both histories are merged |
| Operations telemetry | Not included in workflow operations summaries | Included in summaries, interventions, failures, and overdue counts |

#### Idempotency and ownership

Both engines claim through `WorkflowRuntimeClaimService`, lock the same workflow row, read the
database-authoritative runtime owner under that lock, and check the opposite ledger before insert.
The established legacy rule id remains the primary identity for paired workflows. A workflow-id
compatibility identity is retained when needed, and schedule identities include the UTC cadence
bucket. Never derive replay identity from process time or a JVM-local owner flag.

#### Execution identity

| Mode | Both engines |
|---|---|
| `system` | Execute as the built-in `SystemActor`; attribute the effect to `created_by_id` |
| `user` | Execute as the configured live workspace member and current role; fail closed when the user or membership is gone |
| Condition evaluation | Uses the same effective actor for every graph shape that a legacy rule can represent |

### Read-only fleet pre-scan

Run this against each active tenant catalog before planning any ownership change. Every statement is
read-only. Save the complete output with the rollout evidence. The third query is deliberately
conservative: SQL cannot evaluate the full segment condition model, so it reports schedule rules
whose current eligible record-type population exceeds one 100-row production page. Evaluate those
rules through the application condition model to determine the exact enrollment count; do not treat
absence from this candidate list as proof that a future population will stay below 100.

```sql
SELECT workspace_id,
       COUNT(*) AS legacy_owned_workflows
FROM workflow
WHERE runtime_owner = 'legacy'
  AND archived_at IS NULL
GROUP BY workspace_id
ORDER BY workspace_id;

SELECT r.workspace_id,
       r.id AS rule_id,
       w.id AS workflow_id,
       r.enabled,
       r.record_type,
       JSON_UNQUOTE(JSON_EXTRACT(r.trigger_config, '$.cadence')) AS cadence
FROM rule r
LEFT JOIN workflow w
  ON w.workspace_id = r.workspace_id
 AND w.legacy_rule_id = r.id
WHERE LOWER(TRIM(r.trigger_type)) = 'schedule'
  AND r.condition_json IS NULL
ORDER BY r.workspace_id, r.id;

WITH record_population AS (
    SELECT workspace_id, 'company' AS record_type, COUNT(*) AS eligible_records
    FROM company
    WHERE archived_at IS NULL
    GROUP BY workspace_id
    UNION ALL
    SELECT workspace_id, 'person' AS record_type, COUNT(*) AS eligible_records
    FROM person
    WHERE suspended_at IS NULL
      AND archived_at IS NULL
    GROUP BY workspace_id
    UNION ALL
    SELECT workspace_id, 'deal' AS record_type, COUNT(*) AS eligible_records
    FROM deal
    GROUP BY workspace_id
)
SELECT r.workspace_id,
       r.id AS rule_id,
       w.id AS workflow_id,
       r.record_type,
       JSON_UNQUOTE(JSON_EXTRACT(r.trigger_config, '$.cadence')) AS cadence,
       p.eligible_records AS conservative_enrollment_ceiling
FROM rule r
JOIN workflow w
  ON w.workspace_id = r.workspace_id
 AND w.legacy_rule_id = r.id
JOIN record_population p
  ON p.workspace_id = r.workspace_id
 AND p.record_type = r.record_type
WHERE LOWER(TRIM(r.trigger_type)) = 'schedule'
  AND p.eligible_records > 100
ORDER BY r.workspace_id, r.id;

SELECT r.workspace_id,
       r.id AS rule_id,
       w.id AS workflow_id,
       COUNT(*) AS notify_action_count
FROM rule r
JOIN workflow w
  ON w.workspace_id = r.workspace_id
 AND w.legacy_rule_id = r.id
JOIN JSON_TABLE(
    r.actions_json,
    '$[*]' COLUMNS (action_type VARCHAR(24) PATH '$.type')
) action_rows
WHERE LOWER(TRIM(action_rows.action_type)) = 'notify'
GROUP BY r.workspace_id, r.id, w.id
HAVING COUNT(*) > 1
ORDER BY r.workspace_id, r.id;
```

## Offboarding

The sequence is **export → verify → delete workspaces or delete organization**. There is no CLI for
these lifecycle operations. The supported product flow is **Organization → Overview → Export and
teardown**, performed by the customer's own organization administrator or owner. An organization
administrator can export; only an organization owner can delete a workspace or organization.

**Confirm before you start that the operator has a registered passkey.** Both the export and both
teardown calls are step-up-guarded, and step-up is WebAuthn-only — a password-only session fails
every one of them with `RECENT_AUTHENTICATION_REQUIRED`. Discovering this at termination, when the
account may already be the last one standing, is a bad day. If they have no passkey, enrol one
first: **Account → Security**, see "Step-up requires a passkey" above.

### 1. Export

1. Sign in as an organization administrator or owner.
2. Open **Organization → Overview → Export and teardown**.
3. Read the plaintext-export warning, choose **Export workspace**, and complete the passkey prompt.
4. When **Download ZIP** appears, select it. The browser handles the streamed attachment in a new
   tab while Connex remains open. Save the archive only to the approved location.

The browser first calls the mutating grant endpoint, then downloads the artifact when the operator
selects the explicit download control:

```text
POST /api/orgs/{orgId}/workspaces/{workspaceId}/export
GET  /api/orgs/{orgId}/workspaces/{workspaceId}/export
```

The POST returns the expiry and download path while placing the credential in an exact-path,
`HttpOnly`, `SameSite=Strict` cookie. The grant lasts two minutes, is single-use, and is bound to the
issuing user, authenticated session, organization, and workspace. A replay or cross-tenant use is
refused. The browser consumes it on the GET; operators never copy the grant or a `JSESSIONID` into a
script. The GET has no grantless compatibility mode: a missing, expired, or already-consumed grant
is refused even while the authenticated session remains inside its recent-authentication window.

- Requires **organization administrator** access (`Organization administrator access required`) and
  **recent authentication** when the grant is issued. The strict authorization audit record is
  durable before any response body begins.
- Streams a **ZIP** — tenant table snapshots plus managed object bytes — without materializing
  tables or object bytes in memory. Response headers are hardened: `Cache-Control: no-store`,
  `X-Content-Type-Options: nosniff`, `Cross-Origin-Resource-Policy: same-origin`, and a
  `default-src 'none'; sandbox` CSP.
- **Admission-controlled with back-pressure.** Concurrent exports take a global lease; over capacity
  the call is refused with `429`:

  ```text
  Too many tenant exports are already streaming; retry shortly
  ```

  The product remains open and the download grant is not cleared on this refusal. Return to the
  export controls and retry **Download ZIP** before the two-minute grant expires, or choose **Renew
  download** to issue a fresh grant. Do not run several tenant exports in parallel to "save time".
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

Return to **Organization → Overview → Export and teardown**. Choose the workspace or organization
delete action, read exactly what is deleted and retained, type the displayed slug without changing
case or whitespace, and complete the passkey prompt. If ZIP verification outlived the recent-auth
window, this mutation raises a fresh prompt naturally.

Both return `204`. Both are guarded four ways:

1. **Organization owner only** — not administrator. `Organization owner access required`.
2. **Recent authentication** (step-up) at the moment of the call.
3. **Case-sensitive slug confirmation.** A mismatch is a `400`:
   `Tenant confirmation does not match its slug`. The workspace call confirms the *workspace* slug;
   the organization call confirms the *organization* slug.
4. **Refused while any APPI data-subject request is still open** against that workspace or
   organization. Deleting the organization root would cascade the request rows themselves away, so
   an unfinished 開示等 obligation must be **closed first**, not erased. Expect this refusal on a
   real offboarding and plan for it. The dialog stays open and identifies the blocking obligation
   instead of collapsing it into a generic failure. See
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
| 8 | Profile-forbidden posture keys | `connex.deployment.profile=<p> forbids: <key>=true` | none — `dev`/`test`/`seeder` exempt an **unset** profile from being required, but once a profile is set its forbidden keys are refused under every Spring profile |
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
