import { notFound } from 'next/navigation';

import AskConnexWorkspaceMount from '@/app/components/ask-connex/AskConnexWorkspaceMount';

export default async function AskConnexSessionPage({
    params,
}: {
    params: Promise<{ sessionId: string }>;
}) {
    const { sessionId } = await params;
    if (!/^[1-9]\d*$/.test(sessionId)) notFound();
    const id = Number(sessionId);
    if (!Number.isInteger(id) || id > 2_147_483_647) notFound();
    return <AskConnexWorkspaceMount />;
}
