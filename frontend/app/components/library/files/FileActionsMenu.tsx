import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { EllipsisVerticalIcon } from "@heroicons/react/24/outline";
import { ArrowTopRightOnSquareIcon } from '@heroicons/react/24/outline';
import { ArrowDownTrayIcon } from "@heroicons/react/24/outline";
import { TrashIcon } from '@heroicons/react/24/outline';
import { useTranslations } from "next-intl";
import type { Attachment } from "@/app/lib/types";
import { safeHref } from "@/app/lib/utils";

type T = ReturnType<typeof useTranslations>;

/**
 * File actions menu component for the files browser. fork of the other action menu components
 * @param attachment the attachment object
 * @param t the translations object
 * @param onDelete the function to call when the delete button is clicked
 * @returns the file actions menu component
 */
export default function FileActionsMenu({ attachment, t, onDelete }: { attachment: Attachment; t: T; onDelete: () => void }) {
    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <button
                    type="button"
                    aria-label={t('actionsAria', { name: attachment.fileName })}
                    className="flex size-7 shrink-0 items-center justify-center rounded-full text-muted-foreground opacity-0 transition hover:bg-muted hover:text-foreground focus-visible:opacity-100 aria-expanded:bg-muted aria-expanded:text-foreground aria-expanded:opacity-100 group-hover:opacity-100"
                >
                    <EllipsisVerticalIcon className="size-4" />
                </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-44">
                <DropdownMenuItem asChild>
                    <a href={safeHref(attachment.url)} target="_blank" rel="noopener noreferrer">
                        <ArrowTopRightOnSquareIcon className="size-4 text-muted-foreground" />
                        {t('open')}
                    </a>
                </DropdownMenuItem>
                <DropdownMenuItem asChild>
                    <a href={safeHref(attachment.url)} download={attachment.fileName}>
                        <ArrowDownTrayIcon className="size-4 text-muted-foreground" />
                        {t('download')}
                    </a>
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem
                    className="text-destructive hover:bg-destructive/10"
                    onSelect={(e) => {
                        e.preventDefault();
                        onDelete();
                    }}
                >
                    <TrashIcon className="size-4 text-destructive" />
                    {t('delete')}
                </DropdownMenuItem>
            </DropdownMenuContent>
        </DropdownMenu>
    );
}