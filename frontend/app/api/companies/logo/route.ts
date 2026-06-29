import { NextRequest, NextResponse } from "next/server";
import { headers } from "next/headers";
import { getCurrentUserFromCookie } from "@/app/lib/api";
import { rejectInvalidImage, resolveContained, safeFileName } from "@/app/lib/uploads";
import path from "path";
import fs from "fs/promises";

/**
 * Stores an uploaded company logo under the public uploads directory and returns
 * its public URL. This route does not call the backend; the caller is
 * responsible for persisting the returned URL.
 */
export async function PUT(request: NextRequest) {
    const companyId = request.nextUrl.searchParams.get("companyId");
    const cookie = (await headers()).get("cookie");
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
    }
    if (!companyId || !/^\d+$/.test(companyId)) {
        return NextResponse.json({ error: "companyId is required" }, { status: 400 });
    }

    const formData = await request.formData();
    const companyLogo = formData.get("companyLogo");
    if (!companyLogo || typeof companyLogo !== "object" || !("arrayBuffer" in companyLogo)) {
        return NextResponse.json({ error: "Company logo is required" }, { status: 400 });
    }

    const upload = companyLogo as File;
    const rejection = rejectInvalidImage(upload);
    if (rejection) {
        return NextResponse.json({ error: rejection.error }, { status: rejection.status });
    }

    const fileName = `company-${companyId}-${Date.now()}-${safeFileName(upload.name)}`;
    const baseDir = process.env.CONNEX_UPLOADS_DIR ?? path.join(process.cwd(), "public");
    const publicDir = path.join(baseDir, "company-logos");
    const filePath = resolveContained(publicDir, fileName);
    if (!filePath) {
        return NextResponse.json({ error: "Invalid file name" }, { status: 400 });
    }

    const buffer = Buffer.from(await upload.arrayBuffer());
    await fs.mkdir(publicDir, { recursive: true });
    await fs.writeFile(filePath, buffer);

    return NextResponse.json({ logoUrl: `/company-logos/${fileName}` });
}
