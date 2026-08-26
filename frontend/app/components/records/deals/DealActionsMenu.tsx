'use client';

import { useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { toastSuccess } from '@/app/lib/toast';
import { useTranslations } from 'next-intl';
import { LoaderCircle } from 'lucide-react';
import {
    EllipsisVerticalIcon,
    PencilSquareIcon,
    EyeIcon,
    PaperClipIcon,
    PlusIcon,
    ChatBubbleLeftRightIcon,
    DocumentTextIcon,
    CheckCircleIcon,
    XCircleIcon,
    ArrowUturnLeftIcon,
    TrashIcon,
    UsersIcon,
} from '@heroicons/react/24/outline';

import { useAttachmentUploader } from '@/app/components/attachments/useAttachmentUploader';

import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Button } from '@/components/ui/button';
import { IconButton } from '@/components/ui/icon-button';
import { ButtonGroup } from '@/components/ui/button-group';

import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import DealTeamDialog from '@/app/components/records/deals/DealTeamDialog';
import EditDealSheet from '@/app/components/records/deals/EditDealSheet';
import {
    RecordActivityComposer,
    RecordTaskComposer,
    type RecordComposerAnchor,
} from '@/app/components/records/RecordComposers';
import NoteDialog from '@/app/components/activity/notes/NoteDialog';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import {
    useContactTargetSearch,
    useDealTargetSearch,
} from '@/app/hooks/useRecordTargetSearch';

import { closeDeal, deleteDeal, reopenDeal } from '@/app/lib/api';
import { type Contact, type Deal, type Pipeline, type Stage, type User } from '@/app/lib/types';
import { isDealClosed } from './dealOutcome';

const EMPTY_CONTACTS: Contact[] = [];
const EMPTY_DEALS: Deal[] = [];
const EMPTY_USERS: User[] = [];

