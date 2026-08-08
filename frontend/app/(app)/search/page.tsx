import { headers } from "next/headers";
import { redirect } from "next/navigation";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { getCurrentUserResultFromCookie, searchFromCookie } from "@/app/lib/api";
import SearchResultsView from "@/app/components/SearchResultsView";

export default async function SearchPage({
    searchParams,
}: {
    searchParams: Promise<{ query?: string }>;
}) {
    const cookie = (await headers()).get("cookie");
    const userResult = await getCurrentUserResultFromCookie(cookie);

    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
    if (!user) {
        redirect("/auth/login");
    }

    const { query = "" } = await searchParams;
    const results = await searchFromCookie(cookie, query);

    return <SearchResultsView query={query} results={results} />;
}
