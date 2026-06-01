import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getCurrentUserFromCookie, searchFromCookie } from "@/app/lib/api";
import SearchResultsView from "@/app/components/SearchResultsView";

export default async function SearchPage({
    searchParams,
}: {
    searchParams: Promise<{ query?: string }>;
}) {
    const cookie = (await headers()).get("cookie");
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect("/auth/login");
    }

    const { query = "" } = await searchParams;
    const results = await searchFromCookie(cookie, query);

    return <SearchResultsView query={query} results={results} />;
}