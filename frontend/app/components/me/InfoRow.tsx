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
            <dt className="text-sm text-muted-foreground">{label}</dt>
            <dd className="text-base wrap-break-word text-foreground">
                {href && value ? (
                    <a href={href} target="_blank" rel="noopener noreferrer" className="text-brand hover:underline">{value}</a>
                ) : value}
            </dd>
        </div>
    );
}
