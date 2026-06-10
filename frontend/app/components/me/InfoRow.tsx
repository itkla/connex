export default
function InfoRow({
    label,
    value,
    href,
}: {
    label: string;
    value: string;
    href?: string;
}) {
    return (
        <div className="flex flex-col gap-1 px-6 py-4">
            <dt className="text-sm text-neutral-500">{label}</dt>
            <dd className="text-base wrap-break-word text-gray-800">
                {href && value ? (
                    <a href={href} target="_blank" rel="noreferrer" className="text-brand hover:underline">{value}</a>
                ) : value}
            </dd>
        </div>
    );
}
