export default function EmptyState({ message }: { message: string }) {
    return <p className="px-6 py-6 text-sm text-muted-foreground">{message}</p>;
}