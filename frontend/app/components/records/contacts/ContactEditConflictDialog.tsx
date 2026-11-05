"use client";

import { useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog';
import type { RecordEditConflict } from '@/app/hooks/useRecordEditConflict';
import type { Contact } from '@/app/lib/types';
import { changedContactFields } from './contactEditConflict';

/** Reviews concurrent changes and confirms discarding the draft before reloading. */
export default function ContactEditConflictDialog({ conflict, onKeepEditing, onReload }: {
    conflict: RecordEditConflict<Contact>;
    onKeepEditing: () => void;
    onReload: (current: Contact) => void;
}) {
    const t = useTranslations('ContactsEditConflict');
    const [confirmReload, setConfirmReload] = useState(false);
    const keepEditingRef = useRef<HTMLButtonElement>(null);
    const changed = conflict.current ? changedContactFields(conflict.base, conflict.current) : [];
    return (
        <Dialog open onOpenChange={(open) => { if (!open) onKeepEditing(); }}>
            <DialogContent size="sm">
                <DialogHeader>
                    <DialogTitle>{t(confirmReload ? 'confirmTitle' : 'title')}</DialogTitle>
                    <DialogDescription>
                        {t(confirmReload ? 'confirmDescription' : conflict.current ? 'description' : 'loadFailed', { name: conflict.base.name })}
                    </DialogDescription>
                </DialogHeader>
                {!confirmReload && conflict.current && (
                    changed.length > 0 ? (
                        <ul className="list-disc space-y-1 pl-5">
                            {changed.map((field) => <li key={field}>{t(`fields.${field}`)}</li>)}
                        </ul>
                    ) : <p>{t('otherChanges')}</p>
                )}
                <DialogFooter>
                    {conflict.current && (
                        <Button variant={confirmReload ? 'destructive' : 'outline'} onClick={() => {
                            if (confirmReload && conflict.current) onReload(conflict.current);
                            else {
                                setConfirmReload(true);
                                keepEditingRef.current?.focus();
                            }
                        }}>
                            {t(confirmReload ? 'discardAndReload' : 'reload')}
                        </Button>
                    )}
                    <Button ref={keepEditingRef} variant="outline" autoFocus onClick={onKeepEditing}>{t('keepEditing')}</Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
