export default 
function InfoRow({ 
    label, 
    value,
}: {
    label: string;
    value: string;
}) {
    return (
        <div className="flex flex-col gap-1 px-6 py-4">
            <dt className="text-sm text-neutral-500">{label}</dt>
            <dd className="text-base break-words text-gray-800">{value}</dd>
        </div>
    );
}
