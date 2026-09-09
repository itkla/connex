import { headers } from "next/headers";

import WorkflowEditor from "@/app/components/settings/workflows/WorkflowEditor";
import { getCapabilitiesResultFromCookie } from "@/app/lib/api";

export default async function NewWorkflowPage() {
    const capabilities = await getCapabilitiesResultFromCookie(
        (await headers()).get("cookie"),
    );
    return (
        <WorkflowEditor
            definitionAuthoringEnabled={capabilities.ok && capabilities.data.workflowDefinitionSchemaVersion === 2}
            triggeredSendEnabled={capabilities.ok
                && capabilities.data.workflowTriggeredSend === true}
        />
    );
}
