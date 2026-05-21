'use client';

import { EllipsisIcon, EyeIcon, PencilIcon, MailIcon, PhoneIcon, TrashIcon } from 'lucide-react';
import { useRouter } from 'next/navigation';
import {
    DropdownMenu,
    DropdownMenuTrigger,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
} from '@/components/ui/dropdown-menu';
import { copyToClipboard } from '@/app/lib/utils';
import { toast } from 'sonner';

interface ContactCardProps {
    id: number;
    name?: string;
    title?: string;
    company?: string;
    email?: string;
    phone?: string;
    imageUrl?: string;
    onQuickEdit?: () => void;
    onDelete?: () => void;
}

export default function ContactCard({
    id,
    name = 'Tahm Kench',
    title = 'CTO',
    company = '',
    email,
    phone,
    imageUrl,
    onQuickEdit,
    onDelete,
}: ContactCardProps) {
    const router = useRouter();

    function openContactPage() {
        router.push(`/records/contacts/${id}`);
    }



    return (
        <div
            className="relative w-64 max-w-full rounded-2xl bg-gradient-to-br from-brand-light via-brand to-brand-dark hover:shadow-lg transition-shadow duration-300 hover:scale-105 cursor-pointer transition-all duration-300 transition-ease-in-out"
            onClick={openContactPage}
        >
            <div className="aspect-square w-full overflow-hidden rounded-2xl bg-neutral-200 shadow-[0_10px_25px_-5px_rgba(0,0,0,0.35)] ring-1 ring-black/5">
                {imageUrl ? (
                    <img
                        src={imageUrl}
                        alt={name}
                        className="h-full w-full object-cover"
                    />
                ) : (
                    <div
                        className="h-full w-full"
                        // TODO: find something better than this
                        style={{
                            background:
                                'linear-gradient(180deg, #cdd5dc 0%, #b6bfc6 60%, #9aa4ad 100%)',
                        }}
                        aria-hidden="true"
                    />
                )}
            </div>

            <div className="px-3 pt-3 pb-3 pr-11 text-white">
                <div className="min-w-0">
                    <h3 className="text-base font-semibold leading-tight">
                        {name}
                    </h3>
                    <p className="mt-0.5 truncate text-xs font-medium uppercase tracking-wide opacity-80">
                        {title}
                    </p>
                    <p className="mt-1 truncate text-sm opacity-90">
                        {company}
                    </p>
                </div>
            </div>

            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <button
                        type="button"
                        aria-label="Contact actions"
                        onClick={(e) => e.stopPropagation()}
                        className="absolute bottom-3 right-3 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-white/20 text-white transition hover:bg-white/30"
                    >
                        <EllipsisIcon className="size-4" />
                    </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" onClick={(e) => e.stopPropagation()}>
                    <DropdownMenuItem onSelect={() => router.push(`/records/contacts/${id}`)}>
                        <EyeIcon className="size-4" />
                        View
                    </DropdownMenuItem>
                    <DropdownMenuItem
                        onSelect={(e) => {
                            e.preventDefault();
                            onQuickEdit?.();
                        }}
                    >
                        <PencilIcon className="size-4" />
                        Quick edit
                    </DropdownMenuItem>
                    {email && (
                        <DropdownMenuItem onSelect={() =>
                            copyToClipboard(email, 'Email') ? toast.success('Email copied') : toast.error('Failed to copy email')
                        }>
                            <MailIcon className="size-4" />
                            Copy email
                        </DropdownMenuItem>
                    )}
                    {phone && (
                        <DropdownMenuItem onSelect={() =>
                            copyToClipboard(phone, 'Phone') ? toast.success('Phone copied') : toast.error('Failed to copy phone')
                        }>
                            <PhoneIcon className="size-4" />
                            Copy phone
                        </DropdownMenuItem>
                    )}
                    <DropdownMenuSeparator />
                    <DropdownMenuItem
                        className="text-destructive hover:bg-red-500/10"
                        onSelect={(e) => {
                            e.preventDefault();
                            onDelete?.();
                        }}
                    >
                        <TrashIcon className="size-4 text-destructive" />
                        Delete
                    </DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>
        </div>
    );
}