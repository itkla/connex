import { useTranslations } from "next-intl";
import { missingPasswordRequirements } from "@/app/lib/passwordPolicy";
import policy from "@/app/lib/password-policy.json";

/** Shows every new-password requirement before submission and updates its completion state. */
export function PasswordRequirements({ id, password }: { id: string; password: string }) {
    const t = useTranslations("PasswordPolicy");
    const missing = missingPasswordRequirements(password);
    return (
        <ul id={id} className="mt-1.5 space-y-1 px-1 text-sm text-muted-foreground">
            {["length", "byteLength", ...policy.rules.map((rule) => rule.key)].map((key) => (
                <li key={key} className="flex gap-2">
                    <span aria-hidden="true">{missing.includes(key) ? "○" : "✓"}</span>
                    <span><span className="sr-only">{t(missing.includes(key) ? "missing" : "met")}{": "}</span>{t(key)}</span>
                </li>
            ))}
        </ul>
    );
}
