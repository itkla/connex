export default function SectionHeader({
    title,
    action,
}: {
    title: string;
    action?: React.ReactNode;
}) {
    return (
        <div className="mb-3 flex h-8 items-center justify-between">
            <h2 className="px-6 text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                {title}
            </h2>
            {action ? <div className="px-1">{action}</div> : null}
        </div>
    );
}