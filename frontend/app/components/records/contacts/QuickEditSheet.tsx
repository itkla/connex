import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetFooter, SheetClose } from '@/components/ui/sheet';
import { Button } from '@/components/ui/button';
import { Loader2Icon, UserIcon } from 'lucide-react';
import { CameraIcon } from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';
import { Label } from '@/components/ui/label';
import { type Contact } from '@/app/lib/types';
import type { SelectionId } from '@/app/components/records/types';
import ContactAvatar from '@/app/components/records/contacts/ContactAvatar';

// the styling from login
// TODO: move this to a shared location
const inputClass = 'w-full rounded-lg bg-neutral-100 px-3 py-2 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand';

export type ContactDraft = {
    name: string;
    email: string;
    phone: string;
    title: string;
};

type Props = {
    editSheetOpen: boolean;
    setEditSheetOpen: (open: boolean) => void;
    selectedIds: Set<SelectionId>;
    selectedContacts: Contact[];
    drafts: Record<number, ContactDraft>;
    updateDraft: (id: number, patch: Partial<ContactDraft>) => void;
    imageFiles?: Record<number, File | null>;
    updateImageFile?: (id: number, file: File | null) => void;
    isSaving: boolean;
    saveEdits: () => void;
};

export default function QuickEditSheet({
    editSheetOpen,
    setEditSheetOpen,
    selectedIds,
    selectedContacts,
    drafts,
    updateDraft,
    imageFiles,
    updateImageFile,
    isSaving,
    saveEdits,
}: Props) {
    const t = useTranslations('ContactsQuickEditSheet');
    return (
        <Sheet open={editSheetOpen} onOpenChange={setEditSheetOpen}>
            <SheetContent side="right" className="flex w-full flex-col sm:max-w-lg">
                <SheetHeader className="border-b">
                    <SheetTitle>
                        {selectedIds.size === 1 ? t('titleSingle') : t('titleMultiple', { count: selectedIds.size })}
                    </SheetTitle>
                    <SheetDescription>
                        {t('description')}
                    </SheetDescription>
                </SheetHeader>

                <div className="flex-1 overflow-y-auto px-4 py-2">
                    <div className="flex flex-col gap-6">
                        {selectedContacts.map((c, idx) => {
                            const draft = drafts[c.id];
                            if (!draft) return null;
                            const pendingImage = imageFiles?.[c.id] ?? null;
                            const previewSrc = pendingImage
                                ? URL.createObjectURL(pendingImage)
                                : c.imageUrl || null;
                            return (
                                <div key={c.id} className={idx > 0 ? 'border-t pt-6' : ''}>
                                    <div className="mb-3 flex items-center gap-3">
                                        {updateImageFile ? (
                                            <label
                                                htmlFor={`pfp-${c.id}`}
                                                className="group relative flex h-16 w-16 cursor-pointer items-center justify-center overflow-hidden rounded-full bg-neutral-200 ring-1 ring-black/5 transition hover:ring-2 hover:ring-brand"
                                            >
                                                {previewSrc ? (
                                                    <img src={previewSrc} alt="" className="h-full w-full object-cover" />
                                                ) : (
                                                    <div className="flex h-full w-full items-center justify-center bg-gray-400">
                                                        <UserIcon className="size-10 text-white" />
                                                    </div>
                                                )}
                                                <div className="absolute inset-0 flex items-center justify-center bg-black/40 opacity-0 transition group-hover:opacity-100">
                                                    <CameraIcon className="size-5 text-white" />
                                                </div>
                                                <input
                                                    id={`pfp-${c.id}`}
                                                    type="file"
                                                    accept="image/*"
                                                    onChange={(e) => updateImageFile(c.id, e.target.files?.[0] ?? null)}
                                                    className="sr-only"
                                                />
                                            </label>
                                        ) : (
                                            <ContactAvatar contact={c} type="large" />
                                        )}
                                        <div className="text-lg font-medium text-neutral-600">{c.name}</div>
                                    </div>

                                    <div className="grid gap-3">
                                        <div className="grid gap-1.5">
                                            <Label htmlFor={`name-${c.id}`}>{t('name')}</Label>
                                            <input
                                                id={`name-${c.id}`}
                                                type="text"
                                                value={draft.name}
                                                onChange={(e) => updateDraft(c.id, { name: e.target.value })}
                                                className={inputClass}
                                                required
                                            />
                                        </div>
                                        <div className="grid gap-1.5">
                                            <Label htmlFor={`email-${c.id}`}>{t('email')}</Label>
                                            <input
                                                id={`email-${c.id}`}
                                                type="email"
                                                value={draft.email}
                                                onChange={(e) => updateDraft(c.id, { email: e.target.value })}
                                                className={inputClass}
                                            />
                                        </div>
                                        <div className="grid gap-1.5">
                                            <Label htmlFor={`phone-${c.id}`}>{t('phone')}</Label>
                                            <input
                                                id={`phone-${c.id}`}
                                                type="tel"
                                                value={draft.phone}
                                                onChange={(e) => updateDraft(c.id, { phone: e.target.value })}
                                                className={inputClass}
                                            />
                                        </div>
                                        <div className="grid gap-1.5">
                                            <Label htmlFor={`title-${c.id}`}>{t('title')}</Label>
                                            <input
                                                id={`title-${c.id}`}
                                                type="text"
                                                value={draft.title}
                                                onChange={(e) => updateDraft(c.id, { title: e.target.value })}
                                                className={inputClass}
                                            />
                                        </div>
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                </div>

                <SheetFooter className="border-t">
                    <SheetClose asChild>
                        <Button variant="outline" disabled={isSaving}>{t('cancel')}</Button>
                    </SheetClose>
                    <Button onClick={saveEdits} disabled={isSaving} className="bg-brand text-white hover:bg-brand-dark">
                        {isSaving ? <Loader2Icon className="size-4 animate-spin" /> : t('save')}
                    </Button>
                </SheetFooter>
            </SheetContent>
        </Sheet>
    );
}
