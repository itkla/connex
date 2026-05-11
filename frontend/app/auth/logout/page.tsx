"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef } from "react";
import { toast } from "sonner";
import { LoaderCircle } from "lucide-react";

import { logout } from "@/app/lib/api";

export default function LogoutPage() {
    const router = useRouter();
    const hasLoggedOut = useRef(false);

    useEffect(() => {
        if (hasLoggedOut.current) {
            return;
        }

        hasLoggedOut.current = true;

        async function signOut() {
            try {
                await logout();
                toast.success("You are now logged out.", {
                    style: {
                        backgroundColor: "#73d200",
                        color: "white",
                    }
                });
            } catch (err) {
                const message = err instanceof Error ? err.message : "Could not sign out";
                toast.error(message, {
                    style: {
                        backgroundColor: "--color-destructive",
                        color: "--color-destructive-foreground",
                    }
                });
            } finally {
                router.replace("/");
                router.refresh();
            }
        }

        void signOut();
    }, [router]);

    return (
        // <div className="flex min-h-screen items-center justify-center bg-white px-6">
        //     <p className="text-base text-black">Signing out...</p>
        // </div>
        <div className="flex min-h-screen items-center justify-center bg-white px-6">
            <LoaderCircle className="size-4 animate-spin" />
            <p className="text-base text-black">Signing out</p>
        </div>
    );
}
