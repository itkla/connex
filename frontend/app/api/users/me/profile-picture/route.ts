// accept a PUT request to update the profile picture of the current user, then move the file to the /public/profile-pictures directory

// THIS API ROUTE DOES NOT MAKE THE PUT REQUEST TO THE BACKEND, IT ONLY UPLOADS THE FILE TO THE /public/profile-pictures DIRECTORY. DO THE API REQUEST AT SOURCE

import { NextRequest, NextResponse } from "next/server";
import { headers } from "next/headers";
import { getCurrentUserFromCookie } from "@/app/lib/api";
import path from "path";
import fs from "fs/promises";

export async function PUT(request: NextRequest) {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
    }
    const formData = await request.formData();
    const profilePicture = formData.get("profilePicture");
    if (!profilePicture || typeof profilePicture !== "object" || !("arrayBuffer" in profilePicture)) {
        return NextResponse.json({ error: "Profile picture is required" }, { status: 400 });
    }
    
    // Extract filename and buffer
    const fileName = `user-${user.id}-${Date.now()}-${(profilePicture as File).name}`;
    const buffer = Buffer.from(await (profilePicture as File).arrayBuffer());
    // const publicDir = path.join(process.cwd(), "public", "profile-pictures");
    const baseDir = process.env.CONNEX_UPLOADS_DIR ?? path.join(process.cwd(), "public");
    const publicDir = path.join(baseDir, "profile-pictures");
    const filePath = path.join(publicDir, fileName);

    // Ensure /public/profile-pictures directory exists
    await fs.mkdir(publicDir, { recursive: true });

    // Save the file to /public/profile-pictures
    await fs.writeFile(filePath, buffer);

    // Construct the public URL to the file
    const profilePictureUrl = `/profile-pictures/${fileName}`;

    return NextResponse.json({ profilePictureUrl: profilePictureUrl });
}