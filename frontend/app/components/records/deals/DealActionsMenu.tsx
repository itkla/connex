'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { toastError, toastSuccess } from '@/app/lib/toast';
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
} from '@heroicons/react/24/outline';

import { useAttachmentUploader } from '@/app/components/attachments/useAttachmentUploader';

import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Button } from '@/components/ui/button';
import { ButtonGroup } from '@/components/ui/button-group';

import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import EditDealSheet from '@/app/components/records/deals/EditDealSheet';
import NewDealActivityDialog from '@/app/components/records/deals/NewDealActivityDialog';
import NewDealTaskDialog from '@/app/components/records/deals/NewDealTaskDialog';
import NoteDialog from '@/app/components/activity/notes/NoteDialog';

import { closeDeal, deleteDeal, reopenDeal } from '@/app/lib/api';
import { parseMysqlDateTime } from '@/app/lib/utils';
import { type Company, type Contact, type Deal, type Pipeline, type Stage } from '@/app/lib/types';

function isClosed(deal: Deal): boolean {
    const t = parseMysqlDateTime(deal.closedAt);
    return Number.isFinite(t) && t <= Date.now();
}

export default function DealActionsMenu({
    deal,
    companies,
    pipelines,
    stagesByPipeline,
    currentUserId,
    persons = [],
    deals = [],
}: {
    deal: Deal;
    companies: Company[];
    pipelines: Pipeline[];
    stagesByPipeline: Record<number, Stage[]>;
    currentUserId: number;
    persons?: Contact[];
    deals?: Deal[];
}) {
    const router = useRouter();
    const t = useTranslations('DealsActionsMenu');
    const { inputRef: attachmentInputRef, uploading: attachmentsUploading, openPicker: openAttachmentPicker, onFilesSelected: onAttachmentFilesSelected } = useAttachmentUploader('deal', deal.id);
    const [editOpen, setEditOpen] = useState(false);
    const [activityOpen, setActivityOpen] = useState(false);
    const [taskOpen, setTaskOpen] = useState(false);
    const [noteOpen, setNoteOpen] = useState(false);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [isDeleting, setIsDeleting] = useState(false);
    const [isUpdatingStatus, setIsUpdatingStatus] = useState(false);

    const closed = isClosed(deal);

    const toggleDealStatus = async (won: boolean | null) => {
        setIsUpdatingStatus(true);
        try {
            if (won === null) await reopenDeal(deal.id);
            else await closeDeal(deal.id, { won });
            toastSuccess(won === null ? t('dealReopened') : t('dealClosed'));
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('failedToUpdateStatus'));
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
            toastError(err instanceof Error ? err.message : t('failedToDelete'));
            setIsDeleting(false);
        }
    };

    return (
        <div className="flex flex-row gap-2">
            <ButtonGroup orientation="horizontal">
                <Button variant="outline" size="sm" onClick={() => setEditOpen(true)}>
                    <PencilSquareIcon className="size-4" />
                    {t('edit')}
                </Button>
                <Button
                    variant="outline"
                    size="sm"
                    disabled={deal.company == null}
                    onClick={() => deal.company != null && router.push(`/overview/map?companyId=${deal.company}`)}
                >
                    <EyeIcon className="size-4" />
                    {t('viewInMap')}
                </Button>
            </ButtonGroup>
            <ButtonGroup orientation="horizontal">
                <Button
                    variant="outline"
                    size="sm"
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
                        <Button variant="outline" size="sm">
                            <PlusIcon className="size-4" />
                            {t('new')}
                        </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end" className="w-48">
                        <DropdownMenuItem
                            onSelect={(e) => {
                                e.preventDefault();
                                setActivityOpen(true);
                            }}
                        >
                            <ChatBubbleLeftRightIcon className="size-4" />
                            <span>{t('addActivity')}</span>
                        </DropdownMenuItem>
                        <DropdownMenuItem
                            onSelect={(e) => {
                                e.preventDefault();
                                setNoteOpen(true);
                            }}
                        >
                            <DocumentTextIcon className="size-4" />
                            <span>{t('addNote')}</span>
                        </DropdownMenuItem>
                        <DropdownMenuItem
                            onSelect={(e) => {
                                e.preventDefault();
                                setTaskOpen(true);
                            }}
                        >
                            <CheckCircleIcon className="size-4" />
                            <span>{t('addTask')}</span>
                        </DropdownMenuItem>
                    </DropdownMenuContent>
                </DropdownMenu>
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <Button variant="outline" size="sm">
                            <EllipsisVerticalIcon className="size-4" />
                        </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end" className="w-48">
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
                companies={companies}
                pipelines={pipelines}
                stagesByPipeline={stagesByPipeline}
            />

            <NewDealActivityDialog
                dealId={deal.id}
                dealName={deal.name}
                currentUserId={currentUserId}
                deal={deal}
                open={activityOpen}
                onOpenChange={setActivityOpen}
            />

            <NewDealTaskDialog
                dealId={deal.id}
                dealName={deal.name}
                deal={deal}
                currentUserId={currentUserId}
                open={taskOpen}
                onOpenChange={setTaskOpen}
            />

            <NoteDialog
                open={noteOpen}
                onOpenChange={setNoteOpen}
                note={null}
                persons={persons}
                deals={deals.length > 0 ? deals : [deal]}
                defaultDeal={deal}
                currentUserId={currentUserId}
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