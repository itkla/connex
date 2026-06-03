"use client";

import { useRouter } from "next/navigation";
import { type User } from "@/app/lib/types";
import UserAvatar from "./UserAvatar";

export default function UserCard({ user }: { user: User }) {
    const router = useRouter();
    return (
        <button
            type="button"
            onClick={() => router.push(`/users/${user.id}`)}
            className="group flex w-full cursor-pointer flex-col items-center gap-4 rounded-2xl bg-white p-6 text-center ring-1 ring-black/5 transition duration-200 hover:-translate-y-1 hover:shadow-[0_18px_40px_-16px_rgb(0_0_0/0.28)] hover:ring-black/10"
        >
            <div className="transition-transform duration-200 group-hover:scale-[1.03]">
                <UserAvatar user={user} type="2xlarge" />
            </div>
            <div className="min-w-0 w-full">
                <h3 className="truncate font-semibold text-neutral-900">{user.displayName}</h3>
                <p className="truncate text-xs text-neutral-500">@{user.username}</p>
                <p className="mt-1.5 truncate text-sm text-neutral-600">{user.email}</p>
            </div>
        </button>
    );
}