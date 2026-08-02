import Link from 'next/link';
import { LockClosedIcon } from '@heroicons/react/24/outline';

import PageState, { type PageStateAction } from '@/app/components/PageState';
import { Button } from '@/components/ui/button';

const NO_ACTIONS: ReadonlyArray<PageStateAction> = [];

/**
 * 403 presentation for a resource the caller is authenticated for but not permitted to
 * see. Kept distinct from `NotFoundState` on purpose: telling a user "this doesn't
 * exist" when the real answer is "you may not view it" is both dishonest and, for a
 * record they can name, actively confusing.
 *
 * `variant="page"` (default) is the full-page route-level state via {@link PageState},
 * for a whole surface the caller may not see; it can offer more than a single dead-end
 * link because a 403 also arises from a stale or foreign workspace cookie, not only a
 * role gap. `variant="inline"` is the compact in-panel card for a settings or
 * organization tab that stays reachable while one section refuses, so the denial reads
 * as one grammar with the page state rather than a hand-rolled card.
 *
 * Stays string-driven rather than reading a fixed message namespace so it can also be
 * used from client trees; `AccessDeniedPage` is the localized route-level wrapper.
 * @param title localized heading; required for the full-page variant, which must never
 * render a headingless state, and optional for the compact in-panel card
 * @param body localized explanation of why access was refused
 * @param actions destinations the caller can still reach; the first is emphasized
 * @param variant full-page route state, or the compact in-panel card
 */
export type AccessDeniedProps = {
    body: string;
    actions?: ReadonlyArray<PageStateAction>;
} & ({ variant?: 'page'; title: string } | { variant: 'inline'; title?: string });

export default function AccessDenied(props: AccessDeniedProps) {
    const { title, body, actions = NO_ACTIONS } = props;
    if (props.variant === 'inline') {
        return (
            <div className="flex flex-col items-center gap-3 rounded-2xl border border-border bg-card px-6 py-12 text-center">
                <span aria-hidden className="grid size-10 place-items-center rounded-full bg-muted text-muted-foreground">
                    <LockClosedIcon className="size-5" />
                </span>
                <div className="space-y-1">
                    {title ? <p className="text-sm font-semibold text-foreground">{title}</p> : null}
                    <p className="max-w-sm text-sm text-muted-foreground">{body}</p>
                </div>
                {actions.length > 0 ? (
                    <div className="mt-1 flex flex-wrap items-center justify-center gap-2">
                        {actions.map((action, index) => (
                            <Button
                                key={action.href}
                                asChild
                                variant={action.variant ?? (index === 0 ? 'default' : 'ghost')}
                            >
                                <Link href={action.href}>{action.label}</Link>
                            </Button>
                        ))}
                    </div>
                ) : null}
            </div>
        );
    }
    return <PageState icon={LockClosedIcon} title={props.title} body={body} actions={actions} />;
}
