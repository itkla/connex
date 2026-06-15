import { type Attachment } from '@/app/lib/types';

// TODO: move attachments to a library file

const ATTACHMENTS_ADDED = 'connex:attachments-added';

type AttachmentsAddedDetail = {
    entityType: string;
    entityId: number;
    attachments: Attachment[];
};

export function emitAttachmentsAdded(detail: AttachmentsAddedDetail) {
    if (typeof window === 'undefined') return;
    window.dispatchEvent(new CustomEvent<AttachmentsAddedDetail>(ATTACHMENTS_ADDED, { detail }));
}

export function onAttachmentsAdded(handler: (detail: AttachmentsAddedDetail) => void): () => void {
    if (typeof window === 'undefined') return () => {};
    const listener = (e: Event) => handler((e as CustomEvent<AttachmentsAddedDetail>).detail);
    window.addEventListener(ATTACHMENTS_ADDED, listener);
    return () => window.removeEventListener(ATTACHMENTS_ADDED, listener);
}