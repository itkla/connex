import { PIPELINE_EDIT_URL_KEY, parseDeepLinkId } from '@/app/hooks/listStateUrl';

/** Canonical href that opens one pipeline in the browser-managed edit sheet. */
export function pipelineEditHref(pipelineId: number): string {
    return `/records/pipelines?${PIPELINE_EDIT_URL_KEY}=${pipelineId}`;
}

/** Parses the pipeline edit-sheet identity from the browser's owned URL parameter. */
export function parsePipelineEditId(raw: string | null): number | null {
    return parseDeepLinkId(raw);
}
