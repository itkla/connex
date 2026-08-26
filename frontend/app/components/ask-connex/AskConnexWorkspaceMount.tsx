'use client';

import { useAskConnexMount } from '@/app/components/ask-connex/AskConnexProvider';

/**
 * The routed Ask Connex workspace's canvas.
 *
 * `/ask-connex` and `/ask-connex/[sessionId]` are separate route segments rendering separate
 * elements, so this container is mounted afresh whenever the route moves between them — including
 * on the replace that follows creating or switching a chat. Registering the live node through the
 * provider rather than letting the controller look it up keeps the portal target and the rendered
 * element the same object across every one of those transitions.
 */
export default function AskConnexWorkspaceMount() {
    const { registerWorkspaceRoot } = useAskConnexMount();
    return <div ref={registerWorkspaceRoot} className="h-full min-h-0" />;
}
