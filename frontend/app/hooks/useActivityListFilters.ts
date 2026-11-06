'use client';

import type { Dispatch, SetStateAction } from 'react';
import { usePathname, useSearchParams } from 'next/navigation';
import { parseDeepLinkId, writeOwnedParamsToUrl } from '@/app/hooks/listStateUrl';
import { useOwnedUrlParams } from '@/app/hooks/useOwnedUrlParams';

/** Restores bounded record filters, including links from contact, company and deal detail pages. */
export function activityListIds(params: Pick<URLSearchParams, 'get'>, key: string, alias?: string): Set<string> {
    const values = (params.get(key) ?? '').split(',');
    if (alias) values.push(params.get(alias) ?? '');
    return new Set(values.filter((value) => parseDeepLinkId(value) !== null).slice(0, 50));
}

/** Reads record filters reactively so navigation replaces context and shallow writes retain it. */
export function useActivityListFilters() {
    const params = useSearchParams();
    const pathname = usePathname();
    const personFilter = activityListIds(params, 'persons', 'contactId');
    const companyFilter = activityListIds(params, 'companies', 'companyId');
    const dealFilter = activityListIds(params, 'deals', 'dealId');
    const memberFilter = activityListIds(params, 'members');
    const setter = (key: string, alias?: string): Dispatch<SetStateAction<Set<string>>> => (value) => {
        const current = activityListIds(new URLSearchParams(window.location.search), key, alias);
        const next = typeof value === 'function' ? value(current) : value;
        writeOwnedParamsToUrl(pathname, {
            [key]: [...next].join(',') || undefined,
            ...(alias ? { [alias]: undefined } : {}),
        });
    };
    const setPersonFilter = setter('persons', 'contactId');
    const setCompanyFilter = setter('companies', 'companyId');
    const setDealFilter = setter('deals', 'dealId');
    const setMemberFilter = setter('members');
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
