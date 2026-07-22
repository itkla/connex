import { notFound } from "next/navigation";

import WorkflowEditor from "@/app/components/settings/workflows/WorkflowEditor";

export default async function EditWorkflowPage({ params }: { params: Promise<{ id: string }> }) {
    const { id } = await params;
    const ruleId = Number(id);
    if (!Number.isInteger(ruleId) || ruleId <= 0) {
        notFound();
    }

    return <WorkflowEditor ruleId={ruleId} />;
}
