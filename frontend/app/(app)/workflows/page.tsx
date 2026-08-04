import WorkflowsPanel from "@/app/components/settings/workflows/WorkflowsPanel";
import { PageShell } from "@/app/components/PageShell";

export default function WorkflowsPage() {
    return (
        <PageShell tier="wide">
            <WorkflowsPanel />
        </PageShell>
    );
}
