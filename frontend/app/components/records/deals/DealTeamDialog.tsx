'use client';

import { useEffect, useRef, useState, type WheelEvent } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { Loader2Icon } from 'lucide-react';
import { CheckCircleIcon, UserIcon } from '@heroicons/react/24/outline';

import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter, DialogClose } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Combobox, ComboboxItem, ComboboxList, ComboboxContent, ComboboxEmpty, ComboboxInput } from '@/components/ui/combobox';
import { InputGroupAddon } from '@/components/ui/input-group';
import { DialogStatusCover, resolveDialogStatus } from '@/components/ui/dialog-status-cover';
import UserAvatar from '@/app/components/records/users/UserAvatar';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import {
    getActiveWorkspaceMembers,
    replaceDealCollaborators,
    updateDealOwner,
} from '@/app/lib/api';
import { pendingDealTeamWrites } from '@/app/lib/dealTeam';
import { type User, type WorkspaceMember } from '@/app/lib/types';
import { toastSuccess } from '@/app/lib/toast';
import { cn } from '@/lib/utils';

const comboInputClass =
    'rounded-lg border-0 bg-muted shadow-none ring-1 ring-border dark:bg-muted has-[[data-slot=input-group-control]:focus-visible]:ring-2 has-[[data-slot=input-group-control]:focus-visible]:ring-brand';
const comboLeadIconClass =
    'size-4 text-muted-foreground transition-colors group-focus-within/input-group:text-brand';

type TeamMember = Pick<
    WorkspaceMember,
    'id' | 'username' | 'displayName' | 'email' | 'profilePictureUrl'
>;

function mergeMembers(...groups: TeamMember[][]): TeamMember[] {
    const byId = new Map<number, TeamMember>();
    for (const group of groups) {
        for (const member of group) byId.set(member.id, member);
    }
    return [...byId.values()].sort((left, right) =>
        left.displayName.localeCompare(right.displayName));
}

