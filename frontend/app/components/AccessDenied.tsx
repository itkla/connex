import { LockClosedIcon } from '@heroicons/react/24/outline';

import PageState, { type PageStateAction } from '@/app/components/PageState';

/**
 * 403 presentation for a resource the caller is authenticated for but not permitted to
 * see. Kept distinct from `NotFoundState` on purpose: telling a user "this doesn't
 * exist" when the real answer is "you may not view it" is both dishonest and, for a
 * record they can name, actively confusing.
 *
 * Offers more than a single dead-end link because a 403 also arises from a stale or
 * foreign workspace cookie, not only a role gap — so the caller can supply both a way
 * back and a way to re-establish session or workspace context.
 *
 * Stays string-driven rather than reading a fixed message namespace so it can also be
 * used from client trees; `AccessDeniedPage` is the localized route-level wrapper.
 * @param title localized heading
 * @param body localized explanation of why access was refused
 * @param actions destinations the caller can still reach; the first is emphasized
 */
export default function AccessDenied({
    title,
    body,
    actions = [],
}: {
    title: string;
    body: string;
    actions?: ReadonlyArray<PageStateAction>;
}) {
    return <PageState icon={LockClosedIcon} title={title} body={body} actions={actions} />;
}
