import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter, DialogClose } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Loader2Icon } from 'lucide-react';
import { type Contact } from '@/app/lib/types';
import type { SelectionId } from '@/app/components/records/DataRenderView';

type Props = {
    deleteDialogOpen: boolean;
    setDeleteDialogOpen: (open: boolean) => void;
    selectedIds: Set<SelectionId>;
    selectedContacts: Contact[];
    isDeleting: boolean;
    confirmDelete: () => void;
};

export default function DeleteContactDialog({ deleteDialogOpen, setDeleteDialogOpen, selectedIds, selectedContacts, isDeleting, confirmDelete }: Props) {
    return (
        <Dialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>
                        {selectedIds.size === 1 ? 'Delete contact' : `Delete ${selectedIds.size} contacts`}
                    </DialogTitle>
                    <DialogDescription>
                        {selectedIds.size === 1 && selectedContacts[0]
                            ? `Are you sure you want to delete "${selectedContacts[0].name}"? This action cannot be undone.`
                            : `Are you sure you want to delete these ${selectedIds.size} contacts? This action cannot be undone.`}
                    </DialogDescription>
                </DialogHeader>
                <DialogFooter>
                    <DialogClose asChild>
                        <Button variant="outline" disabled={isDeleting}>Cancel</Button>
                    </DialogClose>
                    <Button
                        variant="destructive"
                        className="bg-red-500 text-white hover:bg-red-600"
                        disabled={isDeleting}
                        onClick={confirmDelete}
                    >
                        {isDeleting ? (<Loader2Icon className="size-4 animate-spin" />) : 'Delete'}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    )
};