import { NextRequest, NextResponse } from "next/server";
import { headers } from "next/headers";
import { getCurrentUserFromCookie } from "@/app/lib/api";
import { rejectInvalidImage, resolveContained, safeFileName } from "@/app/lib/uploads";
import path from "path";
import fs from "fs/promises";

/**
 * Stores an uploaded profile picture for the current user under the public
 * uploads directory and returns its public URL. This route does not call the
 * backend; the caller is responsible for persisting the returned URL.
 */
export async function PUT(request: NextRequest) {
    const cookie = (await headers()).get("cookie");
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
    }

    const formData = await request.formData();
    const profilePicture = formData.get("profilePicture");
    if (!profilePicture || typeof profilePicture !== "object" || !("arrayBuffer" in profilePicture)) {
        return NextResponse.json({ error: "Profile picture is required" }, { status: 400 });
    }

    const upload = profilePicture as File;
    const rejection = rejectInvalidImage(upload);
    if (rejection) {
        return NextResponse.json({ error: rejection.error }, { status: rejection.status });
    }

    const fileName = `user-${user.id}-${Date.now()}-${safeFileName(upload.name)}`;
    const baseDir = process.env.CONNEX_UPLOADS_DIR ?? path.join(process.cwd(), "public");
    const publicDir = path.join(baseDir, "profile-pictures");
    const filePath = resolveContained(publicDir, fileName);
    if (!filePath) {
        return NextResponse.json({ error: "Invalid file name" }, { status: 400 });
    }

    const buffer = Buffer.from(await upload.arrayBuffer());
    await fs.mkdir(publicDir, { recursive: true });
    await fs.writeFile(filePath, buffer);

    return NextResponse.json({ profilePictureUrl: `/profile-pictures/${fileName}` });
}
