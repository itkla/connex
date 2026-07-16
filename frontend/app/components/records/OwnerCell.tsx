import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import type { WorkspaceMember } from '@/app/lib/types';

/**
 * Presentational owner cell for record browser tables: shows the owning member's avatar and
 * display name, or a muted unassigned label when the record has no owner.
 */
export default function OwnerCell({
    ownerId,
    members,
    unassignedLabel,
}: {
    ownerId: number | null | undefined;
    members: WorkspaceMember[];
    unassignedLabel: string;
}) {
    const owner = ownerId != null ? members.find((member) => member.id === ownerId) : undefined;
    if (!owner) {
        return <span className="text-muted-foreground">{unassignedLabel}</span>;
    }
    return (
        <span className="flex min-w-0 items-center gap-2">
            <Avatar size="sm">
                {owner.profilePictureUrl && <AvatarImage src={owner.profilePictureUrl} alt="" />}
                <AvatarFallback className="bg-brand-light text-[10px] font-medium text-brand-dark">
                    {owner.displayName.slice(0, 1).toUpperCase()}
                </AvatarFallback>
            </Avatar>
            <span className="truncate">{owner.displayName}</span>
        </span>
    );
}
