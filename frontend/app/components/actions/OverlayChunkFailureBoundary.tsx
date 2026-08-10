"use client";

import { Component, type ReactNode } from "react";

type OverlayChunkFailureBoundaryProps = {
    onFailure: () => void;
    children: ReactNode;
};

type OverlayChunkFailureBoundaryState = {
    failed: boolean;
};

/** Releases a shell-owned overlay when its lazy component cannot render. */
export class OverlayChunkFailureBoundary extends Component<
    OverlayChunkFailureBoundaryProps,
    OverlayChunkFailureBoundaryState
> {
    state: OverlayChunkFailureBoundaryState = { failed: false };

    static getDerivedStateFromError(): OverlayChunkFailureBoundaryState {
        return { failed: true };
    }

    componentDidCatch() {
        this.props.onFailure();
    }

    render() {
        return this.state.failed ? null : this.props.children;
    }
}