export default function DealActionsMenu({
    deal,
    pipelines,
    stagesByPipeline,
    currentUserId,
    personSeeds = EMPTY_CONTACTS,
    dealSeeds = EMPTY_DEALS,
    collaborators = EMPTY_USERS,
}: {
    deal: Deal;
    pipelines: Pipeline[];
    stagesByPipeline: Record<number, Stage[]>;
    currentUserId: number;
    personSeeds?: Contact[];
    dealSeeds?: Deal[];
    collaborators?: User[];
}) {
    const router = useRouter();
    const t = useTranslations('DealsActionsMenu');
    const showApiError = useApiErrorToast('DealsActionsMenu');
    const { inputRef: attachmentInputRef, uploading: attachmentsUploading, openPicker: openAttachmentPicker, onFilesSelected: onAttachmentFilesSelected } = useAttachmentUploader('deal', deal.id);
    const [editOpen, setEditOpen] = useState(false);
    const [teamOpen, setTeamOpen] = useState(false);
    const [activityOpen, setActivityOpen] = useState(false);
    const [taskOpen, setTaskOpen] = useState(false);
    const [noteOpen, setNoteOpen] = useState(false);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [isDeleting, setIsDeleting] = useState(false);
    const [isUpdatingStatus, setIsUpdatingStatus] = useState(false);
    const stableDealSeeds = useMemo(
        () => dealSeeds.some((candidate) => candidate.id === deal.id)
            ? dealSeeds
            : [deal, ...dealSeeds],
        [deal, dealSeeds],
    );
    const composerAnchor = useMemo<RecordComposerAnchor>(() => ({ kind: 'deal', deal }), [deal]);
    const personSearch = useContactTargetSearch(noteOpen, [], personSeeds);
    const dealSearch = useDealTargetSearch(noteOpen, [deal.id], stableDealSeeds);

    const closed = isDealClosed(deal);

    const toggleDealStatus = async (won: boolean | null) => {
        setIsUpdatingStatus(true);
        try {
            if (won === null) await reopenDeal(deal.id);
            else await closeDeal(deal.id, { won });
            toastSuccess(won === null ? t('dealReopened') : t('dealClosed'));
            router.refresh();
        } catch (err) {
            showApiError(err, 'failedToUpdateStatus');
        } finally {
            setIsUpdatingStatus(false);
        }
    };

    const confirmDelete = async () => {
        setIsDeleting(true);
        try {
            await deleteDeal(deal.id);
            toastSuccess(t('dealDeleted'));
            router.push('/records/deals');
            router.refresh();
        } catch (err) {
            showApiError(err, 'failedToDelete');
            setIsDeleting(false);
        }
    };

    return (
        <div className="flex flex-row gap-2">
            <ButtonGroup orientation="horizontal">
                <Button variant="outline" size="toolbar" onClick={() => setEditOpen(true)}>
                    <PencilSquareIcon className="size-4" />
                    {t('edit')}
                </Button>
                <Button
                    variant="outline"
                    size="toolbar"
                    disabled={deal.company == null}
                    onClick={() => deal.company != null && router.push(`/intelligence/map?companyId=${deal.company}`)}
                >
                    <EyeIcon className="size-4" />
                    {t('viewInMap')}
                </Button>
            </ButtonGroup>
            <ButtonGroup orientation="horizontal">
                <Button
                    variant="outline"
                    size="toolbar"
                    onClick={openAttachmentPicker}
                    disabled={attachmentsUploading}
                >
                    {attachmentsUploading ? (
                        <LoaderCircle className="size-4 animate-spin" />
                    ) : (
                        <PaperClipIcon className="size-4" />
                    )}
                    {t('add')}
                </Button>
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <Button variant="brand" size="toolbar" menu>
                            <PlusIcon className="size-4" />
                            {t('new')}
                        </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end" className="w-48">
                        <DropdownMenuItem
                            onSelect={() => setActivityOpen(true)}
                        >
                            <ChatBubbleLeftRightIcon className="size-4" />
                            <span>{t('addActivity')}</span>
                        </DropdownMenuItem>
                        <DropdownMenuItem
                            onSelect={() => setNoteOpen(true)}
                        >
                            <DocumentTextIcon className="size-4" />
                            <span>{t('addNote')}</span>
                        </DropdownMenuItem>
                        <DropdownMenuItem
                            onSelect={() => setTaskOpen(true)}
                        >
                            <CheckCircleIcon className="size-4" />
                            <span>{t('addTask')}</span>
                        </DropdownMenuItem>
                    </DropdownMenuContent>
                </DropdownMenu>
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <IconButton variant="outline" size="icon-toolbar" label={t('moreActions')}>
                            <EllipsisVerticalIcon className="size-4" />
                        </IconButton>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end" className="w-48">
                        <DropdownMenuItem
                            onSelect={(e) => {
                                e.preventDefault();
                                setTeamOpen(true);
                            }}
                        >
                            <UsersIcon className="size-4" />
                            <span>{t('manageTeam')}</span>
                        </DropdownMenuItem>
                        <DropdownMenuSeparator />
                        {closed ? (
                            <DropdownMenuItem
                                disabled={isUpdatingStatus}
                                onSelect={(e) => {
                                    e.preventDefault();
                                    toggleDealStatus(null);
                                }}
                            >
                                <ArrowUturnLeftIcon className="size-4" />
                                <span>{t('markOpen')}</span>
                            </DropdownMenuItem>
                        ) : (
                            <>
                                <DropdownMenuItem
                                    disabled={isUpdatingStatus}
                                    onSelect={(e) => {
                                        e.preventDefault();
                                        toggleDealStatus(true);
                                    }}
                                >
                                    <CheckCircleIcon className="size-4" />
                                    <span>{t('markWon')}</span>
                                </DropdownMenuItem>
                                <DropdownMenuItem
                                    disabled={isUpdatingStatus}
                                    onSelect={(e) => {
                                        e.preventDefault();
                                        toggleDealStatus(false);
                                    }}
                                >
                                    <XCircleIcon className="size-4" />
                                    <span>{t('markLost')}</span>
                                </DropdownMenuItem>
                            </>
                        )}
                        <DropdownMenuItem
                            variant="destructive"
                            onSelect={(e) => {
                                e.preventDefault();
                                setDeleteOpen(true);
                            }}
                        >
                            <TrashIcon className="size-4" />
                            <span>{t('delete')}</span>
                        </DropdownMenuItem>
                    </DropdownMenuContent>
                </DropdownMenu>
            </ButtonGroup>

            <input
                ref={attachmentInputRef}
                type="file"
                multiple
                className="hidden"
                onChange={onAttachmentFilesSelected}
            />

            <EditDealSheet
                deal={deal}
                open={editOpen}
                onOpenChange={setEditOpen}
                pipelines={pipelines}
                stagesByPipeline={stagesByPipeline}
            />

            <DealTeamDialog
                open={teamOpen}
                onOpenChange={setTeamOpen}
                dealId={deal.id}
                initialOwnerId={deal.ownerId}
                initialCollaborators={collaborators}
            />

            <RecordActivityComposer
                anchor={composerAnchor}
                currentUserId={currentUserId}
                open={activityOpen}
                onOpenChange={setActivityOpen}
            />

            <RecordTaskComposer
                anchor={composerAnchor}
                currentUserId={currentUserId}
                open={taskOpen}
                onOpenChange={setTaskOpen}
            />

            <NoteDialog
                open={noteOpen}
                onOpenChange={setNoteOpen}
                note={null}
                persons={personSearch.contacts}
                deals={dealSearch.deals}
                defaultDeal={deal}
                currentUserId={currentUserId}
                onPersonQueryChange={personSearch.onInputValueChange}
                onDealQueryChange={dealSearch.onInputValueChange}
                personOptionsLoading={personSearch.loading}
                dealOptionsLoading={dealSearch.loading}
            />

            <DeleteRecordDialog
                open={deleteOpen}
                onOpenChange={setDeleteOpen}
                selectedIds={new Set([deal.id])}
                selectedItems={[deal]}
                entityLabel={t('entityLabel')}
                getDisplayName={(d) => d.name}
                isDeleting={isDeleting}
                confirmDelete={confirmDelete}
            />
        </div>
    );
}
