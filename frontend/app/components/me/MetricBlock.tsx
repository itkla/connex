// NOTE: not used in /me page anymore, but might be used in other pages so im keeping it

export default function MetricBlock({
    label,
    count,
    children,
}: {
    label: string;
    count: number;
    children: React.ReactNode;
}) {
    return (
        <section className="overflow-hidden rounded-2xl bg-neutral-100 ring-1 ring-black/5">
            <header className="flex items-baseline justify-between gap-4 px-6 pt-5 pb-4">
                <h2 className="text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                    {label}
                </h2>
                <span className="font-['Instrument_Serif'] text-4xl leading-none text-black">
                    {count}
                </span>
            </header>
            <div className="border-t border-neutral-200 bg-white">{children}</div>
        </section>
    );
}