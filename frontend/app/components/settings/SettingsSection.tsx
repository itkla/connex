import SectionHeader from "@/app/components/dashboard/SectionHeader";
import { cn } from "@/lib/utils";

/**
 * Consistent section header for the workspace settings panels: a clear title with an optional
 * description and a right-aligned action, aligned flush with the cards beneath it. Replaces the
 * legacy uppercase eyebrow so every settings tab reads with the same hierarchy. When `children`
 * are passed the header and content are grouped together; otherwise it renders the header alone.
 *
 * `headingLevel` exists for the consolidated destinations of #1340, where a panel that names its
 * own sections is rendered inside a section named for the job it came from. The nested headings step
 * down to `h3` so the page stays one coherent outline rather than repeating `h2` inside `h2`; the
 * rendered size is unchanged, because the level is a structural fact and not a visual one.
 */
export function SettingsSection({
    title,
    description,
    action,
    children,
    className,
    headingLevel = 2,
}: {
    title: string;
    description?: string;
    action?: React.ReactNode;
    children?: React.ReactNode;
    className?: string;
    headingLevel?: 2 | 3;
}) {
    const Heading = headingLevel === 3 ? "h3" : "h2";
    return (
        <section className={cn("space-y-4", className)}>
            <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
                <div className="space-y-1">
                    <Heading className="text-base font-semibold tracking-tight text-foreground text-balance">
                        {title}
                    </Heading>
                    {description ? (
                        <p className="max-w-prose text-sm text-pretty text-muted-foreground">{description}</p>
                    ) : null}
                </div>
                {action ? <div className="shrink-0">{action}</div> : null}
            </div>
            {children}
        </section>
    );
}

/**
 * Which of its two homes is rendering a panel while #1340 migrates the administration destinations.
 *
 * - `page` is the panel's own route, exactly as it ships: an uppercase eyebrow under the shell's
 *   page title, which is the only heading that route has.
 * - `section` is one section of a consolidated scope destination, where the page is a single outline
 *   of section headings and the eyebrow would read as a third, smaller kind of title.
 */
export type SettingsPanelPresentation = "page" | "section";

/**
 * The heading a panel puts above itself, in whichever home is showing it.
 *
 * The organization panels all open the same way — an eyebrow, then a description paragraph beside
 * it — so the switch between their two presentations is one decision rather than six copies of it.
 * The legacy shape is reproduced exactly, `px-6` alignment included, because those routes must keep
 * rendering as they ship until #1340's redirects retire them.
 *
 * @param presentation - which home is rendering the panel
 * @param title - the panel's own name, identical in both homes
 * @param description - the sentence under it
 * @param action - controls the heading carries, such as a filter or a reload
 * @param descriptionClassName - the legacy paragraph's own classes, where a panel constrains its
 * measure; ignored in the section presentation, which constrains it for every panel
 * @param headingLevel - the level this heading takes in the section presentation, so a panel that
 * names a part of itself sits under its own name rather than beside it
 */
export function SettingsPanelHeading({
    presentation,
    title,
    description,
    action,
    descriptionClassName,
    headingLevel,
}: {
    presentation: SettingsPanelPresentation;
    title: string;
    description: string;
    action?: React.ReactNode;
    descriptionClassName?: string;
    headingLevel?: 2 | 3;
}) {
    if (presentation === "section") {
        return (
            <SettingsSection
                title={title}
                description={description}
                action={action}
                headingLevel={headingLevel}
            />
        );
    }
    return (
        <div>
            <SectionHeader title={title} action={action} />
            <p className={cn("px-6 text-sm text-muted-foreground", descriptionClassName)}>
                {description}
            </p>
        </div>
    );
}
