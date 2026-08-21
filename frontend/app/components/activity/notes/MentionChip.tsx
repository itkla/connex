"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { EnvelopeIcon, UserIcon } from "@heroicons/react/24/outline";

import { getUserById } from "@/app/lib/api";
import { type User } from "@/app/lib/types";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/components/ui/hover-card";

import { PreviewSkeleton } from "./RecordPreview";

function MemberPreview({ id }: { id: number }) {
    const t = useTranslations("ActivityNotesReferencePreview");
    const [user, setUser] = useState<User | null>(null);
    const [loading, setLoading] = useState(true);
    useEffect(() => {
        let cancelled = false;
        getUserById(id)
            .then((value) => {
                if (!cancelled) setUser(value);
            })
            .catch(() => {})
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, [id]);
    if (loading) return <PreviewSkeleton />;
    if (!user) return <p className="text-sm text-muted-foreground">{t("memberUnavailable")}</p>;
    return (
        <div className="flex gap-3">
            <Avatar size="lg" className="ring-1 ring-border">
                {user.profilePictureUrl ? (
                    <AvatarImage src={user.profilePictureUrl} alt={user.displayName} />
                ) : (
                    <AvatarFallback>
                        <UserIcon className="size-4 text-muted-foreground" />
                    </AvatarFallback>
                )}
            </Avatar>
            <div className="min-w-0 flex-1">
                <p className="truncate font-semibold text-foreground">{user.displayName}</p>
                <p className="truncate text-sm text-muted-foreground">@{user.username}</p>
                {user.email ? (
                    <p className="mt-1 flex items-center gap-1 truncate text-xs text-muted-foreground">
                        <EnvelopeIcon className="size-3 shrink-0" />
                        {user.email}
                    </p>
                ) : null}
            </div>
        </div>
    );
}

/**
 * Renders a member @-mention inside note content: an accent-coloured, focusable
 * link to the member's profile, with a hover preview card. Reachable without
 * hover — the card is a supplementary enhancement.
 */
export default function MentionChip({ id, label }: { id: number; label: string }) {
    return (
        <HoverCard>
            <HoverCardTrigger
                render={
                    <Link
                        href={`/users/${id}`}
                        onClick={(event) => event.stopPropagation()}
                        className="rounded-sm px-0.5 font-medium text-brand-dark transition-colors duration-150 hover:bg-brand-light/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/40"
                    >
                        @{label}
                    </Link>
                }
            />
            <HoverCardContent>
                <MemberPreview id={id} />
            </HoverCardContent>
        </HoverCard>
    );
}
