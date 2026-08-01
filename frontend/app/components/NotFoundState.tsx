import { MagnifyingGlassIcon } from '@heroicons/react/24/outline';

import PageState, { type PageStateAction } from '@/app/components/PageState';

export type { PageStateAction as NotFoundAction };

/**
 * 404 presentation rendered by `not-found.tsx` segment files. Each segment supplies its
 * own copy and return-to-list destinations so a dead record link lands somewhere useful
 * rather than on a generic dead end.
 * @param title localized heading
 * @param body localized explanation of what was not found
 * @param actions return-to-list destinations; the first is emphasized
 */
export default function NotFoundState({
    title,
    body,
    actions,
}: {
    title: string;
    body: string;
    actions: ReadonlyArray<PageStateAction>;
}) {
    return <PageState icon={MagnifyingGlassIcon} title={title} body={body} actions={actions} />;
}
