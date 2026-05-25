import { headers } from "next/headers";
import { getDealsFromCookie, getCurrentUserFromCookie } from "@/app/lib/api";
import { type Deal } from "@/app/lib/types";
import { redirect } from "next/navigation";
import DealsBrowser from "@/app/components/records/deals/DealsBrowser";

export default async function DealsPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const deals: Deal[] = await getDealsFromCookie(cookie);

    return <DealsBrowser deals={deals} />;
}