export default function DealTeamDialog({
    open,
    onOpenChange,
    dealId,
    initialOwnerId,
    initialCollaborators,
}: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    dealId: number;
    initialOwnerId?: number | null;
    initialCollaborators: User[];
}) {
    const t = useTranslations('Notifications');
    const showApiError = useApiErrorToast('Notifications');
    const router = useRouter();
    const [ownerId, setOwnerId] = useState<number | null>(initialOwnerId ?? null);
    const [collaboratorIds, setCollaboratorIds] = useState(() => initialCollaborators.map((user) => user.id));
    const [saving, setSaving] = useState(false);
    const [success, setSuccess] = useState(false);
    const [members, setMembers] = useState<TeamMember[]>(initialCollaborators);
    const [membersLoaded, setMembersLoaded] = useState(false);
    const [membersFailed, setMembersFailed] = useState(false);

    const wasOpen = useRef(open);
    useEffect(() => {
        if (open && !wasOpen.current) {
            setOwnerId(initialOwnerId ?? null);
            setCollaboratorIds(initialCollaborators.map((user) => user.id));
            setSaving(false);
            setSuccess(false);
        }
        wasOpen.current = open;
    }, [open, initialOwnerId, initialCollaborators]);

    useEffect(() => {
        if (!open || membersLoaded) return;
        let cancelled = false;
        getActiveWorkspaceMembers()
            .then((activeMembers) => {
                if (!cancelled) {
                    setMembers(mergeMembers(
                        initialCollaborators,
                        activeMembers.filter((member) => member.status === 'active'),
                    ));
                    setMembersFailed(false);
                    setMembersLoaded(true);
                }
            })
            .catch(() => {
                if (!cancelled) {
                    setMembers(initialCollaborators);
                    setMembersFailed(true);
                }
            });
        return () => {
            cancelled = true;
        };
    }, [open, initialCollaborators, membersLoaded]);

    const membersLoading = open && !membersLoaded && !membersFailed;
    const selectedOwner = members.find((user) => user.id === ownerId) ?? null;
    const candidates = members.filter((user) => user.id !== ownerId);
    const selectedCount = candidates.filter((user) => collaboratorIds.includes(user.id)).length;
    const status = resolveDialogStatus({ isLoading: saving, isSuccess: success });

    const toggleCollaborator = (id: number) =>
        setCollaboratorIds((current) => (current.includes(id) ? current.filter((value) => value !== id) : [...current, id]));

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    const handleOpenChange = (next: boolean) => {
        if (!next && saving) return;
        onOpenChange(next);
    };

    async function save() {
        setSaving(true);
        try {
            const writes = pendingDealTeamWrites(
                { ownerId: initialOwnerId ?? null, collaboratorIds: initialCollaborators.map((user) => user.id) },
                { ownerId, collaboratorIds },
            );
            if (writes.owner) await updateDealOwner(dealId, ownerId);
            if (writes.collaboratorIds) await replaceDealCollaborators(dealId, writes.collaboratorIds);
            toastSuccess(t('dealTeamUpdated'));
            setSaving(false);
            setSuccess(true);
            router.refresh();
            window.setTimeout(() => onOpenChange(false), 900);
        } catch (error) {
            setSaving(false);
            showApiError(error, 'dealTeamError');
        }
    }

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <DialogStatusCover status={status} />

                <div className="px-6 pb-6">
                    <DialogHeader className="ncd-rise -mt-12 mb-5" style={{ animationDelay: '40ms' }}>
                        <DialogTitle className="text-xl font-semibold tracking-tight">{t('dealTeamTitle')}</DialogTitle>
                        <DialogDescription>{t('dealTeamDescription')}</DialogDescription>
                    </DialogHeader>

                    <div className="grid gap-5">
                        <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '90ms' }}>
                            <Label htmlFor="deal-owner">{t('owner')}</Label>
                            <Combobox<TeamMember>
                                items={members}
                                itemToStringLabel={(u: TeamMember) => u.displayName}
                                value={selectedOwner}
                                onValueChange={(u: TeamMember | null) => setOwnerId(u?.id ?? null)}
                            >
                                <ComboboxInput id="deal-owner" placeholder={t('unassigned')} showClear className={comboInputClass}>
                                    <InputGroupAddon align="inline-start">
                                        <UserIcon className={comboLeadIconClass} />
                                    </InputGroupAddon>
                                </ComboboxInput>
                                <ComboboxContent className="pointer-events-auto">
                                    <ComboboxList onWheel={handleListWheel}>
                                        <ComboboxEmpty>
                                            {membersLoading ? t('loadingMembers') : t('noUsers')}
                                        </ComboboxEmpty>
                                        {members.map((u) => (
                                            <ComboboxItem key={u.id} value={u}>
                                                {u.displayName}
                                            </ComboboxItem>
                                        ))}
                                    </ComboboxList>
                                </ComboboxContent>
                            </Combobox>
                        </div>

                        <div className="ncd-rise grid gap-2" style={{ animationDelay: '140ms' }}>
                            <div className="flex items-center justify-between">
                                <Label>{t('collaborators')}</Label>
                                {selectedCount > 0 ? (
                                    <span className="text-xs font-medium text-muted-foreground">{selectedCount}</span>
                                ) : null}
                            </div>
                            <div className="max-h-64 overflow-y-auto rounded-lg ring-1 ring-border">
                                {candidates.length === 0 ? (
                                    <p className="px-3 py-6 text-center text-sm text-muted-foreground">{t('noCollaborators')}</p>
                                ) : (
                                    <ul className="divide-y divide-border">
                                        {candidates.map((u) => {
                                            const selected = collaboratorIds.includes(u.id);
                                            return (
                                                <li key={u.id}>
                                                    <button
                                                        type="button"
                                                        onClick={() => toggleCollaborator(u.id)}
                                                        aria-pressed={selected}
                                                        className={cn(
                                                            'flex w-full items-center gap-3 px-3 py-2.5 text-left transition-colors hover:bg-muted/60',
                                                            selected && 'bg-brand-light/40',
                                                        )}
                                                    >
                                                        <UserAvatar user={u} type="small" />
                                                        <div className="min-w-0 flex-1">
                                                            <p className="truncate text-sm font-medium">{u.displayName}</p>
                                                            <p className="truncate text-xs text-muted-foreground">{u.email}</p>
                                                        </div>
                                                        <CheckCircleIcon
                                                            className={cn(
                                                                'size-5 shrink-0 transition-colors',
                                                                selected ? 'text-brand' : 'text-muted-foreground/30',
                                                            )}
                                                        />
                                                    </button>
                                                </li>
                                            );
                                        })}
                                    </ul>
                                )}
                            </div>
                        </div>

                        <DialogFooter className="ncd-rise mt-1" style={{ animationDelay: '190ms' }}>
                            <DialogClose asChild>
                                <Button type="button" variant="outline" disabled={saving}>{t('cancel')}</Button>
                            </DialogClose>
                            <Button
                                type="button"
                                onClick={() => void save()}
                                variant="brand"
                                disabled={saving || success}
                                className="min-w-24 shadow-sm transition hover:shadow-md"
                            >
                                {saving ? <Loader2Icon className="size-4 animate-spin" /> : t('saveTeam')}
                            </Button>
                        </DialogFooter>
                    </div>
                </div>
            </DialogContent>
        </Dialog>
    );
}
