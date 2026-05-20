import { type Contact } from "@/app/lib/types";
import { cn } from "@/lib/utils";
import { UserIcon } from "@heroicons/react/24/outline";

type AvatarSize = 'small' | 'medium' | 'large' | 'xlarge' | '2xlarge';

const SIZE_CLASS: Record<AvatarSize, string> = {
    small: 'h-8 w-8',
    medium: 'h-12 w-12',
    large: 'h-16 w-16',
    xlarge: 'h-24 w-24',
    '2xlarge': 'h-32 w-32',
};

const ICON_CLASS: Record<AvatarSize, string> = {
    small: 'size-4',
    medium: 'size-8',
    large: 'size-10',
    xlarge: 'size-12',
    '2xlarge': 'size-16',
};

export default function ContactAvatar({ contact, type = 'small' }: { contact: Contact; type?: AvatarSize; upload?: boolean }) {
    return (
        // ContactAvatars are always round. company logos are squircles
        <div className={cn("shrink-0 overflow-hidden rounded-full bg-neutral-200 ring-1 ring-black/5", SIZE_CLASS[type])}>
            {contact.imageUrl ? (
                <img src={contact.imageUrl} alt="" className="h-full w-full object-cover" />
            ) : (
                <div className="h-full w-full flex items-center justify-center bg-gray-400">
                    <UserIcon className={cn("text-white", ICON_CLASS[type])} />
                </div>
            )}
        </div>
    )
}
