/**
 * The error-voice lint gates and their burndown inventory (#1337).
 *
 * Two rules keep failure copy in one dialect: nothing imports `sonner` except the branded toast
 * helpers, and no raw error text is carried into a toast. Both ship in error mode. The selectors
 * catch the idioms the audited inventory actually contains — determined evasion (aliased imports,
 * multi-step variable laundering, non-toast render paths) is review's job, not this gate's.
 *
 * **The burndown contract.** `SONNER_IMPORT_EXCLUSIONS` and `RAW_ERROR_TOAST_EXCLUSIONS` are the
 * committed inventory of files that still violate their rule — the denominator for every "every
 * call site" claim in the workstream. Entries may only be **removed**, as each file migrates onto
 * `toastApiError`; adding one is how the debt grows back. `EXCLUSION_BASELINE` records the size of
 * each list when the gates landed and must never be raised — `test/unit/errorVoiceInventory.test.ts`
 * fails the build if a list outgrows its baseline, names a file that no longer exists, or falls out
 * of order. Both lists are empty when the workstream exits.
 *
 * `SONNER_ALLOWED` is a different thing and is permanent: the two modules that are *supposed* to
 * reach for `sonner` — the branded helpers every other module calls, and the Toaster that renders
 * them.
 */

/** The modules that legitimately depend on `sonner`. Not part of the burndown. */
export const SONNER_ALLOWED = [
    "app/lib/toast.ts",
    "components/ui/sonner.tsx",
];

/** Files that still import `sonner` directly instead of the branded helpers in `app/lib/toast.ts`. */
export const SONNER_IMPORT_EXCLUSIONS = [
    "app/components/DraftResumeBridge.tsx",
    "app/components/calendar/CalendarShell.tsx",
    "app/components/import/RecordsActions.tsx",
    "app/components/records/RecordsRenderView.tsx",
    "app/components/records/companies/CompaniesBrowser.tsx",
    "app/components/records/companies/ContactsGrid.tsx",
    "app/components/records/companies/EditCompanySheet.tsx",
    "app/components/records/contacts/ChangeCompanyDialog.tsx",
    "app/components/records/contacts/ContactCard.tsx",
    "app/components/records/deals/DealsBrowser.tsx",
    "app/components/records/deals/EditDealSheet.tsx",
    "app/components/records/pipelines/EditPipelineSheet.tsx",
    "app/hooks/useNotifications.tsx",
];

/** Files that still carry an error's own text into a toast instead of mapping it to product copy. */
export const RAW_ERROR_TOAST_EXCLUSIONS = [
    "app/auth/logout/page.tsx",
    "app/auth/reset-password/ResetPasswordForm.tsx",
    "app/components/account/ChangeEmailDialog.tsx",
    "app/components/account/ConnectionsPanel.tsx",
    "app/components/account/MembershipPanel.tsx",
    "app/components/actions/create/CompanyCreateContainer.tsx",
    "app/components/actions/create/ContactCreateContainer.tsx",
    "app/components/actions/create/DealCreateContainer.tsx",
    "app/components/activity/activities/ActivitiesBrowser.tsx",
    "app/components/activity/notes/NotesBrowser.tsx",
    "app/components/activity/tasks/TasksBrowser.tsx",
    "app/components/activity/tasks/TasksKanban.tsx",
    "app/components/calendar/CalendarNewDealContainer.tsx",
    "app/components/dashboard/CoolingRelationships.tsx",
    "app/components/invite/AcceptInvite.tsx",
    "app/components/invite/AcceptInviteLink.tsx",
    "app/components/library/documents/DocumentTemplatesBrowser.tsx",
    "app/components/library/tags/TagsBrowser.tsx",
    "app/components/marketing/campaigns/CampaignDelivery.tsx",
    "app/components/marketing/campaigns/CampaignDetail.tsx",
    "app/components/marketing/campaigns/CampaignExportPanel.tsx",
    "app/components/marketing/campaigns/CampaignsBrowser.tsx",
    "app/components/marketing/campaigns/UnsubscribeConfirm.tsx",
    "app/components/me/TimelineRow.tsx",
    "app/components/organization/DataRequestDialog.tsx",
    "app/components/organization/DataRequestsPanel.tsx",
    "app/components/organization/OrgAiProviderPanel.tsx",
    "app/components/organization/OrgAllowedDomainsPanel.tsx",
    "app/components/organization/OrgAuditPanel.tsx",
    "app/components/organization/OrganizationIdentityForm.tsx",
    "app/components/organization/OrganizationLifecyclePanel.tsx",
    "app/components/organization/OrganizationOverviewPanel.tsx",
    "app/components/records/BulkAssignOwnerDialog.tsx",
    "app/components/records/BulkChangeStageDialog.tsx",
    "app/components/records/BulkTagDialog.tsx",
    "app/components/records/EngineEvaluationPanel.tsx",
    "app/components/records/approval-policies/ApprovalPoliciesBrowser.tsx",
    "app/components/records/approval-policies/ApprovalPolicyDialog.tsx",
    "app/components/records/companies/CompaniesBrowser.tsx",
    "app/components/records/companies/CompanyActionsMenu.tsx",
    "app/components/records/companies/EditCompanySheet.tsx",
    "app/components/records/contacts/ChangeCompanyDialog.tsx",
    "app/components/records/contacts/ContactActionsMenu.tsx",
    "app/components/records/contacts/ContactCard.tsx",
    "app/components/records/contacts/ContactConnections.tsx",
    "app/components/records/contacts/ContactProvenanceDialog.tsx",
    "app/components/records/contacts/ContactQualificationDialog.tsx",
    "app/components/records/contacts/ContactStageDialog.tsx",
    "app/components/records/contacts/RestrictionsDialog.tsx",
    "app/components/records/contacts/TagEditor.tsx",
    "app/components/records/deals/DealActionsMenu.tsx",
    "app/components/records/deals/DealDocuments.tsx",
    "app/components/records/deals/DealLineItems.tsx",
    "app/components/records/deals/DealTaskList.tsx",
    "app/components/records/deals/DealTeamDialog.tsx",
    "app/components/records/deals/DealsBrowser.tsx",
    "app/components/records/deals/DealsKanban.tsx",
    "app/components/records/deals/EditDealSheet.tsx",
    "app/components/records/pipelines/EditPipelineSheet.tsx",
    "app/components/records/pipelines/PipelinesBrowser.tsx",
    "app/components/records/users/NewUserDialog.tsx",
    "app/components/reports/AskConnexComposer.tsx",
    "app/components/reports/GoalsBoard.tsx",
    "app/components/reports/ReportBuilderBoard.tsx",
    "app/components/reports/ReportDocumentBoard.tsx",
    "app/components/reports/ReportsBoard.tsx",
    "app/components/reports/ScheduleManager.tsx",
    "app/components/settings/CustomFieldsPanel.tsx",
    "app/components/settings/DeliveryPanel.tsx",
    "app/components/settings/EmailPanel.tsx",
    "app/components/settings/MembersPanel.tsx",
    "app/components/settings/QualificationCriteriaPanel.tsx",
    "app/components/settings/RolesPanel.tsx",
    "app/components/settings/SsoPanel.tsx",
    "app/components/settings/WorkspaceIdentityPanel.tsx",
];

