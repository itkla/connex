import Link from 'next/link';
import type { ComponentType, ReactNode } from 'react';

import Rise from '@/app/components/motion/Rise';
import { Button } from '@/components/ui/button';

/** One destination offered from a page state, rendered in the order supplied. */
export type PageStateAction = {
    href: string;
    label: string;
    variant?: 'default' | 'ghost' | 'outline';
};

/**
 * Shared full-page state presentation behind the not-found, access-denied and
 * permissions-unavailable surfaces, so all three read as one grammar rather than three
 * hand-rolled variations.
 *
 * A Server Component taking already-localized strings: each caller owns its copy and
 * destinations, and a dead-end route ships no client JavaScript beyond the entrance.
 * The icon tile is intentionally muted — these states report a normal outcome, not a
 * fault, and should not borrow the alarm of `ErrorState`.
 * @param icon the heroicon to render in the tile
 * @param title localized heading
 * @param body localized explanation
 * @param actions destinations offered; the first is emphasized
 * @param action interactive recovery control supplied by a client component when navigation alone
 * cannot retry the failed operation
 */
export default function PageState({
    icon: Icon,
    title,
    body,
    actions,
    action,
}: {
    icon: ComponentType<{ className?: string }>;
    title: string;
    body: string;
    actions: ReadonlyArray<PageStateAction>;
    action?: ReactNode;
}) {
    return (
        <div className="flex min-h-[60vh] items-center justify-center px-6 py-16">
            <Rise className="flex w-full max-w-md flex-col items-center text-center">
                <div className="flex size-14 items-center justify-center rounded-2xl bg-muted text-muted-foreground">
                    <Icon className="size-7" />
                </div>
                <h2 className="mt-5 text-lg font-semibold text-foreground">{title}</h2>
                <p className="mt-1.5 max-w-sm text-sm text-muted-foreground">{body}</p>
                {action || actions.length > 0 ? (
                    <div className="mt-6 flex flex-wrap items-center justify-center gap-2">
                        {action}
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
            </Rise>
        </div>
    );
}
