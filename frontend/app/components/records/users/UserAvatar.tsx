import { type User } from "@/app/lib/types";
import { cn } from "@/lib/utils";

type AvatarSize = "small" | "medium" | "large" | "xlarge" | "2xlarge";

const SIZE_CLASS: Record<AvatarSize, string> = {
    small: "h-8 w-8",
    medium: "h-12 w-12",
    large: "h-16 w-16",
    xlarge: "h-24 w-24",
    "2xlarge": "h-32 w-32",
};

const TEXT_CLASS: Record<AvatarSize, string> = {
    small: "text-sm",
    medium: "text-lg",
    large: "text-2xl",
    xlarge: "text-4xl",
    "2xlarge": "text-5xl",
};

export default function UserAvatar({ user, type = "small" }: { user: User; type?: AvatarSize }) {
    const initial = user.displayName?.slice(0, 1).toUpperCase() || "?";
    return (
        <div className={cn("shrink-0 overflow-hidden rounded-full ring-1 ring-black/5", SIZE_CLASS[type])}>
            {user.profilePictureUrl ? (
                <img src={user.profilePictureUrl} alt="" className="h-full w-full object-cover" />
            ) : (
                <div
                    className={cn(
                        "flex h-full w-full items-center justify-center bg-brand-light font-medium text-brand-dark",
                        TEXT_CLASS[type],
                    )}
                >
                    {initial}
                </div>
            )}
        </div>
    );
}