'use client';

import { useCallback, useRef } from 'react';

export function useDragScroll<T extends HTMLElement = HTMLElement>() {
    const cleanupRef = useRef<(() => void) | null>(null);

    return useCallback((el: T | null) => {
        if (cleanupRef.current) {
            cleanupRef.current();
            cleanupRef.current = null;
        }
        if (!el) return;

        let dragging = false;
        let startX = 0;
        let startY = 0;
        let startLeft = 0;
        let startTop = 0;

        const onPointerDown = (e: PointerEvent) => {
            if (e.button !== 1) return;
            dragging = true;
            startX = e.clientX;
            startY = e.clientY;
            startLeft = el.scrollLeft;
            startTop = el.scrollTop;
            el.setPointerCapture(e.pointerId);
            el.dataset.dragging = 'true';
            e.preventDefault();
        };

        const onPointerMove = (e: PointerEvent) => {
            if (!dragging) return;
            el.scrollLeft = startLeft - (e.clientX - startX);
            el.scrollTop = startTop - (e.clientY - startY);
        };

        const endDrag = (e: PointerEvent) => {
            if (!dragging) return;
            dragging = false;
            delete el.dataset.dragging;
            if (el.hasPointerCapture(e.pointerId)) el.releasePointerCapture(e.pointerId);
        };

        const suppressAutoScroll = (e: MouseEvent) => {
            if (e.button === 1) e.preventDefault();
        };

        el.addEventListener('pointerdown', onPointerDown);
        el.addEventListener('pointermove', onPointerMove);
        el.addEventListener('pointerup', endDrag);
        el.addEventListener('pointercancel', endDrag);
        el.addEventListener('mousedown', suppressAutoScroll);
        el.addEventListener('auxclick', suppressAutoScroll);

        cleanupRef.current = () => {
            el.removeEventListener('pointerdown', onPointerDown);
            el.removeEventListener('pointermove', onPointerMove);
            el.removeEventListener('pointerup', endDrag);
            el.removeEventListener('pointercancel', endDrag);
            el.removeEventListener('mousedown', suppressAutoScroll);
            el.removeEventListener('auxclick', suppressAutoScroll);
        };
    }, []);
}
