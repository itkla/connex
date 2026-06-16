'use client';

import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';

import type { Attachment } from '@/app/lib/types';
import { classifyKind } from '@/app/components/library/files/fileMeta';
import FileGlyph from '@/app/components/library/files/FileGlyph';
import { formatFileSize, formatShortDate } from '@/app/lib/utils';

/**
 * Dashboard widget: the most recently added files, with a storage-usage footer.
 * Each row deep-links into the Files hub with the file's detail panel open.
 * @param files - The list of attachments to display.
 * @param total - The total number of attachments.
 * @param totalSize - The total size of the attachments.
 * @returns 
 */
export default function RecentFiles({
    files,
    total,
    totalSize,
}: {
    files: Attachment[];
    total: number;
    totalSize: number;
}) {
    const t = useTranslations('DashboardPage');
    const locale = useLocale();

    return (
        <div className="flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-card">
            {files.length === 0 ? (
                <p className="flex-1 px-4 py-10 text-center text-sm text-muted-foreground">{t('noFiles')}</p>
            ) : (
                <ul className="flex-1 divide-y divide-border">
                    {files.map((a) => {
                        const kind = classifyKind(a.contentType, a.fileName);
                        return (
                            <li key={a.id}>
                                <Link
                                    href={`/library/files?file=${a.id}`}
                                    className="flex items-center gap-3 px-4 py-2.5 transition-colors hover:bg-muted/50"
                                >
                                    <FileGlyph attachment={a} kind={kind} />
                                    <div className="min-w-0 flex-1">
                                        <p className="truncate text-sm font-medium text-foreground">{a.fileName}</p>
                                        <p className="truncate text-xs text-muted-foreground">
                                            {a.entityLabel || formatFileSize(a.size)}
                                        </p>
                                    </div>
                                    <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
                                        {formatShortDate(a.createdAt, locale)}
                                    </span>
                                </Link>
                            </li>
                        );
                    })}
                </ul>
            )}
            {total > 0 && (
                <div className="border-t border-border px-4 py-2.5 text-xs tabular-nums text-muted-foreground">
                    {t('storageUsed', { count: total, size: formatFileSize(totalSize) })}
                </div>
            )}
        </div>
    );
}