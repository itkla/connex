import { notFound } from 'next/navigation';

/**
 * Gates the design-system reference gallery to development builds: it is an internal engineering
 * tool, so production builds return a 404 for the whole segment.
 */
export default function DesignSystemLayout({ children }: { children: React.ReactNode }) {
    if (process.env.NODE_ENV !== 'development') {
        notFound();
    }
    return children;
}
