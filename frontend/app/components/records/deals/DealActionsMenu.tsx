'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import {
    EllipsisVerticalIcon,
    PencilSquareIcon,
    EyeIcon,
    PaperClipIcon,
    PlusIcon,
    ChatBubbleLeftRightIcon,
    DocumentTextIcon,
    CheckCircleIcon,
    TrashIcon,
} from '@heroicons/react/24/outline';

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

import { deleteDeal } from '@/app/lib/api';
import { type Company, type Deal, type Pipeline, type Stage } from '@/app/lib/types';

export default function DealActionsMenu({
    deal,
    companies,
    pipelines,
    stagesByPipeline,
    currentUserId,
}: {
    deal: Deal;
    companies: Company[];
    pipelines: Pipeline[];
    stagesByPipeline: Record<number, Stage[]>;
    currentUserId: number;
}) {
    const router = useRouter();
    const [editOpen, setEditOpen] = useState(false);
    const [activityOpen, setActivityOpen] = useState(false);
    const [taskOpen, setTaskOpen] = useState(false);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [isDeleting, setIsDeleting] = useState(false);

    const confirmDelete = async () => {
        setIsDeleting(true);
        try {
            await deleteDeal(deal.id);
            toast.success('Deal deleted', {
                style: { backgroundColor: 'var(--color-brand)', color: 'white' },
            });
            router.push('/records/deals');
            router.refresh();
        } catch (err) {
            toast.error(err instanceof Error ? err.message : 'Failed to delete', {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
            setIsDeleting(false);
        }
    };

    return (
        <div className="flex flex-row gap-2">
            <ButtonGroup orientation="horizontal">
                <Button variant="outline" size="sm" onClick={() => setEditOpen(true)}>
                    <PencilSquareIcon className="size-4" />
                    Edit
                </Button>
                <Button variant="outline" size="sm">
                    <EyeIcon className="size-4" />
                    View in map
                </Button>
            </ButtonGroup>
            <ButtonGroup orientation="horizontal">
                <Button variant="outline" size="sm">
                    <PaperClipIcon className="size-4" />
                    Add
                </Button>
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <Button variant="outline" size="sm">
                            <PlusIcon className="size-4" />
                            New
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
                            <span>Add activity</span>
                        </DropdownMenuItem>
                        <DropdownMenuItem disabled>
                            <DocumentTextIcon className="size-4" />
                            <span>Add note</span>
                        </DropdownMenuItem>
                        <DropdownMenuItem
                            onSelect={(e) => {
                                e.preventDefault();
                                setTaskOpen(true);
                            }}
                        >
                            <CheckCircleIcon className="size-4" />
                            <span>Add task</span>
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
                        <DropdownMenuItem
                            variant="destructive"
                            onSelect={(e) => {
                                e.preventDefault();
                                setDeleteOpen(true);
                            }}
                        >
                            <TrashIcon className="size-4" />
                            <span>Delete</span>
                        </DropdownMenuItem>
                    </DropdownMenuContent>
                </DropdownMenu>
            </ButtonGroup>

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

            <DeleteRecordDialog
                open={deleteOpen}
                onOpenChange={setDeleteOpen}
                selectedIds={new Set([deal.id])}
                selectedItems={[deal]}
                entityLabel="deal"
                getDisplayName={(d) => d.name}
                isDeleting={isDeleting}
                confirmDelete={confirmDelete}
            />
        </div>
    );
}