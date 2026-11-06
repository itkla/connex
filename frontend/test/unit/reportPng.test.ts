/** @vitest-environment jsdom */
import { afterEach, describe, expect, it, vi } from 'vitest';

import { renderReportPng } from '@/app/lib/reportPng';

const cleanups: Array<() => void> = [];

function paper(width = 942, height = 1201) {
    const element = document.createElement('article');
    cleanups.push(() => element.remove());
    const paragraph = document.createElement('p');
    paragraph.textContent = '四半期 <営業> & results';
    paragraph.style.fontWeight = '600';
    element.appendChild(paragraph);
    document.body.appendChild(element);
    Object.defineProperties(element, { scrollWidth: { value: width }, scrollHeight: { value: height } });

    const events: string[] = [];
    const decode = vi.fn(async () => { events.push('decode'); });
    const images: ReportImage[] = [];
    class ReportImage {
        src = '';
        decode = decode;
        constructor() { images.push(this); }
    }
    let drawnImage: { src: string } | undefined;
    const context = {
        scale: vi.fn(),
        drawImage: vi.fn((image: { src: string }) => { drawnImage = image; events.push('draw'); }),
    };
    const png = new Blob(['encoded image'], { type: 'image/png' });
    const toBlob = vi.fn((callback: BlobCallback, type?: string) => {
        events.push('encode');
        if (!drawnImage?.src.startsWith('data:image/svg+xml')) {
            throw new DOMException('SVG foreignObject image is not origin-clean', 'SecurityError');
        }
        if (type !== 'image/png') throw new Error('Expected PNG encoding');
        callback(png);
    });
    const canvas = { width: 0, height: 0, getContext: vi.fn((): typeof context | null => context), toBlob };
    const createElement = vi.fn((tag: string) => {
        if (tag !== 'canvas') throw new Error('Unexpected output element');
        return canvas;
    });
    vi.stubGlobal('window', { getComputedStyle: window.getComputedStyle.bind(window), document: { createElement } });
    vi.stubGlobal('URL', class extends URL {
        static createObjectURL() { return 'blob:report-svg'; }
        static revokeObjectURL() { return undefined; }
    });
    vi.stubGlobal('Image', ReportImage);
    return { element, images, png, canvas, context, decode, events };
}

afterEach(() => {
    cleanups.splice(0).forEach((close) => close());
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

describe('report PNG preparation', () => {
    it('uses an origin-clean SVG image and preserves styled multilingual content before encoding', async () => {
        const { element, images, png, canvas, context, events } = paper();
        expect(await renderReportPng(element)).toBe(png);
        expect(images).toHaveLength(1);
        const source = images[0].src;
        expect(source.startsWith('data:image/svg+xml')).toBe(true);
        const separator = source.indexOf(',');
        const svg = source.slice(0, separator).includes(';base64')
            ? Buffer.from(source.slice(separator + 1), 'base64').toString('utf8')
            : decodeURIComponent(source.slice(separator + 1));
        const serialized = new DOMParser().parseFromString(svg, 'image/svg+xml');
        expect(serialized.querySelector('parsererror')).toBeNull();
        expect(serialized.querySelector('foreignObject article p')?.textContent).toBe('四半期 <営業> & results');
        const exportedStyles = element.ownerDocument.createElement('p').style;
        exportedStyles.cssText = serialized.querySelector('foreignObject article p')?.getAttribute('style') ?? '';
        expect(exportedStyles.fontWeight).toBe('600');
        expect(context.scale).toHaveBeenCalledWith(2, 2);
        expect(context.drawImage).toHaveBeenCalledWith(images[0], 0, 0, 942, 1201);
        expect([canvas.width, canvas.height]).toEqual([1884, 2402]);
        expect(events).toEqual(['decode', 'draw', 'encode']);
    });

    it('keeps the existing maximum image dimension and aspect ratio', async () => {
        const { element, canvas, context } = paper(20_000, 10_000);
        await renderReportPng(element);
        expect([canvas.width, canvas.height]).toEqual([16_384, 8192]);
        expect(context.scale).toHaveBeenCalledWith(16_384 / 20_000, 16_384 / 20_000);
    });

    it('returns no image when decoding fails', async () => {
        const { element, decode, context, canvas } = paper();
        decode.mockRejectedValueOnce(new DOMException('Image decoding failed', 'EncodingError'));
        await expect(renderReportPng(element)).rejects.toMatchObject({ name: 'EncodingError' });
        expect(context.drawImage).not.toHaveBeenCalled();
        expect(canvas.toBlob).not.toHaveBeenCalled();
    });

    it('returns no image when a canvas context is unavailable', async () => {
        const { element, canvas } = paper();
        canvas.getContext.mockReturnValueOnce(null);
        await expect(renderReportPng(element)).rejects.toThrow('Canvas rendering is unavailable');
        expect(canvas.toBlob).not.toHaveBeenCalled();
    });

    it('returns no image when the PNG encoder returns no data', async () => {
        const { element, canvas } = paper();
        canvas.toBlob.mockImplementationOnce((callback) => callback(null));
        await expect(renderReportPng(element)).rejects.toThrow('PNG export failed');
    });

    it('propagates a canvas security refusal instead of returning an image', async () => {
        const { element, canvas } = paper();
        canvas.toBlob.mockImplementationOnce(() => { throw new DOMException('Canvas readback denied', 'SecurityError'); });
        await expect(renderReportPng(element)).rejects.toMatchObject({ name: 'SecurityError' });
    });

    it.each([[0, 1], [1, 0], [0, 0]])('rejects an unavailable report size %i by %i', async (width, height) => {
        const { element, decode, canvas } = paper(width, height);
        await expect(renderReportPng(element)).rejects.toThrow('Report dimensions are unavailable');
        expect(decode).not.toHaveBeenCalled();
        expect(canvas.toBlob).not.toHaveBeenCalled();
    });
});
