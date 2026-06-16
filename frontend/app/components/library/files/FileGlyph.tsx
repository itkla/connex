import { Attachment } from '@/app/lib/types';
import { FileKind, KIND_ICON } from '@/app/components/library/files/fileMeta';

/**
 * File glyph component for the files browser
 * @param attachment the attachment object
 * @param kind the kind of the file
 * @returns the file glyph component
 */
export default function FileGlyph({ attachment, kind }: { attachment: Attachment; kind: FileKind }) {
    if (kind === 'image') {
        return (
            <img
                src={attachment.url}
                alt=""
                loading="lazy"
                className="size-9 shrink-0 rounded-lg bg-muted object-cover ring-1 ring-border"
            />
        );
    }
    const Icon = KIND_ICON[kind];
    return (
        <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground ring-1 ring-border">
            <Icon className="size-5" />
        </div>
    );
}