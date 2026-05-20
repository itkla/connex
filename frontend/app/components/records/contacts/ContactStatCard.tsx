import Link from 'next/link';
import { ArrowRightIcon } from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import StatCard from '@/app/components/me/StatCard';

export default function ContactStatCard({
    label,
    value,
    subtitle,
    addAction,
    viewHref,
}: {
    label: string;
    value: number;
    subtitle?: string;
    addAction?: React.ReactNode;
    viewHref?: string;
}) {
    return (
        <div className="relative">
            <StatCard label={label} value={value} subtitle={subtitle} />
            {(addAction || viewHref) ? (
                <div className="absolute right-2 top-2 flex items-center gap-0.5">
                    {addAction}
                    {viewHref ? (
                        <Button
                            asChild
                            variant="ghost"
                            size="icon-sm"
                            title={`View all ${label.toLowerCase()}`}
                            className="text-neutral-500 hover:text-black cursor-pointer"
                        >
                            <Link href={viewHref}>
                                <ArrowRightIcon className="size-4" />
                                <span className="sr-only">View all {label}</span>
                            </Link>
                        </Button>
                    ) : null}
                </div>
            ) : null}
        </div>
    );
}
