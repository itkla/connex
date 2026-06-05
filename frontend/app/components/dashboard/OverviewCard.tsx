import { ArrowUpRightIcon } from '@heroicons/react/16/solid';
import { cn } from '@/lib/utils';
import MotionCard from '@/app/components/dashboard/MotionCard';
import CountUp from '@/app/components/dashboard/CountUp';

export default function OverviewCard({
    label,
    value,
    icon: Icon,
    href,
    index = 0,
    className,
    description,
}: {
    label: string;
    value: number;
    icon: React.ComponentType<{ className?: string }>;
    href?: string;
    index?: number;
    className?: string;
    description?: string;
}) {
    return (
        <MotionCard
            href={href}
            index={index}
            className={cn(
                'flex h-full flex-col rounded-2xl border border-black/[0.07] bg-white px-5 py-4 transition-shadow duration-200 hover:shadow-[0_14px_34px_-16px_rgba(0,0,0,0.22)]',
                className,
            )}
        >
            <div className="flex items-center justify-between">
                <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-brand-light text-brand-dark transition-transform duration-200 group-hover:scale-105">
                    <Icon className="size-4" />
                </span>
                {href ? (
                    <ArrowUpRightIcon className="size-4 text-neutral-300 transition-[transform,color] duration-200 group-hover:-translate-y-0.5 group-hover:translate-x-0.5 group-hover:text-brand-dark" />
                ) : null}
            </div>
            <span className="mt-4 text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                {label}
            </span>
            <CountUp value={value} className="mt-1 text-4xl leading-none text-neutral-900 tabular-nums" />
            {description ? (
                <span className="mt-1 pt-2 text-xs text-neutral-400">{description}</span>
            ) : null}
        </MotionCard>
    );
}
