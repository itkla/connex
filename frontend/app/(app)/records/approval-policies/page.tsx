import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getApprovalPoliciesFromCookie, getCurrentUserFromCookie } from "@/app/lib/api";
import { type ApprovalPolicy } from "@/app/lib/types";
import ApprovalPoliciesBrowser from "@/app/components/records/approval-policies/ApprovalPoliciesBrowser";

export default async function ApprovalPoliciesPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const policies: ApprovalPolicy[] = await getApprovalPoliciesFromCookie(cookie);

    return <ApprovalPoliciesBrowser policies={policies} />;
}
