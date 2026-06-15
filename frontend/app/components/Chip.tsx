// like a tag, but smaller and more compact

export default function Chip({ type = "default", color = "", children }: { type: "default" | "success" | "warning" | "error"; color: string; children: React.ReactNode }) {
    const baseClasses = "inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider";
    const colorClasses = {
        default: "bg-neutral-200 text-neutral-600",
        success: "bg-green-500 text-white",
        warning: "bg-yellow-500 text-white",
        error: "bg-red-500 text-white",
    };
    const colorClass = colorClasses[type];
    return (
        <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider text-muted-foreground" style={{ backgroundColor: color }}>
            <span className="mr-1">●</span>{children}
        </span>
    );
}