import type { Metadata } from "next";

export const metadata: Metadata = {
    title: "Me",
    description: "Your relationships and work at a glance",
};

export default function MeLayout({
    children,
}: Readonly<{
    children: React.ReactNode;
}>) {
    return children;
}
