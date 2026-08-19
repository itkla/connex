'use client';

import { useTranslations } from 'next-intl';
import { Bars2Icon, Bars3Icon } from '@heroicons/react/24/outline';

import { SegmentedControl } from '@/components/ui/segmented-control';
import type { RowDensity } from '@/app/hooks/useRecordDensity';

/**
 * Toggles record-table row density between comfortable and compact, mirroring the view-mode toggle so the
 * table toolbar stays visually consistent. Only meaningful in table mode.
 */
export default function DensityToggle({ value, onChange }: { value: RowDensity; onChange: (next: RowDensity) => void }) {
    const t = useTranslations('RecordDensity');
    return (
        <SegmentedControl<RowDensity>
            ariaLabel={t('ariaLabel')}
            value={value}
            onChange={onChange}
            options={[
                { value: 'comfortable', icon: <Bars2Icon className="size-4" />, ariaLabel: t('comfortable') },
                { value: 'compact', icon: <Bars3Icon className="size-4" />, ariaLabel: t('compact') },
            ]}
        />
    );
}
