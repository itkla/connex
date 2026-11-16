# Picker keys and focus

`components/ui/combobox.tsx` keeps Base UI's collection, composition, selection and dismissal behavior.
`ComboboxList` requires a typed render function. Static mapped children bypass Base UI's filtered collection
and must never be used. Put `ComboboxEmpty` beside the list within `ComboboxContent`.
Local matching normalizes labels and queries with NFKC before Base UI's collation, including
half-width kana, voiced combining marks and full-width Latin. A committed selection still opens the
full collection. The root renders at most 100 matching rows after filtering.

Server searches pass a `serverSearch` snapshot from `lib/server-picker-search.ts`. Raw `filter={null}`
is excluded from the shared wrapper's public type; the snapshot selects Base UI's server-filtered mode.
`createServerPickerSearch` binds a response's record IDs to its query. The shared root immediately hides
options and guards Enter and item selection while the response belongs to another query, including
the debounce interval. It also rejects an item outside the current response even if an obsolete option
node is still mounted. Input events invalidate selection synchronously, before the next source render.
Current-query identity is independent of selected-label hydration, mapped option objects and label text.
`RecordSelect` permits its explicit None action once the search settles; it never exempts record rows.

`useCompanySearch` and `useRecordTargetSearch` tag response pages separately from hydrated selections.
Obsolete requests are aborted and cannot publish a newer query's choices. Empty or failed pages never
turn selected records into selectable fallback results. Note and provenance adapters preserve Base UI's
change reason so restored labels do not become search queries. Workflow simulation and both import
linkers provide their query-owned response snapshots through the same contract, including import retry
options. The shared root preserves server metadata matches whose display name does not contain the query.

Server-filtered consumers explicitly opt in to automatic highlighting. When a pending response settles,
the shared input uses Base UI's keyboard navigation to highlight the first ready result; the public
boolean highlight mode otherwise only reacts to input events.

Both normal and chip inputs prevent Enter from submitting while a picker is open or its query is
uncommitted. Base UI selects the highlighted result. With no result, Enter leaves the query available
for correction. Composition Enter (`isComposing`, an active composition, or legacy key code 229) must
neither select nor submit. Filtering waits for composition to finish. Cancelling a search restores a
committed selection and releases the collapsed field's normal Enter behavior.

New contact uses `usePickerDraft` to retain unresolved company text through blur. Submission checks
that draft independently of focus: choose a result, explicitly empty the search, or choose company
creation in the business-card flow before creating the contact. Its inline hint becomes an error after
a blocked submission. Escape with an existing company cancels the query and restores that selection;
Escape without a selection preserves the unresolved draft.

Radix dialogs intercept Escape in document capture before Base UI's popup can handle it. The shared
`DialogContent` therefore prevents its own dismissal while a nested picker is expanded, leaving Base UI
to close that picker. Radix select/menu dismissal and Base UI drawer nesting retain their primitive
behavior. A separate Escape on a dirty form opens its discard confirmation. Mount that confirmation
inside its parent root, after its content, so the parent's focus scope remains active. The confirmation
explicitly focuses Keep editing, stops its Escape before other document capture listeners can reopen
the prompt, and
restores the previously focused field on cancellation. Both dialog
and drawer close controls use the bilingual `UI.close` catalogue key.

The document merge-field menu cancels the menu's default trigger focus restoration and focuses the
editor at its retained selection when closing, including Escape. Continued typing follows the inserted token.


Searchable toolbar filters keep their Radix menu semantics while explicitly focusing the search field
on open. Down/Up or Tab/Shift+Tab from the field enters the available menu items; Tab from an item
returns to search, and Up from the first item also returns there. Escape dismisses and restores the
trigger. Input editing stays outside menu typeahead, and composition keystrokes do not move focus or dismiss the menu.

## Zone labels

The four zone consumers share `useTimeZoneSearch`: Profile, Workspace identity, report delivery,
and Ask Connex briefing preferences. Persisted values remain IANA identifiers. Labels combine the
localized zone name, its UTC offset at the time the options are built, and the readable identifier.
Search accepts English and Japanese zone names, IANA values, offsets and common city/country/zone
aliases (for example Japan, 日本, 東京 and JST). Offsets describe the displayed instant; recurring
schedule execution still uses the stored zone's daylight-saving rules. A bounded formatter cache
reuses locale/zone/style formatters without freezing the instant used to calculate seasonal offsets.

## Consumer inventory

All 23 consumers of the shared combobox were audited:

