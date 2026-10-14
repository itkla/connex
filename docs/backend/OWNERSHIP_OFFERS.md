# Ownership offers

Control migration V229 creates the `ownership_offer` table. Closure attribution (`closed_by_user_id`) is nullable and carries **no foreign key**: account erasure clears it explicitly, so a closure's `UPDATE` never takes an implicit account lock after the scope roots, which would invert the erasure order. The retained offeree foreign key has its own immutable supporting index for the same reason.

Issue #1534 decision 4 makes an existing-scope owner promotion consent-based. Creation preserves the offeree's raw role and custom overlay. Admin changes, founding, owner repairs, last-owner/recovery guards, invitations and SSO admission retain their existing behavior. Acceptance clears a workspace overlay or an organization founder flag; it grants no credential coverage.

## HTTP contract

All routes require the authenticated session; mutations retain CSRF. IDs in paths are decimal integers. Offer IDs in JSON are lossless decimal strings. Self-list, acceptance and decline ignore stale workspace selection.

| Method and path | Body | Success |
|---|---|---|
| `PATCH /api/workspaces/{id}/members/{userId}` | Existing `{role?,roleId?,acknowledgeConfinement?}`; `role:"owner"` creates consent when the target is not owner | `202 OwnershipOfferCreated<MemberDto>`; other changes and existing-owner repair retain `200 MemberDto` |
| `POST /api/orgs/{id}/members` | `{email,orgRole,acknowledgeConfinement?}` | New owner consent: `202 OwnershipOfferCreated<OrgMemberDto>`; other edits retain `204` |
| `PUT /api/orgs/{id}/members/{userId}` | `{orgRole,acknowledgeConfinement?}` | Same as organization POST |
| `GET /api/ownership-offers` | None | `200 OwnershipOffer[]`, authenticated offeree's live pending cards |
| `GET /api/workspaces/{id}/ownership-offers` | None | `200 OwnershipOffer[]`, current raw workspace owners |
| `GET /api/orgs/{id}/ownership-offers` | None | `200 OwnershipOffer[]`, current raw organization owners |
| `POST /api/ownership-offers/{id}/accept` | Optional `{acknowledgeConfinement?:boolean}` | `200 {offer,membership,standing,enforcedStanding}` |
| `POST /api/ownership-offers/{id}/decline` | None | `204` |
| `DELETE /api/workspaces/{id}/ownership-offers/{offerId}` | None | `204`, current raw owner with recent authentication |
| `DELETE /api/orgs/{id}/ownership-offers/{offerId}` | None | `204`, current raw owner with recent authentication |

`OwnershipOfferCreated<T>` is `{outcome:"ownership_offer",offer:OwnershipOffer,currentMember:T|null}`. Only a new organization membership has `currentMember:null`.

`OwnershipOffer` has `id:string`, `scopeKind:"WORKSPACE"|"ORGANIZATION"`, `orgId:number`, `workspaceId:number|null`, `offerorUserId:number`, `offereeUserId:number`, `state:"OPEN"|"ACCEPTED"|"DECLINED"|"REVOKED"|"EXPIRED"|"INVALIDATED"`, `createdAt:string`, `expiresAt:string`, `closedAt:string|null`, `closedByUserId:number|null`, `closureReason:string|null`, `scopeName:string|null`, `offerorDisplayName:string|null`, `offereeDisplayName:string|null`, `offereeEmail:string|null`. Timestamps are UTC ISO 8601. Accepted membership is the existing workspace `MemberDto` or organization `OrgMemberDto`, or `null` if an accepted retry finds that membership subsequently removed. Standing fields are `UNPRIVILEGED`, `SATISFIED`, `ENROLLMENT_REQUIRED` or `ATTESTATION_REQUIRED`, exposed only to the acceptor. Nullable ownership-offer and acceptance-wrapper fields remain present in JSON. The existing nested member DTOs retain their global NON_NULL rendering: workspace members contain `id`, `username`, `displayName`, `email`, `role`, `builtInRole`, `status`, and optional non-null `profilePictureUrl`/`roleId`; organization members contain `id`, `username`, `displayName`, `email`, `orgRole`, and optional non-null `profilePictureUrl`. Existing nullable string fields are omitted when null.

Display labels are optional and always present, including when null. `scopeName` is the workspace name for WORKSPACE offers and organization name for ORGANIZATION offers. Self-list and acceptance expose `scopeName`, `offerorDisplayName` and the caller's own `offereeDisplayName`/`offereeEmail`; another offeree's contact fields are null, and no offeror email is exposed. Scope-owner lists and 202 creation responses expose all four labels. Existing non-locking control reads resolve labels after write transactions return wherever possible, with caches confined to one response. Missing records or labels produce null and never reject a transition.

