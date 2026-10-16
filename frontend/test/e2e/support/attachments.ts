import { expect, type APIRequestContext } from "@playwright/test";

import { csrfBootstrap, type RunFixture } from "./api";

/** A 10×10 PNG: an upload the attachment pipeline accepts and the Files library previews. */
const PIXEL_PNG =
    "iVBORw0KGgoAAAANSUhEUgAAAAoAAAAKCAYAAACNMs+9AAAADklEQVR4XmNgGAWDEwAAAZoAAWA5V18AAAAASUVORK5CYII=";

/** The tenant and CSRF headers every attachment write needs. */
async function writeHeaders(api: APIRequestContext, fixture: RunFixture): Promise<Record<string, string>> {
    const csrf = await csrfBootstrap(api);
    return {
        "X-Workspace-Id": String(fixture.workspaceId),
        [csrf.headerName]: csrf.token,
    };
}

/**
 * Uploads a PNG onto a contact through the multipart endpoint the record page's uploader uses, so
 * the Files library has a real file to list, filter, sort and open.
 *
 * @param api request context carrying the tenant's session
 * @param fixture the tenant the contact belongs to
 * @param personId the contact the file is attached to
 * @param fileName the name the library shows, unique to the calling test
 * @returns the created attachment's id
 */
export async function uploadContactAttachment(
    api: APIRequestContext,
    fixture: RunFixture,
    personId: number,
    fileName: string,
): Promise<number> {
    const response = await api.post("/api/attachments/upload", {
        timeout: 120_000,
        headers: await writeHeaders(api, fixture),
        multipart: {
            entityType: "person",
            entityId: String(personId),
            file: {
                name: fileName,
                mimeType: "image/png",
                buffer: Buffer.from(PIXEL_PNG, "base64"),
            },
        },
    });
    expect(response.status(), `POST /api/attachments/upload: ${(await response.text()).slice(0, 500)}`).toBe(200);
    const created: unknown = await response.json();
    if (typeof created !== "object" || created === null || !("id" in created) || typeof created.id !== "number") {
        throw new Error("The attachment upload response carries no numeric id");
    }
    return created.id;
}

/**
 * Deletes an attachment a test uploaded, so the shared tenant's library does not grow per run.
 *
 * @param api request context carrying the tenant's session
 * @param fixture the tenant the attachment belongs to
 * @param attachmentId the attachment to delete
 */
export async function deleteAttachment(
    api: APIRequestContext,
    fixture: RunFixture,
    attachmentId: number,
): Promise<void> {
    const response = await api.delete(`/api/attachments/${attachmentId}`, {
        timeout: 120_000,
        headers: await writeHeaders(api, fixture),
    });
    expect(response.ok(), `DELETE /api/attachments/${attachmentId}: ${response.status()}`).toBe(true);
}
