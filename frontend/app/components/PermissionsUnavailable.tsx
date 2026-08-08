import { ExclamationTriangleIcon } from '@heroicons/react/24/outline';
import type { ReactNode } from 'react';

import PageState, { type PageStateAction } from '@/app/components/PageState';

const NO_ACTIONS: ReadonlyArray<PageStateAction> = [];

/**
 * State for a surface whose permission check could not be evaluated, because the
 * effective-permissions lookup itself failed.
 *
 * Exists to replace two dishonest alternatives. Treating an unreadable permission list as "no
 * permissions" either redirects the user off a page they are entitled to, or answers `AccessDenied`
 * — which tells an admin to ask an administrator, and hides the fact that the fix is to retry. This
 * stays fail-closed, so the content is still withheld, while saying plainly that the check failed
 * rather than implying a verdict.
 *
 * `variant="page"` (default) is the full-page route-level state via {@link PageState};
 * `variant="inline"` is the compact in-panel card for a settings or organization tab that stays
 * reachable while one section cannot resolve its gate, so it reads as one grammar with
 * `AccessDenied` rather than a hand-rolled card. Stays string-driven, and free of hooks, so it can
 * be used from client trees without pulling client JavaScript into the route-level dead ends;
 * {@link PermissionsUnavailablePage} is the localized route-level wrapper.
 *
 * @param title localized heading; required for the full-page variant, which must never render a
 * headingless state, and optional for the compact in-panel card
 * @param body localized explanation that the check, not the caller, is what failed
 * @param actions destinations offered from the full-page variant; the first is emphasized
 * @param action a recovery control supplied by the client tree that owns the failed check
 */
export type PermissionsUnavailableProps = { body: string } & (
    | {
        variant?: 'page';
        title: string;
        actions?: ReadonlyArray<PageStateAction>;
        action?: ReactNode;
    }
    | { variant: 'inline'; title?: string; action?: ReactNode }
);

export default function PermissionsUnavailable(props: PermissionsUnavailableProps) {
    if (props.variant === 'inline') {
        return (
            <div className="flex flex-col items-center gap-3 rounded-2xl border border-border bg-card px-6 py-12 text-center">
                <span aria-hidden className="grid size-10 place-items-center rounded-full bg-muted text-muted-foreground">
                    <ExclamationTriangleIcon className="size-5" />
                </span>
                <div className="space-y-1">
                    {props.title ? <p className="text-sm font-semibold text-foreground">{props.title}</p> : null}
                    <p className="max-w-sm text-sm text-muted-foreground">{props.body}</p>
                </div>
                {props.action ? <div className="mt-1">{props.action}</div> : null}
            </div>
        );
    }
    return (
        <PageState
            icon={ExclamationTriangleIcon}
            title={props.title}
            body={props.body}
            actions={props.actions ?? NO_ACTIONS}
            action={props.action}
        />
    );
}
