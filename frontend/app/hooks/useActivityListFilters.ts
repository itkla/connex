'use client';

import { useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { parseDeepLinkId } from '@/app/hooks/listStateUrl';
import { useOwnedUrlParams } from '@/app/hooks/useOwnedUrlParams';

/** Restores bounded record filters, including links from contact, company and deal detail pages. */
export function activityListIds(params: Pick<URLSearchParams, 'get'>, key: string, alias?: string): Set<string> {
    const values = (params.get(key) ?? '').split(',');
    if (alias) values.push(params.get(alias) ?? '');
    return new Set(values.filter((value) => parseDeepLinkId(value) !== null).slice(0, 50));
}

/** Keeps record context in the URL so paging and reload retain the user's selection. */
export function useActivityListFilters() {
    const params = useSearchParams();
    const [personFilter, setPersonFilter] = useState(() => activityListIds(params, 'persons', 'contactId'));
    const [companyFilter, setCompanyFilter] = useState(() => activityListIds(params, 'companies', 'companyId'));
    const [dealFilter, setDealFilter] = useState(() => activityListIds(params, 'deals', 'dealId'));
    const [memberFilter, setMemberFilter] = useState(() => activityListIds(params, 'members'));
    useOwnedUrlParams({
        persons: [...personFilter].join(',') || undefined,
        companies: [...companyFilter].join(',') || undefined,
        deals: [...dealFilter].join(',') || undefined,
        members: [...memberFilter].join(',') || undefined,
        contactId: undefined,
        companyId: undefined,
        dealId: undefined,
    });
    return {
        personFilter, setPersonFilter, companyFilter, setCompanyFilter, dealFilter, setDealFilter,
        memberFilter, setMemberFilter,
        hasRecordContext: personFilter.size + companyFilter.size + dealFilter.size > 0,
        params: {
            persons: [...personFilter].map(Number),
            companies: [...companyFilter].map(Number),
            deals: [...dealFilter].map(Number),
            members: [...memberFilter].map(Number),
        },
    };
}
