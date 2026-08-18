import { permanentRedirect } from "next/navigation";

/** Automation lives on the workflows surface; this path stays a permanent redirect for old bookmarks. */
export default function RulesSettingsPage() {
    permanentRedirect("/workflows");
}
