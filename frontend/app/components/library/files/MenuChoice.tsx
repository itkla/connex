import type { IconType } from '@/app/components/library/files/fileMeta';
import { DropdownMenuItem } from '@/components/ui/dropdown-menu';
import { CheckIcon } from '@heroicons/react/24/outline';

export default function MenuChoice({
    label,
    active,
    onSelect,
    Icon,
    count,
}: {
    label: string;
    active: boolean;
    onSelect: () => void;
    Icon?: IconType;
    count?: number;
}) {
    return (
        <DropdownMenuItem onSelect={onSelect}>
            {Icon && <Icon className="size-4 text-muted-foreground" />}
            <span className="flex-1">{label}</span>
            {typeof count === 'number' && !active && (
                <span className="text-xs tabular-nums text-muted-foreground">{count}</span>
            )}
            {active && <CheckIcon className="size-4 text-brand-dark" />}
        </DropdownMenuItem>
    );
}