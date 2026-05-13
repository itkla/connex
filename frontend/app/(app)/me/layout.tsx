import type { Metadata } from "next";

export const metadata: Metadata = {
    title: "Me",
    description: "Your profile and settings",
};

export default function MeLayout({
    children,
}: Readonly<{
    children: React.ReactNode;
}>) {
    return children;
}
