import { cn } from '@/lib/utils';

/**
 * Static stand-in rendered while the interactive sidebar streams in. The real sidebar reads the
 * query string, so it is wrapped in a Suspense boundary to keep the surrounding page prerenderable;
 * this placeholder mirrors the expanded sidebar's box so the shell does not shift when it swaps in.
 *
 * @param className - the surface classes shared with the real sidebar
 */
export default function SidebarFallback({ className }: { className?: string }) {
    return (
        <div className="h-dvh p-2">
            <div
                aria-hidden
                className={cn('flex min-h-0 w-64 flex-col p-6', className)}
            />
        </div>
    );
}