- `account/ProfilePanel.tsx`
- `activity/activities/ActivityDialog.tsx`
- `activity/notes/NoteDialog.tsx`
- `activity/tasks/TaskDialog.tsx`
- `ask-connex/AskConnexCommandCenter.tsx`
- `import/ImportDialog.tsx`
- `import/InteractionHistoryImportDialog.tsx`
- `introductions/LogIntroDialog.tsx`
- `records/BulkAssignOwnerDialog.tsx`
- `records/BulkChangeStageDialog.tsx`
- `records/BulkTagDialog.tsx`
- `records/RecordSelect.tsx`
- `records/contacts/ChangeCompanyDialog.tsx`
- `records/contacts/ContactConnections.tsx`
- `records/contacts/ContactProvenanceDialog.tsx` (already used the filtered render function)
- `records/contacts/NewContactDialog.tsx`
- `records/deals/ApprovalStepApproverPicker.tsx`
- `records/deals/DealLineItems.tsx`
- `records/deals/DealTeamDialog.tsx`
- `records/deals/NewDealDialog.tsx`
- `records/deals/QuickEditDealSheet.tsx`
- `reports/ScheduleDialog.tsx`
- `settings/WorkspaceIdentityPanel.tsx`

Paths above are relative to `frontend/app/components/`. The three `RecordSelect` consumers were
also checked: `activity/tasks/EditTaskSheet.tsx`, `activity/activities/EditActivitySheet.tsx`, and
`settings/workflows/WorkflowSimulationDialog.tsx`. They inherit the shared filtering and keyboard
behavior without another combobox implementation.

### Server search bindings

Every previous `filter={null}` path uses the shared snapshot guard:

| Consumer | Source / choices verified |
| --- | --- |
| `records/contacts/NewContactDialog.tsx` | `useCompanySearch`, company |
| `records/contacts/ChangeCompanyDialog.tsx` | `useCompanySearch`, company |
| `records/deals/NewDealDialog.tsx` | `useCompanySearch`, company |
| `records/deals/QuickEditDealSheet.tsx` | `useCompanySearch`, company |
| `records/RecordSelect.tsx` → `activity/activities/EditActivitySheet.tsx` | `useRecordTargetSearch`, contact and deal |
| `records/RecordSelect.tsx` → `activity/tasks/EditTaskSheet.tsx` | `useRecordTargetSearch`, deal (assignee/contact remain local) |
| `records/RecordSelect.tsx` → `settings/workflows/WorkflowSimulationDialog.tsx` | `useWorkflowEditor`, company/contact/deal/task |
| `records/contacts/ContactConnections.tsx` | `useRecordTargetSearch`, contact |
| `records/contacts/ContactProvenanceDialog.tsx` | `useRecordTargetSearch`, referrer contact |
| `activity/notes/NoteDialog.tsx` | `useRecordTargetSearch` through `ContactActionsMenu.tsx`, `DealActionsMenu.tsx`, `me/TimelineRow.tsx`; contact and deal |
| `import/ImportDialog.tsx` | query/request-keyed company/contact/deal record and retry choices |
| `import/InteractionHistoryImportDialog.tsx` | query/request-keyed contact and retry choices |

This inventory is a source/type audit. Automated regressions exercise the shared selection boundary,
actual RecordSelect contact/deal adapters, ContactConnections, note contact/deal fields, workflow
simulation and the existing company form. Browser execution is owned by CI.

## Regression evidence

`serverPickerSafety.test.tsx` covers debounce and pending requests for the activity contact/deal,
task deal and ContactConnections pickers; selected-label retention; note contact/deal fields; workflow
simulation; out-of-order contact/deal responses; a still-mounted stale option; and composition/retry
selection at the shared boundary. `companyPickerSafety.test.tsx` exercises delayed and out-of-order search responses, server-only
metadata matches, failed searches, and the actual New contact form's blur/submission and cancellation
paths. `pickerInputSafety.test.tsx` exercises real Base UI and Radix primitives: bounded filtered collections,
server filtering, Japanese composition, Enter selection and unmatched queries, nested Escape, safe
confirmation focus, field restoration, and localized close labels. `timeZoneSearch.test.ts` covers
EN/JA friendly searches, persisted aliases and seasonal offsets. `documentToolbarFocus.test.tsx`
checks real Tiptap selection/focus after menu selection and Escape. The browser spec
`picker-input-safety.spec.ts` checks New contact Enter and Escape in EN/JA on desktop and phone,
including the persisted company relationship, and immediate typing after a merge field.

## Discard-confirmation inventory

These 14 surfaces now mount their confirmation inside the parent dialog/drawer root:

- `activity/activities/ActivityDialog.tsx`
- `activity/notes/NoteDialog.tsx`
- `activity/tasks/EditTaskSheet.tsx`
- `activity/tasks/TaskDialog.tsx`
- `ask-connex/AskConnexMissionCreate.tsx`
- `marketing/campaigns/NewCampaignDialog.tsx`
- `me/MyWorkQueue.tsx`
- `records/companies/NewCompanyDialog.tsx`
- `records/contacts/NewContactDialog.tsx`
- `records/deals/ApprovalStepApproversDialog.tsx`
- `records/deals/NewDealDialog.tsx`
- `records/quick-edit/QuickEditSheetShell.tsx`
- `reports/GoalDialog.tsx`
- `reports/ScheduleDialog.tsx`

`actions/MobileDealDiscardGuard.tsx` was also audited: it already renders inside the mobile creation
flow's drawer. Its shared confirmation receives the same safe focus behavior without a placement change.
