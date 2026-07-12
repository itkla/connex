import { FolderOpenIcon } from '@heroicons/react/24/outline';
import { Button } from '@/components/ui/button';
import Link from 'next/link';
import { useTranslations } from 'next-intl';

type T = ReturnType<typeof useTranslations>;

/**
 * Empty state component for the files browser.
 * @param t - The translations object.
 * @returns The empty state component.
 */
export default function EmptyState({ t }: { t: T }) {
    return (
        <div className="rounded-2xl border border-border bg-card px-6 py-20 text-center">
            <div className="mx-auto flex size-14 items-center justify-center rounded-2xl bg-brand-light text-brand-dark">
                <FolderOpenIcon className="size-7" />
            </div>
            <h2 className="mt-5 text-lg font-semibold text-foreground">{t('emptyTitle')}</h2>
            <p className="mx-auto mt-1.5 max-w-sm text-sm text-muted-foreground">{t('emptyBody')}</p>
            <Button asChild variant="brand" className="mt-6">
                <Link href="/records/companies">{t('emptyCta')}</Link>
            </Button>
        </div>
    );
}
