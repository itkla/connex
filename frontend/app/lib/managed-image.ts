export const MANAGED_IMAGE_MEDIA_TYPES = [
    'image/jpeg',
    'image/png',
    'image/webp',
] as const;

export type ManagedImageMediaType = typeof MANAGED_IMAGE_MEDIA_TYPES[number];

export type ManagedImageFile = File & {
    type: ManagedImageMediaType | 'image/jpg' | 'application/octet-stream' | '';
};

const MANAGED_IMAGE_EXTENSIONS = ['.jpg', '.jpeg', '.png', '.webp'] as const;

export const MANAGED_IMAGE_ACCEPT = [
    ...MANAGED_IMAGE_MEDIA_TYPES,
    ...MANAGED_IMAGE_EXTENSIONS,
].join(',');

/**
 * Derives readable default alt text from an uploaded file name: the extension
 * is dropped and separator runs collapse to spaces, falling back to the raw
 * name when nothing remains.
 */
export function defaultAltFromFileName(fileName: string): string {
    const base = fileName.replace(/\.[^.]+$/u, '').replace(/[_-]+/gu, ' ').trim();
    return base || fileName;
}

function ascii(bytes: Uint8Array, offset: number, expected: string): boolean {
    if (bytes.length < offset + expected.length) return false;
    return Array.from(expected).every(
        (character, index) => bytes[offset + index] === character.charCodeAt(0),
    );
}

async function detectedManagedImageType(file: File): Promise<ManagedImageMediaType | null> {
    try {
        const bytes = new Uint8Array(await file.slice(0, 12).arrayBuffer());
        const jpeg = bytes.length >= 3
            && bytes[0] === 0xff
            && bytes[1] === 0xd8
            && bytes[2] === 0xff;
        const png = bytes.length >= 8
            && bytes[0] === 0x89
            && ascii(bytes, 1, 'PNG')
            && bytes[4] === 0x0d
            && bytes[5] === 0x0a
            && bytes[6] === 0x1a
            && bytes[7] === 0x0a;
        const webp = ascii(bytes, 0, 'RIFF') && ascii(bytes, 8, 'WEBP');
        if (jpeg) return 'image/jpeg';
        if (png) return 'image/png';
        if (webp) return 'image/webp';
        return null;
    } catch {
        return null;
    }
}

/** Returns whether a selected file's declared type and bytes identify the same supported image. */
export async function isManagedImageFile(file: File): Promise<boolean> {
    const mediaType = file.type.trim().toLowerCase();
    const detectedType = await detectedManagedImageType(file);
    if (!detectedType) return false;
    if (MANAGED_IMAGE_MEDIA_TYPES.some((candidate) => candidate === mediaType)) {
        return mediaType === detectedType;
    }
    if (mediaType === 'image/jpg') return detectedType === 'image/jpeg';
    if (mediaType !== '' && mediaType !== 'application/octet-stream') return false;
    return true;
}
