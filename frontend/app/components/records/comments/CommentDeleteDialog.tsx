'use client';

import { useTranslations } from 'next-intl';
import { LoaderCircle } from 'lucide-react';

import { Button } from '@/components/ui/button';
import {
    Dialog,
    DialogClose,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';

type Props = {
    open: boolean;
    deleting: boolean;
    onCancel: () => void;
    onConfirm: () => void;
};

/** Destructive confirm for redacting a comment, mirroring DeleteRecordDialog. */
export default function CommentDeleteDialog({ open, deleting, onCancel, onConfirm }: Props) {
    const t = useTranslations('Comments');

    return (
        <Dialog
            open={open}
            onOpenChange={(next) => {
                if (!next && !deleting) onCancel();
            }}
        >
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>{t('deleteTitle')}</DialogTitle>
                    <DialogDescription>{t('deleteBody')}</DialogDescription>
                </DialogHeader>
                <DialogFooter>
                    <DialogClose asChild>
                        <Button variant="outline" disabled={deleting}>
                            {t('cancel')}
                        </Button>
                    </DialogClose>
                    <Button
                        variant="destructive"
                        className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                        disabled={deleting}
                        onClick={onConfirm}
                    >
                        {deleting ? <LoaderCircle className="size-4 animate-spin" /> : t('confirmDelete')}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
