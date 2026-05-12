import { Skeleton } from '@/components/ui/skeleton'; // TODO: add skeleton loaders to render before the PRomises resolve

export default function StatCard({
    label,
    value,
    subtitle,
}: {
    label: string;
    value: number;
    subtitle?: string;
}) {
    return (
        <div className="flex flex-col rounded-2xl bg-neutral-100 px-5 py-4 ring-1 ring-black/5">
            <span className="text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                {label}
            </span>
            <span className="mt-2 text-4xl leading-none text-black">
                {/* {value ? value : <Skeleton className="h-10 w-full" />} */}
                {value}
            </span>
            {subtitle ? (
                <span className="mt-1 text-xs text-neutral-500">{subtitle}</span>
            ) : null}
        </div>
    );
}