Creation does not consume confinement acknowledgement. Acceptance uses the offeree's recent, sufficient authentication, an owned credential proof and current session epoch; it repeats these checks under roots. Enabled attestation enforcement can return the existing confinement-acknowledgement `409`; the claim, role and audit roll back together. The offeree retries with acknowledgement. After commit, both raw and enforced standing are refreshed; recovery may produce `ENROLLMENT_REQUIRED`.

Inaccessible scope/offer IDs return uniform `404 RESOURCE_NOT_FOUND`. Expired or otherwise terminal acceptance returns `409`; successful ACCEPTED, DECLINED and REVOKED retries are idempotent. Accepted retries report the current membership and never regrant after demotion. Pending workspace targets must join first (`400`). Organization eligibility and mailbox-proof refusals retain the non-revealing existing response. Authentication and CSRF retain their existing `401`/`403` protocol. The remaining API error shapes are:

| Status | JSON body | Trigger |
|---|---|---|
| `401` | Empty body, no code | Application-session authentication required |
| `403` | Empty body, no code | Existing application-chain access-denied/CSRF refusal |
| `400` | `{code:"BAD_REQUEST",message:string}` | Invalid role, pending workspace target, or existing non-revealing organization eligibility/mailbox refusal |
| `400` | `{code:"VALIDATION_FAILED",message:"Please fix the highlighted fields",fieldErrors:Record<string,string>}` | Required/invalid organization body fields, including email |
| `400` | Plain text `Malformed request body` | Malformed JSON or an incompatible body value |
| `400` | Plain text `Invalid value for parameter: {name}` | Path-variable number binding failure |
| `404` | `{code:"RESOURCE_NOT_FOUND",message:string}` | Inaccessible offer/scope, or a newly referenced account fenced by deletion |
| `409` | `{message:string}` | Another offeror owns the open slot, unavailable/expired/terminal transition, or lock conflict |
| `409` | Plain text `The request conflicts with the current state` | A fail-closed internal state guarantee, such as a delivered notice without its recipient-state row |
| `409` | `{code:"PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED",message:string,affectedCount:number}` | Offeree must acknowledge confinement on acceptance |
| `403` | `{code:"RECENT_AUTHENTICATION_REQUIRED",message:string}` | Missing recent sufficient authentication, owned signer or current session epoch |
| `403` | `{code:"FORBIDDEN",message:string}` | Existing membership/role/grant-ceiling refusal |
| `403` | `{code:"PRIVILEGED_MFA_ENROLLMENT_REQUIRED"|"PRIVILEGED_MFA_ATTESTATION_REQUIRED",message:string}` | Confined request outside the allowlist |

Only exact `GET /api/ownership-offers` and exact `POST /api/ownership-offers/{decimalId}/decline` are new confinement allowlist entries. Acceptance, creation and revocation remain gated. The server and client patterns are identical. Scope IDs and party IDs retain the existing integer representation; only offer IDs use strings.

## Persistence, locking and cleanup

