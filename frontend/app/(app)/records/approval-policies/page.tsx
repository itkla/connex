import { headers } from "next/headers";
import { redirect } from "next/navigation";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { getApprovalPolicies, getCurrentUserResultFromCookie } from "@/app/lib/api";
import { type ApprovalPolicy } from "@/app/lib/types";
import ApprovalPoliciesBrowser from "@/app/components/records/approval-policies/ApprovalPoliciesBrowser";

export default async function ApprovalPoliciesPage() {
    const cookie = (await headers()).get('cookie');
    const userResult = await getCurrentUserResultFromCookie(cookie);

    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;

    if (!user) {
        redirect('/auth/login');
    }

    const policies: ApprovalPolicy[] = await getApprovalPolicies({
        headers: { cookie: cookie ?? "" },
        cache: "no-store",
    });

    return <ApprovalPoliciesBrowser policies={policies} />;
}
