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

function hasTextSelectionWithin(element: HTMLElement): boolean {
    const selection = window.getSelection();
    if (!selection || selection.isCollapsed || selection.anchorNode == null) return false;
    return element.contains(selection.anchorNode);
}

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
        let clickResetTimer: number | null = null;
        let classificationFrame: number | null = null;
        let pendingMovement: { pointerId: number; clientX: number; clientY: number } | null = null;
        let startX = 0;
        let startY = 0;
        let startLeft = 0;
        let startTop = 0;

        const applyScroll = (clientX: number, clientY: number) => {
            const previousLeft = el.scrollLeft;
            const previousTop = el.scrollTop;
            el.scrollLeft = startLeft - (clientX - startX);
            el.scrollTop = startTop - (clientY - startY);
            moved ||= el.scrollLeft !== previousLeft || el.scrollTop !== previousTop;
        };

        const cancelPending = () => {
            pendingLeft = false;
            pendingMovement = null;
            if (classificationFrame != null) window.cancelAnimationFrame(classificationFrame);
            classificationFrame = null;
        };

        const beginDrag = (pointerId: number): boolean => {
            try {
                el.setPointerCapture(pointerId);
            } catch {
                cancelPending();
                return false;
            }
            dragging = true;
            pendingLeft = false;
            pendingMovement = null;
            el.dataset.dragging = 'true';
            return true;
        };

        const classifyPending = () => {
            classificationFrame = null;
            const movement = pendingMovement;
            if (!pendingLeft || dragging || movement == null) return;
            if (hasTextSelectionWithin(el)) {
                cancelPending();
                return;
            }
            if (beginDrag(movement.pointerId)) applyScroll(movement.clientX, movement.clientY);
        };

        const scheduleClickReset = () => {
            if (!moved) return;
            clickResetTimer = window.setTimeout(() => {
                moved = false;
                clickResetTimer = null;
            }, 0);
        };

        const onPointerDown = (e: PointerEvent) => {
            if (clickResetTimer != null) window.clearTimeout(clickResetTimer);
            clickResetTimer = null;
            moved = false;
            cancelPending();
            if (e.button === 1) {
                startX = e.clientX;
                startY = e.clientY;
                startLeft = el.scrollLeft;
                startTop = el.scrollTop;
                beginDrag(e.pointerId);
                e.preventDefault();
                return;
            }
            const target = e.target instanceof Element ? e.target : null;
            if (
                e.button === 0 &&
                e.pointerType === 'mouse' &&
                leftDragSelector &&
                target?.closest(leftDragSelector) &&
                !(excludeDragSelector && target.closest(excludeDragSelector))
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
                if ((e.buttons & 1) === 0) {
                    cancelPending();
                    return;
                }
                const horizontalMovement = Math.abs(e.clientX - startX);
                const verticalMovement = Math.abs(e.clientY - startY);
                if (horizontalMovement <= LEFT_DRAG_THRESHOLD && verticalMovement <= LEFT_DRAG_THRESHOLD) return;
                if (verticalMovement >= horizontalMovement) {
                    cancelPending();
                    return;
                }
                pendingMovement = { pointerId: e.pointerId, clientX: e.clientX, clientY: e.clientY };
                if (classificationFrame == null) classificationFrame = window.requestAnimationFrame(classifyPending);
                return;
            }
            if (!dragging) return;
            applyScroll(e.clientX, e.clientY);
            e.preventDefault();
        };

        const endDrag = (e: PointerEvent) => {
            if (!pendingLeft && !dragging) return;
            const movement = pendingMovement;
            if (pendingLeft && movement != null && !hasTextSelectionWithin(el)) {
                applyScroll(movement.clientX, movement.clientY);
            }
            cancelPending();
            dragging = false;
            delete el.dataset.dragging;
            if (el.hasPointerCapture(e.pointerId)) el.releasePointerCapture(e.pointerId);
            scheduleClickReset();
        };

        const cancelDrag = (e: PointerEvent) => {
            if (!pendingLeft && !dragging) return;
            cancelPending();
            dragging = false;
            moved = false;
            delete el.dataset.dragging;
            if (el.hasPointerCapture(e.pointerId)) el.releasePointerCapture(e.pointerId);
        };

        const onClickCapture = (e: MouseEvent) => {
            if (moved) {
                e.stopPropagation();
                e.preventDefault();
                moved = false;
                if (clickResetTimer != null) window.clearTimeout(clickResetTimer);
                clickResetTimer = null;
            }
        };

        const suppressAutoScroll = (e: MouseEvent) => {
            if (e.button === 1) e.preventDefault();
        };

        el.addEventListener('pointerdown', onPointerDown);
        el.addEventListener('pointermove', onPointerMove);
        window.addEventListener('pointerup', endDrag);
        window.addEventListener('pointercancel', cancelDrag);
        el.addEventListener('click', onClickCapture, true);
        el.addEventListener('mousedown', suppressAutoScroll);
        el.addEventListener('auxclick', suppressAutoScroll);

        cleanupRef.current = () => {
            if (clickResetTimer != null) window.clearTimeout(clickResetTimer);
            if (classificationFrame != null) window.cancelAnimationFrame(classificationFrame);
            ro.disconnect();
            el.removeEventListener('scroll', updateEdges);
            el.removeEventListener('pointerdown', onPointerDown);
            el.removeEventListener('pointermove', onPointerMove);
            window.removeEventListener('pointerup', endDrag);
            window.removeEventListener('pointercancel', cancelDrag);
            el.removeEventListener('click', onClickCapture, true);
            el.removeEventListener('mousedown', suppressAutoScroll);
            el.removeEventListener('auxclick', suppressAutoScroll);
        };
    }, [leftDragSelector, excludeDragSelector]);

    return { ref, edges };
}
