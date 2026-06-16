import type { ComponentType } from 'react';
import {
    ArchiveBoxIcon,
    BriefcaseIcon,
    BuildingOffice2Icon,
    CodeBracketIcon,
    DocumentIcon,
    DocumentTextIcon,
    FilmIcon,
    PaperClipIcon,
    PhotoIcon,
    PresentationChartBarIcon,
    TableCellsIcon,
    DocumentChartBarIcon,
    UserCircleIcon,
    UserIcon,
} from '@heroicons/react/24/outline';

export type IconType = ComponentType<{ className?: string }>;

export const FILE_KINDS = [
    'image',
    'pdf',
    'document',
    'spreadsheet',
    'presentation',
    'archive',
    'media',
    'code',
    'other',
] as const;

export type FileKind = (typeof FILE_KINDS)[number];

export const KIND_ICON: Record<FileKind, IconType> = {
    image: PhotoIcon,
    pdf: DocumentIcon,
    document: DocumentTextIcon,
    spreadsheet: DocumentChartBarIcon,
    presentation: PresentationChartBarIcon,
    archive: ArchiveBoxIcon,
    media: FilmIcon,
    code: CodeBracketIcon,
    other: PaperClipIcon,
};

export const KIND_LABEL_KEY: Record<FileKind, string> = {
    image: 'kindImage',
    pdf: 'kindPdf',
    document: 'kindDocument',
    spreadsheet: 'kindSpreadsheet',
    presentation: 'kindPresentation',
    archive: 'kindArchive',
    media: 'kindMedia',
    code: 'kindCode',
    other: 'kindOther',
};

/**
 * Returns the extension of a file name.
 * @param fileName - The file name.
 * @returns The extension of the file name.
 */
function extensionOf(fileName?: string): string {
    if (!fileName || !fileName.includes('.')) return '';
    return fileName.split('.').pop()!.toLowerCase();
}

/**
 * Classifies a file's display kind from its MIME type, falling back to the file
 * extension when the type is missing or generic (e.g. application/octet-stream).
 * @param contentType - The MIME type of the file.
 * @param fileName - The file name.
 * @returns The classified kind of the file.
 */
export function classifyKind(contentType?: string, fileName?: string): FileKind {
    const ct = (contentType ?? '').toLowerCase();
    const ext = extensionOf(fileName);

    const imageExt = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'avif', 'bmp', 'ico', 'heic', 'heif', 'tif', 'tiff'];
    const spreadsheetExt = ['xls', 'xlsx', 'csv', 'tsv', 'ods'];
    const presentationExt = ['ppt', 'pptx', 'odp', 'key'];
    const mediaExt = ['mp3', 'wav', 'ogg', 'flac', 'm4a', 'aac', 'mp4', 'mov', 'webm', 'avi', 'mkv', 'm4v'];
    const archiveExt = ['zip', 'rar', '7z', 'tar', 'gz', 'tgz', 'bz2', 'xz'];
    const codeExt = ['js', 'jsx', 'ts', 'tsx', 'json', 'html', 'htm', 'css', 'scss', 'py', 'java', 'go', 'rb', 'rs', 'php', 'sh', 'yml', 'yaml', 'xml', 'sql', 'c', 'cpp', 'cs', 'kt', 'swift', 'toml'];
    const documentExt = ['doc', 'docx', 'odt', 'rtf', 'txt', 'md', 'markdown', 'pages'];

    if (ct.startsWith('image/') || imageExt.includes(ext)) return 'image';
    if (ct === 'application/pdf' || ext === 'pdf') return 'pdf';
    if (ct.includes('spreadsheet') || ct === 'text/csv' || ct === 'application/vnd.ms-excel' || spreadsheetExt.includes(ext)) return 'spreadsheet';
    if (ct.includes('presentation') || ct === 'application/vnd.ms-powerpoint' || presentationExt.includes(ext)) return 'presentation';
    if (ct.startsWith('audio/') || ct.startsWith('video/') || mediaExt.includes(ext)) return 'media';
    if (/zip|compressed|x-7z|x-rar|x-tar|gzip|x-bzip/.test(ct) || archiveExt.includes(ext)) return 'archive';
    if (ct === 'application/json' || ct === 'application/xml' || ct === 'text/html' || ct.includes('javascript') || codeExt.includes(ext)) return 'code';
    if (ct.includes('word') || ct.includes('opendocument.text') || ct === 'application/rtf' || ct.startsWith('text/') || documentExt.includes(ext)) return 'document';
    return 'other';
}

export type SourceType = 'company' | 'person' | 'deal' | 'user';

export const SOURCE_TYPES: readonly SourceType[] = ['company', 'person', 'deal', 'user'] as const;

type SourceMeta = { Icon: IconType; labelKey: string; href: (id: number) => string };

export const SOURCE_META: Record<SourceType, SourceMeta> = {
    company: { Icon: BuildingOffice2Icon, labelKey: 'sourceCompany', href: (id) => `/records/companies/${id}` },
    person: { Icon: UserIcon, labelKey: 'sourcePerson', href: (id) => `/records/contacts/${id}` },
    deal: { Icon: BriefcaseIcon, labelKey: 'sourceDeal', href: (id) => `/records/deals/${id}` },
    user: { Icon: UserCircleIcon, labelKey: 'sourceUser', href: (id) => `/users/${id}` },
};

/**
 * Returns the source metadata for an entity type, or null for unknown / legacy types.
 * @param entityType - The type of the entity.
 * @returns The source metadata for the entity type, or null for unknown / legacy types.
 */
export function sourceMetaFor(entityType: string): SourceMeta | null {
    return (SOURCE_META as Record<string, SourceMeta>)[entityType] ?? null;
}