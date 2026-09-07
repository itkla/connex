import { notFound } from "next/navigation";
import { headers } from "next/headers";

import { PageShell } from "@/app/components/PageShell";
import { WorkflowRecipeDetail } from "@/app/components/settings/workflows/recipes/WorkflowRecipeGallery";
import { getCapabilitiesResultFromCookie } from "@/app/lib/api";
import { isWorkflowRecipeKey } from "@/app/lib/workflowOperations";

export default async function WorkflowRecipePage({ params }: { params: Promise<{ recipeKey: string }> }) {
    const { recipeKey } = await params;
    if (!isWorkflowRecipeKey(recipeKey)) notFound();
    const capabilities = await getCapabilitiesResultFromCookie((await headers()).get("cookie"));
    return (
        <PageShell>
            <WorkflowRecipeDetail recipeKey={recipeKey} definitionAuthoringEnabled={capabilities.ok && capabilities.data.workflowDefinitionSchemaVersion === 2} />
        </PageShell>
    );
}
