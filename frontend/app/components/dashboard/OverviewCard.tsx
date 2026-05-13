import Link from 'next/link';
import { ArrowUpRightIcon } from '@heroicons/react/16/solid';
import { cn } from '@/lib/utils';

export default function OverviewCard({
    label,
    value,
    icon: Icon,
    href,
    className,
    description,
}: {
    label: string;
    value: number;
    icon: React.ComponentType<{ className?: string }>;
    href?: string;
    className?: string;
    description?: string;
}) {
    const inner = (
        <div className={cn("flex h-full flex-col rounded-2xl bg-neutral-100 px-5 py-4 ring-1 ring-black/5 transition group-hover:bg-neutral-200/70", className)}>
            <div className="flex items-center justify-between">
                <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-white text-neutral-700 ring-1 ring-black/5">
                    <Icon className="size-4" />
                </span>
                {href ? (
                    <ArrowUpRightIcon className="size-4 text-neutral-400 transition group-hover:text-brand-dark" />
                ) : null}
            </div>
            <span className="mt-4 text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                {label}
            </span>
            <span className="mt-1 text-4xl leading-none text-black tabular-nums">
                {value.toLocaleString()}
            </span>
            {description ? (
                <span className="mt-1 text-xs text-neutral-400 pt-2">{description}</span>
            ) : null}
        </div>
    );

    if (href) {
        return (
            <Link href={href} className="group">
                {inner}
            </Link>
        );
    }
    return inner;
}