/**
 * The current size of each list — the burndown ledger. Every commit that migrates a file lowers the
 * matching count in the same change, so the inventory test holds the lists exactly at this number
 * and regressions cannot hide in slack between an old high-water mark and today's list.
 */
export const EXCLUSION_BASELINE = {
    sonnerImports: 13,
    rawErrorToasts: 75,
};

const SONNER_IMPORT_MESSAGE =
    "Import the branded toast helpers from '@/app/lib/toast' instead of 'sonner', and report request "
    + "failures with toastApiError from '@/app/lib/errorMessages' so every failure speaks one voice (#1337).";

const RAW_ERROR_TOAST_MESSAGE =
    "Never put an error's own text in a toast — backend prose, engine prose, and status codes are not "
    + "product copy. Map the failure with toastApiError/userMessageFor from '@/app/lib/errorMessages', "
    + "passing your operation's title key as the fallback (#1337).";

/**
 * Flags `err.message` and `String(err)` reaching a toast directly — through the branded helpers, a
 * raw `sonner` call, or any local wrapper with "toast" in its name, since wrapping the call is the
 * one thing that used to hide the leak from review.
 */
const RAW_TEXT_IN_TOAST_SELECTOR =
    ":matches("
    + "CallExpression[callee.name=/[Tt]oast/],"
    + "CallExpression[callee.object.name='toast'][callee.property.name=/^(error|warning|info|success|message|custom)$/]"
    + ") :matches("
    + "MemberExpression[property.name='message'], "
    + "CallExpression[callee.name='String'], "
    + "CallExpression[callee.property.name='toString'], "
    + "TemplateLiteral Identifier[name=/^(e|err|error|cause)$/]"
    + ")";

/**
 * Flags the same leak one step removed — `const message = err instanceof ApiError ? err.message : …`
 * — which is how most call sites launder raw text into a toast on the next line. Scoped to variable
 * initialization so an `Error` subclass may still build its own developer-facing message from a cause.
 */
const RAW_TEXT_LIFTED_TO_VARIABLE_SELECTOR =
    "VariableDeclarator ConditionalExpression[test.operator='instanceof'][consequent.property.name='message']";

/**
 * Builds the flat-config entries for both gates, each scoped away from its committed exclusions.
 * @returns eslint flat config objects, in the order they must be applied
 */
export function errorVoiceConfig() {
    return [
        {
            files: ["**/*.{ts,tsx,js,jsx,mjs}"],
            ignores: [...SONNER_ALLOWED, ...SONNER_IMPORT_EXCLUSIONS],
            rules: {
                "no-restricted-imports": [
                    "error",
                    { paths: [{ name: "sonner", message: SONNER_IMPORT_MESSAGE }] },
                ],
            },
        },
        {
            files: ["**/*.{ts,tsx,js,jsx,mjs}"],
            ignores: [...RAW_ERROR_TOAST_EXCLUSIONS],
            rules: {
                "no-restricted-syntax": [
                    "error",
                    { selector: RAW_TEXT_IN_TOAST_SELECTOR, message: RAW_ERROR_TOAST_MESSAGE },
                    { selector: RAW_TEXT_LIFTED_TO_VARIABLE_SELECTOR, message: RAW_ERROR_TOAST_MESSAGE },
                ],
            },
        },
    ];
}
