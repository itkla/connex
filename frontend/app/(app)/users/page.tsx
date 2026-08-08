import { headers } from "next/headers";
import { redirect } from "next/navigation";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { getCurrentUserResultFromCookie, getUsers } from "@/app/lib/api";
import UsersBrowser from "@/app/components/records/users/UsersBrowser";

export default async function UsersPage() {
    const cookie = (await headers()).get("cookie");
    const currentUserResult = await getCurrentUserResultFromCookie(cookie);

    if (!currentUserResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const currentUser = currentUserResult.data;
    if (!currentUser) {
        redirect("/auth/login");
    }

    const users = await getUsers({ headers: { cookie: cookie ?? "" }, cache: "no-store" });

    return <UsersBrowser users={users} />;
}
