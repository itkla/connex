import type { Metadata } from "next";

export const metadata: Metadata = {
    title: "Library / Files",
    description: "Manage your organization's files",
};

export default function FilesLayout({
    children,
}: Readonly<{
    children: React.ReactNode;
}>) {
    return children;
}