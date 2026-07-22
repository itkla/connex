export default function Chip({ color = "", children }: { type: "default" | "success" | "warning" | "error"; color: string; children: React.ReactNode }) {
    return (
        <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider text-muted-foreground" style={{ backgroundColor: color }}>
            <span className="mr-1">●</span>{children}
        </span>
    );
}
