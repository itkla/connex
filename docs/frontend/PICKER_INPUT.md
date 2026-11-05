# Picker keys and focus

`components/ui/combobox.tsx` keeps Base UI's collection, composition, selection and dismissal behavior.
`ComboboxList` requires a typed render function. Static mapped children bypass Base UI's filtered collection
and must never be used. Put `ComboboxEmpty` beside the list within `ComboboxContent`.
Local matching normalizes labels and queries with NFKC before Base UI's collation, including
half-width kana, voiced combining marks and full-width Latin. A committed selection still opens the
full collection. The root renders at most 100 matching rows after filtering.

Server-filtered consumers (`filter={null}`) opt in to automatic highlighting only when their option
source tracks query freshness. `useCompanySearch` keeps hydrated `companies` for selected labels and
separate `options` containing only the current server page. Query and selected-id keys invalidate
options immediately, including the debounce interval; obsolete requests are aborted and cannot publish
results for another query. Failure never turns hydrated selections into selectable search results.
All four company pickers pass `itemsPending` and the current `options`, keeping website, industry,
phone and address matching on the server. Root pending guards block both Enter and item selection.
When a pending response settles, the shared input uses Base UI's keyboard navigation to highlight the
first ready result; the public boolean highlight mode otherwise only reacts to input events.

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

## Regression evidence

`companyPickerSafety.test.tsx` exercises delayed and out-of-order search responses, server-only
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
