export default function WorkflowsLayout({ children }: { children: React.ReactNode }) {
    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-[100rem] flex-col">{children}</div>
        </div>
    );
}
