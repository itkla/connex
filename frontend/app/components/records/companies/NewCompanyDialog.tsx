'use client';

import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter, DialogClose } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Loader2Icon } from 'lucide-react';
import { Label } from '@/components/ui/label';
import { type CreateCompanyPayload } from '@/app/lib/types';
import { ChangeEvent, Dispatch, SetStateAction, useEffect, useState } from 'react';
import { CameraIcon } from '@heroicons/react/24/outline';

const inputClass = 'w-full rounded-lg bg-neutral-100 px-3 py-2 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    payload: CreateCompanyPayload;
    setPayload: Dispatch<SetStateAction<CreateCompanyPayload>>;
    logoFile: File | null;
    setLogoFile: Dispatch<SetStateAction<File | null>>;
    isCreating: boolean;
    createNewCompany: () => void;
};

export default function NewCompanyDialog({
    open,
    onOpenChange,
    payload,
    setPayload,
    logoFile,
    setLogoFile,
    isCreating,
    createNewCompany,
}: Props) {
    const [logoPreview, setLogoPreview] = useState<string | null>(null);

    useEffect(() => {
        if (!open && logoPreview) {
            URL.revokeObjectURL(logoPreview);
            setLogoPreview(null);
        }
    }, [open, logoPreview]);

    useEffect(() => {
        return () => {
            if (logoPreview) URL.revokeObjectURL(logoPreview);
        };
    }, [logoPreview]);

    const handleLogoChange = (e: ChangeEvent<HTMLInputElement>) => {
        const file = e.target.files?.[0];
        if (!file) return;
        if (logoPreview) URL.revokeObjectURL(logoPreview);
        setLogoPreview(URL.createObjectURL(file));
        setLogoFile(file);
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>New company</DialogTitle>
                    <DialogDescription>
                        Add a company to your records. These companies will be shared with all users of this organization.
                    </DialogDescription>
                </DialogHeader>

                <div className="flex justify-center">
                    <label
                        htmlFor="company-logo"
                        className="group relative flex h-20 w-20 cursor-pointer items-center justify-center overflow-hidden rounded-2xl bg-neutral-100 ring-1 ring-black/5 transition hover:ring-2 hover:ring-brand"
                    >
                        {logoPreview ? (
                            <img src={logoPreview} alt="" className="h-full w-full object-contain " />
                        ) : (
                            <div className="h-full w-full" style={{ background: 'linear-gradient(180deg, #cdd5dc 0%, #b6bfc6 60%, #9aa4ad 100%)' }} />
                        )}
                        <div className="absolute inset-0 flex items-center justify-center bg-black/40 opacity-0 transition group-hover:opacity-100">
                            <CameraIcon className="size-6 text-white" />
                        </div>
                        <input
                            id="company-logo"
                            type="file"
                            accept="image/*"
                            onChange={handleLogoChange}
                            className="sr-only"
                        />
                    </label>
                </div>

                <div className="grid gap-4">
                    <div className="grid gap-1.5">
                        <Label htmlFor="company-name">Name</Label>
                        <input
                            id="company-name"
                            type="text"
                            value={payload.name}
                            onChange={(e) => setPayload((prev) => ({ ...prev, name: e.target.value }))}
                            className={inputClass}
                            placeholder="Acme Corp"
                            autoFocus
                            required
                        />
                    </div>

                    <div className="grid gap-1.5">
                        <Label htmlFor="company-website">Website</Label>
                        <input
                            id="company-website"
                            type="url"
                            value={payload.website ?? ''}
                            onChange={(e) => setPayload((prev) => ({ ...prev, website: e.target.value }))}
                            className={inputClass}
                            placeholder="https://acme.com"
                        />
                    </div>

                    <div className="grid grid-cols-2 gap-3">
                        <div className="grid gap-1.5">
                            <Label htmlFor="company-industry">Industry</Label>
                            <input
                                id="company-industry"
                                type="text"
                                value={payload.industry ?? ''}
                                onChange={(e) => setPayload((prev) => ({ ...prev, industry: e.target.value }))}
                                className={inputClass}
                                placeholder="Software"
                            />
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor="company-phone">Phone</Label>
                            <input
                                id="company-phone"
                                type="tel"
                                value={payload.phone ?? ''}
                                onChange={(e) => setPayload((prev) => ({ ...prev, phone: e.target.value }))}
                                className={inputClass}
                                placeholder="+1 555 123 4567"
                            />
                        </div>
                    </div>

                    <div className="grid gap-1.5">
                        <Label htmlFor="company-address">Address</Label>
                        <input
                            id="company-address"
                            type="text"
                            value={payload.address ?? ''}
                            onChange={(e) => setPayload((prev) => ({ ...prev, address: e.target.value }))}
                            className={inputClass}
                            placeholder="123 Main St"
                        />
                    </div>
                </div>

                <DialogFooter>
                    <DialogClose asChild>
                        <Button variant="outline" disabled={isCreating}>Cancel</Button>
                    </DialogClose>
                    <Button
                        onClick={createNewCompany}
                        disabled={isCreating || !payload.name.trim()}
                        className="bg-brand text-white hover:bg-brand-dark"
                    >
                        {isCreating ? <Loader2Icon className="size-4 animate-spin" /> : 'Create'}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}


