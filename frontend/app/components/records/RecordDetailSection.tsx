import type { ReactNode } from 'react';

import {
    type RecordDetailSectionId,
    recordDetailSectionId,
} from '@/app/components/records/recordDetailGrammar';
import { cn } from '@/lib/utils';

type RecordDetailSectionProps = {
    recordKind: 'contact' | 'company' | 'deal';
    section: RecordDetailSectionId;
    children: ReactNode;
    className?: string;
    'aria-label'?: string;
};

/**
 * Thin landmark wrapper for a #843 record-detail grammar slot.
 * Keeps section identity stable for adapters without imposing layout.
 */
export default function RecordDetailSection({
    recordKind,
    section,
    children,
    className,
    'aria-label': ariaLabel,
}: RecordDetailSectionProps) {
    return (
        <section
            id={recordDetailSectionId(recordKind, section)}
            data-record-section={section}
            aria-label={ariaLabel}
            className={cn(className)}
        >
            {children}
        </section>
    );
}
