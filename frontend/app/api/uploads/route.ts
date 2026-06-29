import { NextRequest, NextResponse } from "next/server";
import { headers } from "next/headers";
import path from "path";
import fs from "fs/promises";
import { getCurrentUserFromCookie } from "@/app/lib/api";
import { MAX_BYTES, resolveContained, safeFileName } from "@/app/lib/uploads";

const PUBLIC_PREFIX = "/attachments";

function uploadsBaseDir() {
    const baseDir = process.env.CONNEX_UPLOADS_DIR ?? path.join(process.cwd(), "public");
    return path.join(baseDir, "attachments");
}

function safeEntityType(value: string): string {
    return value.trim().toLowerCase().replace(/[^a-z0-9_-]/g, "");
}

export async function POST(request: NextRequest) {
    const cookie = (await headers()).get("cookie");
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
    }

    const formData = await request.formData();
    const entityTypeRaw = formData.get("entityType");
    const entityIdRaw = formData.get("entityId");
    const file = formData.get("file");

    if (typeof entityTypeRaw !== "string" || !safeEntityType(entityTypeRaw)) {
        return NextResponse.json({ error: "entityType is required" }, { status: 400 });
    }
    if (typeof entityIdRaw !== "string" || !/^\d+$/.test(entityIdRaw)) {
        return NextResponse.json({ error: "entityId is required" }, { status: 400 });
    }
    if (!file || typeof file !== "object" || !("arrayBuffer" in file)) {
        return NextResponse.json({ error: "file is required" }, { status: 400 });
    }

    const upload = file as File;
    if (upload.size > MAX_BYTES) {
        return NextResponse.json(
            { error: `File exceeds the maximum size of ${Math.floor(MAX_BYTES / (1024 * 1024))}MB` },
            { status: 413 },
        );
    }

    const entityType = safeEntityType(entityTypeRaw);
    const fileName = `${entityType}-${entityIdRaw}-${Date.now()}-${safeFileName(upload.name)}`;
    const targetDir = path.join(uploadsBaseDir(), entityType);
    const filePath = resolveContained(targetDir, fileName);
    if (!filePath) {
        return NextResponse.json({ error: "Invalid file name" }, { status: 400 });
    }
    const buffer = Buffer.from(await upload.arrayBuffer());

    await fs.mkdir(targetDir, { recursive: true });
    await fs.writeFile(filePath, buffer);

    return NextResponse.json({
        url: `${PUBLIC_PREFIX}/${entityType}/${fileName}`,
        fileName: upload.name,
        contentType: upload.type || "application/octet-stream",
        size: upload.size,
    });
}

export async function DELETE(request: NextRequest) {
    const cookie = (await headers()).get("cookie");
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
    }

    const url = request.nextUrl.searchParams.get("url");
    if (!url || !url.startsWith(`${PUBLIC_PREFIX}/`)) {
        return NextResponse.json({ error: "A valid attachment url is required" }, { status: 400 });
    }

    const baseDir = uploadsBaseDir();
    const relative = url.slice(`${PUBLIC_PREFIX}/`.length);
    const resolved = resolveContained(baseDir, relative);
    if (!resolved) {
        return NextResponse.json({ error: "Invalid attachment url" }, { status: 400 });
    }

    try {
        await fs.unlink(resolved);
    } catch {
        return NextResponse.json({ ok: true });
    }

    return NextResponse.json({ ok: true });
}
