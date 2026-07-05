import { redirect } from "next/navigation";

export default function LegacySsoSettingsPage() {
    redirect("/organization/sso");
}
