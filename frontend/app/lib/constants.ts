import type { SourceType } from '@/app/components/library/files/fileMeta';

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];
type SortKey = 'newest' | 'oldest' | 'name' | 'largest';
const SORT_KEYS: SortKey[] = ['newest', 'oldest', 'name', 'largest'];
const SORT_LABEL_KEY: Record<SortKey, string> = {
    newest: 'sortNewest',
    oldest: 'sortOldest',
    name: 'sortName',
    largest: 'sortLargest',
};
const SOURCE_LABEL_KEY: Record<SourceType, string> = {
    company: 'sourceCompany',
    person: 'sourcePerson',
    deal: 'sourceDeal',
    user: 'sourceUser',
};