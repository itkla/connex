import { UserIcon } from '@heroicons/react/24/outline';

import ProtectedMediaImage from '@/app/components/ProtectedMediaImage';
import { cn } from '@/lib/utils';

type PartySize = 'sm' | 'md';

const SIZE_CLASS: Record<PartySize, string> = {
    sm: 'size-8',
    md: 'size-10',
};

const ICON_CLASS: Record<PartySize, string> = {
    sm: 'size-4',
    md: 'size-5',
};

/**
 * Round avatar for a contact in an introduction, taking just the image URL (the contact's name is
 * always shown alongside it). Mirrors {@code ContactAvatar} without requiring a full Contact object.
 */
export default function PartyAvatar({
    imageUrl,
    size = 'sm',
}: {
    imageUrl?: string | null;
    size?: PartySize;
}) {
    const fallback = (
        <span className="flex h-full w-full items-center justify-center bg-muted-foreground/40">
            <UserIcon className={cn('text-muted-foreground', ICON_CLASS[size])} aria-hidden />
        </span>
    );
    return (
        <div className={cn('shrink-0 overflow-hidden rounded-full bg-muted ring-1 ring-border', SIZE_CLASS[size])}>
            <ProtectedMediaImage
                src={imageUrl}
                alt=""
                loading="lazy"
                decoding="async"
                className="h-full w-full object-cover"
                fallback={fallback}
            />
        </div>
    );
}
