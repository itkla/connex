'use client';

import { useCallback, useRef, useState } from 'react';

export interface ScrollEdges {
    left: boolean;
    right: boolean;
}

interface DragScrollOptions {
    leftDragSelector?: string;
    /** When a left-button pointerdown lands inside this selector, panning is suppressed so the element keeps its own drag (e.g. dnd-kit cards). */
    excludeDragSelector?: string;
}
const LEFT_DRAG_THRESHOLD = 5;

export function useDragScroll<T extends HTMLElement = HTMLElement>(options?: DragScrollOptions) {
    const leftDragSelector = options?.leftDragSelector;
    const excludeDragSelector = options?.excludeDragSelector;
    const cleanupRef = useRef<(() => void) | null>(null);
    const [edges, setEdges] = useState<ScrollEdges>({ left: false, right: false });

    const ref = useCallback((el: T | null) => {
        if (cleanupRef.current) {
            cleanupRef.current();
            cleanupRef.current = null;
        }
        if (!el) return;

        const updateEdges = () => {
            const { scrollLeft, scrollWidth, clientWidth } = el;
            const max = scrollWidth - clientWidth;
            setEdges({ left: scrollLeft > 1, right: scrollLeft < max - 1 });
        };

        const ro = new ResizeObserver(updateEdges);
        ro.observe(el);
        if (el.firstElementChild) ro.observe(el.firstElementChild);
        el.addEventListener('scroll', updateEdges, { passive: true });
        updateEdges();

        let dragging = false;
        let pendingLeft = false;
        let moved = false;
        let startX = 0;
        let startY = 0;
        let startLeft = 0;
        let startTop = 0;

        const beginDrag = (e: PointerEvent) => {
            dragging = true;
            el.setPointerCapture(e.pointerId);
            el.dataset.dragging = 'true';
        };

        const onPointerDown = (e: PointerEvent) => {
            moved = false;
            pendingLeft = false;
            if (e.button === 1) {
                startX = e.clientX;
                startY = e.clientY;
                startLeft = el.scrollLeft;
                startTop = el.scrollTop;
                beginDrag(e);
                e.preventDefault();
                return;
            }
            if (
                e.button === 0 &&
                leftDragSelector &&
                (e.target as Element | null)?.closest(leftDragSelector) &&
                !(excludeDragSelector && (e.target as Element | null)?.closest(excludeDragSelector))
            ) {
                pendingLeft = true;
                startX = e.clientX;
                startY = e.clientY;
                startLeft = el.scrollLeft;
                startTop = el.scrollTop;
            }
        };

        const onPointerMove = (e: PointerEvent) => {
            if (pendingLeft && !dragging) {
                if (
                    Math.abs(e.clientX - startX) > LEFT_DRAG_THRESHOLD ||
                    Math.abs(e.clientY - startY) > LEFT_DRAG_THRESHOLD
                ) {
                    moved = true;
                    beginDrag(e);
                }
            }
            if (!dragging) return;
            el.scrollLeft = startLeft - (e.clientX - startX);
            el.scrollTop = startTop - (e.clientY - startY);
            e.preventDefault();
        };

        const endDrag = (e: PointerEvent) => {
            pendingLeft = false;
            if (!dragging) return;
            dragging = false;
            delete el.dataset.dragging;
            if (el.hasPointerCapture(e.pointerId)) el.releasePointerCapture(e.pointerId);
        };

        const onClickCapture = (e: MouseEvent) => {
            if (moved) {
                e.stopPropagation();
                e.preventDefault();
                moved = false;
            }
        };

        const suppressAutoScroll = (e: MouseEvent) => {
            if (e.button === 1) e.preventDefault();
        };

        el.addEventListener('pointerdown', onPointerDown);
        el.addEventListener('pointermove', onPointerMove);
        el.addEventListener('pointerup', endDrag);
        el.addEventListener('pointercancel', endDrag);
        el.addEventListener('click', onClickCapture, true);
        el.addEventListener('mousedown', suppressAutoScroll);
        el.addEventListener('auxclick', suppressAutoScroll);

        cleanupRef.current = () => {
            ro.disconnect();
            el.removeEventListener('scroll', updateEdges);
            el.removeEventListener('pointerdown', onPointerDown);
            el.removeEventListener('pointermove', onPointerMove);
            el.removeEventListener('pointerup', endDrag);
            el.removeEventListener('pointercancel', endDrag);
            el.removeEventListener('click', onClickCapture, true);
            el.removeEventListener('mousedown', suppressAutoScroll);
            el.removeEventListener('auxclick', suppressAutoScroll);
        };
    }, [leftDragSelector, excludeDragSelector]);

    return { ref, edges };
}
