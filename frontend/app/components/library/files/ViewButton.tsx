import type { IconType } from '@/app/components/library/files/fileMeta';


/**
 * View button component for the files browser.
 * @param Icon - The icon component.
 * @param label - The label of the button.
 * @param active - Whether the button is active.
 * @param onClick - The function to call when the button is clicked.
 * @returns The view button component.
 */
export default function ViewButton({
    Icon,
    label,
    active,
    onClick,
}: {
    Icon: IconType;
    label: string;
    active: boolean;
    onClick: () => void;
}) {
    return (
        <button
            type="button"
            onClick={onClick}
            aria-pressed={active}
            title={label}
            aria-label={label}
            className={`flex items-center justify-center rounded-full p-2 transition ${
                active ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'
            }`}
        >
            <Icon className="size-4" />
        </button>
    );
}