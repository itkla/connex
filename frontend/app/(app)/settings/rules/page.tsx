import { redirect } from "next/navigation";

/** Rules moved to the workflows surface; keep old bookmarks working. */
export default function RulesSettingsPage() {
    redirect("/workflows");
}
