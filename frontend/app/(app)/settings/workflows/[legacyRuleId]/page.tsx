import { cookies } from "next/headers";
import { notFound, permanentRedirect } from "next/navigation";

import { ApiError, resolveLegacyWorkflow } from "@/app/lib/api";

export default async function LegacyWorkflowRedirectPage({
    params,
}: {
    params: Promise<{ legacyRuleId: string }>;
}) {
    const { legacyRuleId: rawLegacyRuleId } = await params;
    if (!/^[1-9]\d*$/.test(rawLegacyRuleId)) notFound();
    const legacyRuleId = Number(rawLegacyRuleId);
    if (!Number.isInteger(legacyRuleId) || legacyRuleId > 2_147_483_647) notFound();
    const cookie = (await cookies()).toString();
    let workflowId: number;
    try {
        const resolution = await resolveLegacyWorkflow(legacyRuleId, { headers: { cookie } });
        workflowId = resolution.workflowId;
    } catch (error) {
        if (error instanceof ApiError && error.status === 404) notFound();
        throw error;
    }
    permanentRedirect(`/workflows/${workflowId}`);
}
