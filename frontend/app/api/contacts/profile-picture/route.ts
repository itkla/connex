import { NextRequest, NextResponse } from "next/server";
import { headers } from "next/headers";
import { getCurrentUserFromCookie, workspaceCanAccessEntity } from "@/app/lib/api";
import { rejectInvalidImage, resolveContained, safeFileName } from "@/app/lib/uploads";
import path from "path";
import fs from "fs/promises";

/**
 * Stores an uploaded contact picture under the public uploads directory and
 * returns its public URL. The caller must belong to the workspace that owns the
 * contact; persisting the returned URL is still done via the backend by the caller.
 */
export async function PUT(request: NextRequest) {
    const contactId = request.nextUrl.searchParams.get("contactId");
    const cookie = (await headers()).get("cookie");
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
    }
    if (!contactId || !/^\d+$/.test(contactId)) {
        return NextResponse.json({ error: "contactId is required" }, { status: 400 });
    }
    if (!(await workspaceCanAccessEntity(cookie, "person", Number(contactId)))) {
        return NextResponse.json({ error: "Forbidden" }, { status: 403 });
    }

    const formData = await request.formData();
    const contactPicture = formData.get("contactPicture");
    if (!contactPicture || typeof contactPicture !== "object" || !("arrayBuffer" in contactPicture)) {
        return NextResponse.json({ error: "Contact picture is required" }, { status: 400 });
    }

    const upload = contactPicture as File;
    const rejection = rejectInvalidImage(upload);
    if (rejection) {
        return NextResponse.json({ error: rejection.error }, { status: rejection.status });
    }

    const fileName = `contact-${contactId}-${Date.now()}-${safeFileName(upload.name)}`;
    const baseDir = process.env.CONNEX_UPLOADS_DIR ?? path.join(process.cwd(), "public");
    const publicDir = path.join(baseDir, "contact-pictures");
    const filePath = resolveContained(publicDir, fileName);
    if (!filePath) {
        return NextResponse.json({ error: "Invalid file name" }, { status: 400 });
    }

    const buffer = Buffer.from(await upload.arrayBuffer());
    await fs.mkdir(publicDir, { recursive: true });
    await fs.writeFile(filePath, buffer);

    return NextResponse.json({ imageUrl: `/contact-pictures/${fileName}` });
}
