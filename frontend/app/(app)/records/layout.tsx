import type { Metadata } from "next";

export const metadata: Metadata = {
    title: "Records",
    description: "Manage your records",
};

export default function RecordsLayout({
    children,
}: Readonly<{
    children: React.ReactNode;
}>) {
    return children;
}
