import { renderToStaticMarkup } from "react-dom/server";
import { NextIntlClientProvider } from "next-intl";
import { describe, expect, it } from "vitest";

import WorkflowValidationSummary from "@/app/components/settings/workflows/WorkflowValidationSummary";
import { workflowIsReadyToPublish } from "@/app/components/settings/workflows/workflowValidation";
import type { WorkflowValidation } from "@/app/lib/types";
import en from "@/messages/en/workspace.json";
import ja from "@/messages/ja/workspace.json";

const publishable: WorkflowValidation = {
    draftRevision: 2,
    valid: true,
    canPublish: true,
    systemAuthoringAllowed: true,
    requiredPermissions: [],
    missingPermissions: [],
    errors: [],
};

describe("workflow publication validation", () => {
    it("requires a valid definition, publication permission, and no errors", () => {
        expect(workflowIsReadyToPublish(publishable)).toBe(true);
        expect(workflowIsReadyToPublish({ ...publishable, valid: false })).toBe(false);
        expect(workflowIsReadyToPublish({ ...publishable, canPublish: false })).toBe(false);
        expect(workflowIsReadyToPublish({
            ...publishable,
            errors: [{ code: "legacy_projection_unsupported", nodeId: null, edgeId: null, fieldPath: null, params: {} }],
        })).toBe(false);
    });

    for (const [locale, messages] of [["en", en], ["ja", ja]] as const) {
        const render = (validation: WorkflowValidation) => renderToStaticMarkup(
            <NextIntlClientProvider locale={locale} messages={messages} timeZone="UTC">
                <WorkflowValidationSummary
                    validation={validation}
                    diagnosticMessage={() => messages.WorkspaceWorkflows.diagnostics.legacy_projection_unsupported}
                    onSelectDiagnostic={() => {}}
                />
            </NextIntlClientProvider>,
        );

        it(`${locale}: shows the publication blocker even when the definition is valid`, () => {
            const markup = render({
                ...publishable,
                canPublish: false,
                errors: [{ code: "legacy_projection_unsupported", nodeId: null, edgeId: null, fieldPath: null, params: {} }],
            });
            expect(markup).toContain(messages.WorkspaceWorkflows.diagnostics.legacy_projection_unsupported.replaceAll("'", "&#x27;"));
            expect(markup).not.toContain(messages.WorkspaceWorkflows.validationReady);
        });

        it(`${locale}: doesn't claim readiness when publication is refused without diagnostics`, () => {
            expect(render({ ...publishable, canPublish: false })).not.toContain(messages.WorkspaceWorkflows.validationReady);
        });

        it(`${locale}: confirms readiness only when publication is allowed without errors`, () => {
            expect(render(publishable)).toContain(messages.WorkspaceWorkflows.validationReady);
        });
    }
});
