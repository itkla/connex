import type { IconType } from '@/app/components/library/files/fileMeta';
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent } from '@/components/ui/dropdown-menu';
import { ChevronDownIcon } from '@heroicons/react/24/outline';

/**
 * Filter menu component for the files browser
 * @param Icon the icon component
 * @param current the current filter
 * @param active whether the filter is active
 * @param srLabel the screen reader label
 * @param hideChevron whether to hide the chevron
 * @param children the children components
 * @returns the filter menu component
 */
export default function FilterMenu({
    Icon,
    current,
    active,
    srLabel,
    hideChevron,
    children,
}: {
    Icon?: IconType;
    current: string;
    active: boolean;
    srLabel: string;
    hideChevron?: boolean;
    children: React.ReactNode;
}) {
    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <button
                    type="button"
                    aria-label={srLabel}
                    className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-medium ring-1 transition ${
                        active
                            ? 'bg-brand-light text-brand-dark ring-brand-light'
                            : 'bg-muted text-muted-foreground ring-border hover:text-foreground'
                    }`}
                >
                    {Icon && <Icon className="size-3.5" />}
                    <span>{current}</span>
                    {!hideChevron && <ChevronDownIcon className="size-3 opacity-70" />}
                </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-52">
                {children}
            </DropdownMenuContent>
        </DropdownMenu>
    );
}