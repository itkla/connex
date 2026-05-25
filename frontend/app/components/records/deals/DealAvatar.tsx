import { cn } from "@/lib/utils";
import { CurrencyDollarIcon } from "@heroicons/react/24/outline";

type AvatarSize = 'small' | 'medium' | 'large';

const SIZE_CLASS: Record<AvatarSize, string> = {
    small: 'h-8 w-8',
    medium: 'h-12 w-12',
    large: 'h-16 w-16',
};

const ICON_CLASS: Record<AvatarSize, string> = {
    small: 'size-4',
    medium: 'size-6',
    large: 'size-8',
};

export default function DealAvatar({ type = 'small' }: { type?: AvatarSize }) {
    return (
        <div className={cn(
            "shrink-0 overflow-hidden rounded-2xl bg-gradient-to-br from-brand-light to-brand-dark ring-1 ring-black/5 flex items-center justify-center",
            SIZE_CLASS[type],
        )}>
            <CurrencyDollarIcon className={cn("text-white", ICON_CLASS[type])} />
        </div>
    );
}