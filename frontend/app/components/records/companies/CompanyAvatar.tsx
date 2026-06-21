import { type Company } from "@/app/lib/types";
import { cn } from "@/lib/utils";
import { BuildingOffice2Icon } from "@heroicons/react/24/outline";

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

export default function CompanyAvatar({ company, type = 'small' }: { company: Company; type?: AvatarSize }) {
    return (
        // company logos are squircles. contact avatars are circles
        <div className={cn("shrink-0 overflow-hidden rounded-2xl bg-muted ring-1 ring-border", SIZE_CLASS[type])}>
            {company.logoUrl ? (
                <img src={company.logoUrl} alt="" loading="lazy" decoding="async" className="h-full w-full object-contain bg-white" />
            ) : (
                <div className="h-full w-full flex items-center justify-center bg-muted-foreground/40">
                    <BuildingOffice2Icon className={cn("text-muted-foreground", ICON_CLASS[type])} />
                </div>
            )}
        </div>
    );
}