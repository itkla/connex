import { Button } from "@/components/ui/button";
import { useTranslations } from "next-intl";

type T = ReturnType<typeof useTranslations>;

/**
 * No results component for the files browser
 * @param t the translations object
 * @param onClear the function to call when the clear button is clicked
 * @returns the no results component
 */
export default function NoResults({ t, onClear }: { t: T; onClear: () => void }) {
    return (
        <div className="rounded-2xl bg-card px-6 py-20 text-center ring-1 ring-border">
            <h2 className="text-lg font-semibold text-foreground">{t('noResultsTitle')}</h2>
            <p className="mx-auto mt-1.5 max-w-sm text-sm text-muted-foreground">{t('noResultsBody')}</p>
            <Button variant="outline" className="mt-6" onClick={onClear}>
                {t('clearFilters')}
            </Button>
        </div>
    );
}