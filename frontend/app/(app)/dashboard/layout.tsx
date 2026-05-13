import type { Metadata } from "next";

export const metadata: Metadata = {
    title: "Dashboard",
    description: "Your organization at a glance",
};

export default function DashboardLayout({
    children,
}: Readonly<{
    children: React.ReactNode;
}>) {
    return children;
}
