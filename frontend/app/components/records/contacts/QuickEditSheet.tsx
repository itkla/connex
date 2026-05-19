import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetFooter, SheetClose } from '@/components/ui/sheet';
import { Button } from '@/components/ui/button';
import { Loader2Icon } from 'lucide-react';
import { Label } from '@/components/ui/label';
import { type Contact } from '@/app/lib/types';
import type { SelectionId } from '@/app/components/records/DataRenderView';
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
    isSaving,
    saveEdits,
}: Props) {
    return (
        <Sheet open={editSheetOpen} onOpenChange={setEditSheetOpen}>
            <SheetContent side="right" className="flex w-full flex-col sm:max-w-lg">
                <SheetHeader className="border-b">
                    <SheetTitle>
                        {selectedIds.size === 1 ? 'Quick edit contact' : `Quick edit ${selectedIds.size} contacts`}
                    </SheetTitle>
                    <SheetDescription>
                        Update fields below. Only changed contacts will be saved.
                    </SheetDescription>
                </SheetHeader>

                <div className="flex-1 overflow-y-auto px-4 py-2">
                    <div className="flex flex-col gap-6">
                        {selectedContacts.map((c, idx) => {
                            const draft = drafts[c.id];
                            if (!draft) return null;
                            return (
                                <div key={c.id} className={idx > 0 ? 'border-t pt-6' : ''}>
                                    <div className="mb-3 flex items-center gap-3">
                                        <ContactAvatar contact={c} type="large" />
                                        <div className="text-lg font-medium text-neutral-600">{c.name}</div>
                                    </div>

                                    <div className="grid gap-3">
                                        <div className="grid gap-1.5">
                                            <Label htmlFor={`name-${c.id}`}>Name</Label>
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
                                            <Label htmlFor={`email-${c.id}`}>Email</Label>
                                            <input
                                                id={`email-${c.id}`}
                                                type="email"
                                                value={draft.email}
                                                onChange={(e) => updateDraft(c.id, { email: e.target.value })}
                                                className={inputClass}
                                            />
                                        </div>
                                        <div className="grid gap-1.5">
                                            <Label htmlFor={`phone-${c.id}`}>Phone</Label>
                                            <input
                                                id={`phone-${c.id}`}
                                                type="tel"
                                                value={draft.phone}
                                                onChange={(e) => updateDraft(c.id, { phone: e.target.value })}
                                                className={inputClass}
                                            />
                                        </div>
                                        <div className="grid gap-1.5">
                                            <Label htmlFor={`title-${c.id}`}>Title</Label>
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
                        <Button variant="outline" disabled={isSaving}>Cancel</Button>
                    </SheetClose>
                    <Button onClick={saveEdits} disabled={isSaving} className="bg-brand text-white hover:bg-brand-dark">
                        {isSaving ? <Loader2Icon className="size-4 animate-spin" /> : 'Save'}
                    </Button>
                </SheetFooter>
            </SheetContent>
        </Sheet>
    );
}
