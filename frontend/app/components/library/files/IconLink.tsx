import type { IconType } from '@/app/components/library/files/fileMeta';

/**
 * Icon link component for the files browser.
 * @param href - The href of the link.
 * @param label - The label of the link.
 * @param Icon - The icon component.
 * @param openInNewTab - Whether to open the link in a new tab.
 * @param download - The download attribute.
 * @returns The icon link component.
 */
export default function IconLink({
    href,
    label,
    Icon,
    openInNewTab,
    download,
}: {
    href: string;
    label: string;
    Icon: IconType;
    openInNewTab?: boolean;
    download?: string;
}) {
    return (
        <a
            href={href}
            title={label}
            aria-label={label}
            {...(openInNewTab ? { target: '_blank', rel: 'noopener noreferrer' } : {})}
            {...(download ? { download } : {})}
            className="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
        >
            <Icon className="size-4" />
        </a>
    );
}