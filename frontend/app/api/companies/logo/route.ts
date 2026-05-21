// Uploads a company logo to /public/company-logos and returns the public URL

import { NextRequest, NextResponse } from "next/server";
import path from "path";
import fs from "fs/promises";

export async function PUT(request: NextRequest) {
    const companyId = request.nextUrl.searchParams.get("companyId");
    if (!companyId) {
        return NextResponse.json({ error: "companyId is required" }, { status: 400 });
    }

    const formData = await request.formData();
    const companyLogo = formData.get("companyLogo");
    if (!companyLogo || typeof companyLogo !== "object" || !("arrayBuffer" in companyLogo)) {
        return NextResponse.json({ error: "Company logo is required" }, { status: 400 });
    }

    const fileName = `company-${companyId}-${Date.now()}-${(companyLogo as File).name}`;
    const buffer = Buffer.from(await (companyLogo as File).arrayBuffer());
    const publicDir = path.join(process.cwd(), "public", "company-logos");
    const filePath = path.join(publicDir, fileName);

    await fs.mkdir(publicDir, { recursive: true });
    await fs.writeFile(filePath, buffer);

    return NextResponse.json({ logoUrl: `/company-logos/${fileName}` });
}