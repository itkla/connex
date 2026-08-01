'use client';

import { Component, type ReactNode } from 'react';

import SectionUnavailable from '@/app/components/SectionUnavailable';
import { reportBoundaryError } from '@/app/lib/clientErrorReporter';

type SectionBoundaryProps = {
    children: ReactNode;
    title?: string;
    body?: string;
    /**
     * When this value changes, a caught error is cleared and the children are re-rendered. Pass a
     * value that changes on genuinely-new content — the active workspace id, for instance — so a
     * failure captured in one context does not stay stuck after the context that caused it is gone.
     */
    resetKey?: string | number | null;
};

type SectionBoundaryState = {
    error: (Error & { digest?: string }) | null;
    renderedKey: string | number | null | undefined;
};

/**
 * Contains a client render failure to one section of a page.
 *
 * This is **not** a substitute for failure-aware data fetching, and the two must not
 * be collapsed into each other. A React error boundary only sees errors thrown while
 * rendering its children on the client; a server fetch that failed during the request
 * never throws here at all — it arrives as absent data, which is exactly the case that
 * `resultWithCookie` exists to report. Use this to stop one broken widget from taking
 * down a whole page, and use the result-shaped fetchers to avoid presenting a backend
 * fault as an empty workspace.
 *
 * Layout-transparent: until a child throws, it renders `children` untouched and adds
 * no wrapper element, so it can wrap grid cells without disturbing their layout.
 */
export default class SectionBoundary extends Component<SectionBoundaryProps, SectionBoundaryState> {
    state: SectionBoundaryState = { error: null, renderedKey: this.props.resetKey };

    static getDerivedStateFromError(error: Error & { digest?: string }): Partial<SectionBoundaryState> {
        return { error };
    }

    static getDerivedStateFromProps(
        props: SectionBoundaryProps,
        state: SectionBoundaryState,
    ): Partial<SectionBoundaryState> | null {
        if (props.resetKey !== state.renderedKey) {
            return { error: null, renderedKey: props.resetKey };
        }
        return null;
    }

    componentDidCatch(error: Error & { digest?: string }): void {
        reportBoundaryError(error);
    }

    render(): ReactNode {
        if (this.state.error) {
            return (
                <SectionUnavailable
                    title={this.props.title}
                    body={this.props.body}
                    onReset={() => this.setState({ error: null })}
                />
            );
        }
        return this.props.children;
    }
}
