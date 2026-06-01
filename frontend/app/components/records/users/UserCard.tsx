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
            className="flex w-64 max-w-full cursor-pointer flex-col items-center gap-3 rounded-2xl bg-white p-6 text-center ring-1 ring-black/5 transition duration-300 hover:scale-105 hover:shadow-lg"
        >
            <UserAvatar user={user} type="xlarge" />
            <div className="min-w-0 w-full">
                <h3 className="truncate text-base font-semibold text-black">{user.displayName}</h3>
                <p className="truncate text-xs text-neutral-500">@{user.username}</p>
                <p className="mt-1 truncate text-sm text-neutral-600">{user.email}</p>
            </div>
        </button>
    );
}