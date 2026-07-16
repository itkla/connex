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

/** Returns whether a selected file uses a supported managed-image media type. */
export function isManagedImageFile(file: File): file is ManagedImageFile {
    const mediaType = file.type.trim().toLowerCase();
    if (MANAGED_IMAGE_MEDIA_TYPES.some((candidate) => candidate === mediaType)) return true;
    if (mediaType === 'image/jpg') return true;
    return mediaType === '' || mediaType === 'application/octet-stream';
}
