export const MANAGED_IMAGE_MEDIA_TYPES = [
    'image/jpeg',
    'image/png',
    'image/webp',
] as const;

export type ManagedImageMediaType = typeof MANAGED_IMAGE_MEDIA_TYPES[number];

export type ManagedImageFile = File & { type: ManagedImageMediaType };

export const MANAGED_IMAGE_ACCEPT = MANAGED_IMAGE_MEDIA_TYPES.join(',');

/** Returns whether a selected file uses a supported managed-image media type. */
export function isManagedImageFile(file: File): file is ManagedImageFile {
    return MANAGED_IMAGE_MEDIA_TYPES.some((mediaType) => mediaType === file.type);
}