Control migration V229 creates `ownership_offer`. The generated `scope_id` is virtual: MySQL 8.4 forbids CASCADE foreign keys on base columns of a stored generated column ([MySQL generated-column constraints](https://dev.mysql.com/doc/refman/8.4/en/create-table-generated-columns.html)). The indexed virtual key preserves the required uniqueness. Database checks enforce distinct parties, valid scope shape and expiry; a generated nullable open-slot key and unique `(scope_kind,scope_id,offeree_id,open_slot)` permit one OPEN consent. Seven-day expiry uses the database UTC clock. Same-offeror retries preserve the ID and deadline; another offeror conflicts. Expired consent closes before replacement. Workspace consent binds to the membership generation, so departure/rejoin cannot revive it.

New offer transactions and invalidation hooks assert READ COMMITTED before their reads. Tests exercising those hooks use READ COMMITTED individually; the shared fixture base retains its isolation. Workspace transitions retain sorted account X → workspace X → sorted exact memberships X → conditional owner/assignee NOWAIT snapshots → sorted custom-role roots/permissions → organization S → offer X → writes → audit head. Organization transitions retain sorted account S → organization X → owner rows X → exact offeree membership X → offer X → writes → audit head. NOWAIT remains a conflict; only MySQL deadlocks retry, in a fresh transaction after rollback. Acceptance rechecks raw offeror ownership/full grantability, current offeree eligibility, lifecycle, reservations, generation, proof and expiry.

Every transition uses a strict scoped consent audit. Acceptance also strictly audits the role grant. Refusal auditing occurs after rollback; enclosing transactions defer independent append until their locks are released. Existing deferred MFA refusals retain their behavior.

Closure attribution (`closed_by_user_id`) has no FK; every new actor reference is still fenced under the initial account locks, and erasure explicitly nulls attribution before audit heads. Party and scope FKs retain their cascade backstops. The offeree FK is supported only by `(offeree_user_id,id)`, which closures never change. Pending-card lookups have a separate `(state,offeree_user_id,expires_at,id)` index. A mutable offeree-leading FK-support index would acquire an offeree account S lock when state changes after scope roots, violating the erasure hierarchy. The immutable support avoids that check without adding counterpart account locks or changing erasure isolation.

Offeror authority loss, party departure and lifecycle fences invalidate consent. An organization fence excludes its cards and acceptance immediately, then an after-commit listener closes workspace consents in separate routed transactions using the existing per-workspace hierarchy. The bounded sweep retries unfinished fenced-scope closures. Root-deletion residual audits flush while their parent roots still exist. Account deletion retains its reservation through every guarded tenant-catalog transaction. Offer erasure runs only after all catalogs succeed (`eraseAfterCatalogs`), so a later tenant guard cannot refuse after offers have already been removed. Notice resolution tolerates notices already removed by catalog erasure and never recreates them. Account cleanup discovers both parties and third-party closure attribution before roots, locks exact offer rows before audit heads, explicitly deletes party references and nulls closure attribution. Final account deletion takes the deleting account X before its first reservation read, so a lease changed while waiting cannot pin an earlier repeatable-read snapshot. This is entered from the existing NOT_SUPPORTED account-deletion facade as a fresh transaction. Final account deletion verifies residuals; foreign-key cascades are backstops. Destination membership/lifecycle cleanup erases notices under offer X and clears delivery pointers, including terminal history. Offers are control state, excluded from tenant content exports.

## Notices and catalog layouts

The self-offer card is the authoritative surface on every catalog layout. Notices use ordinary notification delivery, mapper statements, membership gate and realtime behavior. The pre-existing cross-catalog inbox limitation remains tracked by #313/#440 and pinned by `MapperPlaneArchTest`; this slice does not change its plane behavior. Verify inbox pages, unread counts, ID lookup, realtime and resolution on the shared/default catalog.

Notice type is `ownership.offer`, source/context pointers are null, data is `{offerId:string,scopeKind:string}`, dedupe is `ownership.offer:{id}`, and the destination is `/settings/personal/workspaces`. Organization notices choose an active organization workspace where the offeree already belongs, without fabricating membership. Accounts without such a workspace still have the durable card.

Delivery routes before opening its transaction. It retains sorted parties S with reservation checks → destination workspace root → exact destination membership/generation → organization → offer S, rechecks OPEN/expiry/lifecycle, then upgrades the offer through its conditional marker before ordinary upsert. The actor's account lock is already held. Realtime/email follow commit. Closures hold offer X and resolve the exact recipient/dedupe notice in the same transaction. A delayed worker cannot reopen a terminal notice. Inbox mutations of offer notices retain the offer lock, and restore has a SQL terminal-resolution fence.
Read-all traverses a fixed candidate cutoff with an advancing notification-id watermark. It skips
only candidates whose offer or notice no longer exists after a not-found refusal; other failures
propagate. Disappearing or unchanged candidates cannot prevent ordinary notifications from being
marked read or trap the traversal on its first page. Closure and cleanup counters use a sorted, deduplicated control-only UPDATE of existing recipient-state rows. The ordinary state upsert takes a recipient-account FK lock even on a duplicate; using it here would acquire a counterpart account after roots. Delivery commits DELIVERED, the notice and that state row together. A missing state row refuses closure and rolls it back instead of creating a reference. The counter queue unbinds/rebinds on transaction suspension so AFTER_COMMIT lifecycle closures have their own transaction state. Generic invalidation uses the existing transaction-aware realtime publisher.

Expiry is live on the control card immediately; the notice can remain active until a bounded expiry sweep changes OPEN to EXPIRED and resolves it. The card therefore handles an expired destination. Delivery and expiry failures remain retryable through durable markers and bounded per-offer transactions.

## Upgrade

V226 from PR #2066 is merged. Integrate V227/V228 from PR #2064 before deployment so the global lineage is complete. Ownership offers use control V229 with unchanged SQL after the orchestrator's merge-order reallocation. Keep serving replicas down until control V229 succeeds before deploying code that references the table. MySQL DDL is not transactional: a failed/crashed migration may leave the table without successful history. Inspect table shape and Flyway history, resolve the cause, and apply the established migration recovery runbook. Never edit an applied migration. Account/lifecycle cleanup must ship with the grant and delivery paths. Attestation enforcement remains dark until its separate switch-on slice.
