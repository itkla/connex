/**
 * Rasterizes a report using an inline SVG image so foreignObject content remains origin-clean
 * for canvas readback. A failed decode or encoding never returns a releasable image.
 */
export async function renderReportPng(element: HTMLElement): Promise<Blob> {
    const clonedNode = element.cloneNode(true);
    if (!(clonedNode instanceof HTMLElement)) throw new Error('Report cloning is unavailable');
    const clone = clonedNode;
    const sources = [element, ...Array.from(element.querySelectorAll('*'))];
    const targets = [clone, ...Array.from(clone.querySelectorAll('*'))];
    sources.forEach((source, index) => {
        const target = targets[index];
        if (!(target instanceof HTMLElement || target instanceof SVGElement)) return;
        const computed = window.getComputedStyle(source);
        target.style.cssText = Array.from(computed)
            .map((property) => `${property}:${computed.getPropertyValue(property)};`)
            .join('');
    });
    const width = Math.ceil(element.scrollWidth);
    const height = Math.ceil(element.scrollHeight);
    if (width <= 0 || height <= 0) throw new Error('Report dimensions are unavailable');
    const maximumDimension = 16384;
    const scale = Math.min(2, maximumDimension / width, maximumDimension / height);
    const serialized = new XMLSerializer().serializeToString(clone);
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><foreignObject width="100%" height="100%"><div xmlns="http://www.w3.org/1999/xhtml">${serialized}</div></foreignObject></svg>`;
    const image = new Image();
    image.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
    await image.decode();
    const canvas = window.document.createElement('canvas');
    canvas.width = Math.max(1, Math.floor(width * scale));
    canvas.height = Math.max(1, Math.floor(height * scale));
    const context = canvas.getContext('2d');
    if (!context) throw new Error('Canvas rendering is unavailable');
    context.scale(scale, scale);
    context.drawImage(image, 0, 0, width, height);
    return new Promise<Blob>((resolve, reject) => {
        canvas.toBlob((result) => result ? resolve(result) : reject(new Error('PNG export failed')), 'image/png');
    });
}
