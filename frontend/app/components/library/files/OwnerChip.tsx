import { Attachment } from '@/app/lib/types';
import { sourceMetaFor } from '@/app/components/library/files/fileMeta';
import { useTranslations } from 'next-intl';
import Link from 'next/link';

type T = ReturnType<typeof useTranslations>;

/**
 * Owner chip component for the files browser
 * @param attachment the attachment object
 * @param t the translations object
 * @param className optional class names for the component
 * @returns 
 */
export default function OwnerChip({ attachment, t, className = '' }: { attachment: Attachment; t: T; className?: string }) {
    const meta = sourceMetaFor(attachment.entityType);
    const label = attachment.entityLabel || t('unknownRecord');

    const inner = (
        <>
            {meta ? <meta.Icon className="size-3.5 shrink-0" /> : null}
            <span className="truncate">{label}</span>
        </>
    );

    if (!meta || !attachment.entityId) {
        return <span className={`inline-flex items-center gap-1.5 text-muted-foreground ${className}`}>{inner}</span>;
    }

    return (
        <Link
            href={meta.href(attachment.entityId)}
            title={t('viewRecord', { label })}
            aria-label={t('viewRecord', { label })}
            className={`inline-flex items-center gap-1.5 text-muted-foreground transition-colors hover:text-brand ${className}`}
        >
            {inner}
        </Link>
    );
}