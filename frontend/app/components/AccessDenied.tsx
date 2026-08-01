import { LockClosedIcon } from '@heroicons/react/24/outline';

import PageState from '@/app/components/PageState';

/**
 * 403 presentation for a resource the caller is authenticated for but not permitted to
 * see. Kept distinct from `NotFoundState` on purpose: telling a user "this doesn't
 * exist" when the real answer is "you may not view it" is both dishonest and, for a
 * record they can name, actively confusing.
 *
 * Stays string-driven rather than reading a fixed message namespace so it can also be
 * used from client trees; `AccessDeniedPage` is the localized route-level wrapper.
 * @param title localized heading
 * @param body localized explanation of why access was refused
 * @param action optional destination the caller can still reach
 */
export default function AccessDenied({
    title,
    body,
    action,
}: {
    title: string;
    body: string;
    action?: { href: string; label: string };
}) {
    return (
        <PageState
            icon={LockClosedIcon}
            title={title}
            body={body}
            actions={action ? [action] : []}
        />
    );
}
