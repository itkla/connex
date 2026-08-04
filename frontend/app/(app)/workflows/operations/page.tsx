import WorkflowOperationsCenter from "@/app/components/settings/workflows/operations/WorkflowOperationsCenter";
import { PageShell } from "@/app/components/PageShell";

export default function WorkflowOperationsPage() {
    return (
        <PageShell tier="wide">
            <WorkflowOperationsCenter />
        </PageShell>
    );
}
