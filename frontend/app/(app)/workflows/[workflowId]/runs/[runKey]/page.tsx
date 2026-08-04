import { notFound } from "next/navigation";

import { PageShell } from "@/app/components/PageShell";
import WorkflowRunOperationsDetail from "@/app/components/settings/workflows/operations/WorkflowRunOperationsDetail";

export default async function WorkflowRunPage({
    params,
}: {
    params: Promise<{ workflowId: string; runKey: string }>;
}) {
    const { workflowId: rawWorkflowId, runKey } = await params;
    if (!/^[1-9]\d*$/.test(rawWorkflowId)) notFound();
    if (!/^(?:canonical-[1-9]\d*|legacy-[1-9]\d*)$/.test(runKey)) notFound();
    const workflowId = Number(rawWorkflowId);
    if (!Number.isInteger(workflowId) || workflowId > 2_147_483_647) notFound();
    return (
        <PageShell tier="wide">
            <WorkflowRunOperationsDetail workflowId={workflowId} runKey={runKey} />
        </PageShell>
    );
}
