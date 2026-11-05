# Picker keys and focus

`components/ui/combobox.tsx` keeps Base UI's collection, composition, selection and dismissal behavior.
`ComboboxList` requires a typed render function. Static mapped children bypass Base UI's filtered collection
and must never be used. Put `ComboboxEmpty` beside the list within `ComboboxContent`.
The root defaults to highlighting the first match and rendering at most 100 matching rows; filtering
runs across the full supplied collection before that limit. Existing paged/debounced server searches
remain authoritative where `filter={null}` is used. The New contact company picker additionally filters
its currently loaded company names so Enter cannot choose a stale nonmatching result.

Both normal and chip inputs prevent Enter from submitting while a picker is open or its query is
uncommitted. Base UI selects the highlighted result. With no result, Enter leaves the query available
for correction. Composition Enter (`isComposing`, an active composition, or legacy key code 229) must
neither select nor submit. Filtering waits for composition to finish.

Radix dialogs intercept Escape in document capture before Base UI's popup can handle it. The shared
`DialogContent` therefore prevents its own dismissal while a nested picker is expanded, leaving Base UI
to close that picker. Radix select/menu dismissal and Base UI drawer nesting retain their primitive
behavior. A separate Escape on a dirty form opens its discard confirmation. Mount that confirmation
inside its parent root, after its content, so the parent's focus scope remains active. The confirmation
explicitly focuses Keep editing, consumes its own Escape before it can reach a Base UI parent, and
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
schedule execution still uses the stored zone's daylight-saving rules.

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

`pickerInputSafety.test.tsx` exercises real Base UI and Radix primitives: bounded filtered collections,
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
