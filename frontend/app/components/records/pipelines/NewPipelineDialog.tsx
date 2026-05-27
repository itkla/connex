'use client';

import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter, DialogClose } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Loader2Icon } from 'lucide-react';
import { PlusIcon, XMarkIcon } from '@heroicons/react/24/outline';
import { Label } from '@/components/ui/label';
import { type CreatePipelinePayload } from '@/app/lib/types';
import { Dispatch, SetStateAction } from 'react';
import { useTranslations } from 'next-intl';

const inputClass = 'w-full rounded-lg bg-neutral-100 px-3 py-2 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    payload: CreatePipelinePayload;
    setPayload: Dispatch<SetStateAction<CreatePipelinePayload>>;
    isCreating: boolean;
    createNewPipeline: () => void;
};

export default function NewPipelineDialog({
    open,
    onOpenChange,
    payload,
    setPayload,
    isCreating,
    createNewPipeline,
}: Props) {
    const t = useTranslations('PipelinesNewDialog');
    const stages = payload.stages ?? [];

    const updateStageName = (index: number, name: string) => {
        setPayload((prev) => {
            const next = [...(prev.stages ?? [])];
            next[index] = { name };
            return { ...prev, stages: next };
        });
    };

    const addStage = () => {
        setPayload((prev) => ({
            ...prev,
            stages: [...(prev.stages ?? []), { name: '' }],
        }));
    };

    const removeStage = (index: number) => {
        setPayload((prev) => ({
            ...prev,
            stages: (prev.stages ?? []).filter((_, i) => i !== index),
        }));
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>{t('title')}</DialogTitle>
                    <DialogDescription>
                        {t('description')}
                    </DialogDescription>
                </DialogHeader>

                <div className="grid gap-4">
                    <div className="grid gap-1.5">
                        <Label htmlFor="pipeline-name">{t('name')}</Label>
                        <input
                            id="pipeline-name"
                            type="text"
                            value={payload.name ?? ''}
                            onChange={(e) => setPayload((prev) => ({ ...prev, name: e.target.value }))}
                            className={inputClass}
                            placeholder={t('namePlaceholder')}
                            autoFocus
                            required
                        />
                    </div>

                    <div className="grid gap-1.5">
                        <Label>{t('stages')}</Label>
                        <p className="text-xs text-neutral-500">
                            {t('stagesHint')}
                        </p>
                        <div className="flex flex-col gap-2">
                            {stages.map((s, i) => (
                                <div key={i} className="flex items-center gap-2">
                                    <input
                                        type="text"
                                        value={s.name}
                                        onChange={(e) => updateStageName(i, e.target.value)}
                                        className={`${inputClass} flex-1`}
                                        placeholder={t('stageNamePlaceholder')}
                                    />
                                    <button
                                        type="button"
                                        aria-label={t('removeStageAriaLabel')}
                                        onClick={() => removeStage(i)}
                                        className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-neutral-100 text-neutral-500 transition hover:bg-neutral-200 hover:text-destructive"
                                    >
                                        <XMarkIcon className="size-4" />
                                    </button>
                                </div>
                            ))}
                            <button
                                type="button"
                                onClick={addStage}
                                className="flex items-center gap-2 self-start rounded-full bg-neutral-100 px-3 py-1.5 text-sm text-neutral-700 ring-1 ring-black/5 transition hover:bg-neutral-200"
                            >
                                <PlusIcon className="size-4" />
                                {t('addStage')}
                            </button>
                        </div>
                    </div>
                </div>

                <DialogFooter>
                    <DialogClose asChild>
                        <Button variant="outline" disabled={isCreating}>{t('cancel')}</Button>
                    </DialogClose>
                    <Button
                        onClick={createNewPipeline}
                        disabled={isCreating || !payload.name?.trim()}
                        className="bg-brand text-white hover:bg-brand-dark"
                    >
                        {isCreating ? <Loader2Icon className="size-4 animate-spin" /> : t('create')}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}