import { notFound } from "next/navigation";

import WorkflowEditor from "@/app/components/settings/workflows/WorkflowEditor";

export default async function EditWorkflowPage({ params }: { params: Promise<{ workflowId: string }> }) {
    const { workflowId: rawWorkflowId } = await params;
    if (!/^[1-9]\d*$/.test(rawWorkflowId)) notFound();
    const workflowId = Number(rawWorkflowId);
    if (!Number.isInteger(workflowId) || workflowId > 2_147_483_647) notFound();
    return <WorkflowEditor workflowId={workflowId} />;
}
