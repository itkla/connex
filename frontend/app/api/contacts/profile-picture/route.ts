// Uploads a contact picture to /public/contact-pictures and returns the public URL

// note to future hunter: serverless platforms like vercel dont support fs, so we either need to build/host ourselves OR use a different storage solution.
import { NextRequest, NextResponse } from "next/server";
import path from "path";
import fs from "fs/promises";

export async function PUT(request: NextRequest) {
    const contactId = request.nextUrl.searchParams.get("contactId");
    if (!contactId) {
        return NextResponse.json({ error: "contactId is required" }, { status: 400 });
    }

    const formData = await request.formData();
    const contactPicture = formData.get("contactPicture");
    if (!contactPicture || typeof contactPicture !== "object" || !("arrayBuffer" in contactPicture)) {
        return NextResponse.json({ error: "Contact picture is required" }, { status: 400 });
    }

    const fileName = `contact-${contactId}-${Date.now()}-${(contactPicture as File).name}`;
    const buffer = Buffer.from(await (contactPicture as File).arrayBuffer());
    const publicDir = path.join(process.cwd(), "public", "contact-pictures");
    const filePath = path.join(publicDir, fileName);

    await fs.mkdir(publicDir, { recursive: true });
    await fs.writeFile(filePath, buffer);

    return NextResponse.json({ imageUrl: `/contact-pictures/${fileName}` });
}
