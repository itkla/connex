import { readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { createTranslator } from "next-intl";
import { describe, expect, it, vi } from "vitest";

import OpenGraphImage, { alt, contentType, size } from "@/app/opengraph-image";
import en from "@/messages/en/common.json";

vi.mock("next-intl/server", () => ({
    getTranslations: async (namespace: string) =>
        createTranslator({ locale: "en", messages: en, namespace }),
}));

const { default: manifest } = await import("@/app/manifest");

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

function pngDimensions(path: string): { width: number; height: number } {
    const file = readFileSync(join(process.cwd(), path));
    expect(file.subarray(0, 8)).toEqual(PNG_SIGNATURE);
    return { width: file.readUInt32BE(16), height: file.readUInt32BE(20) };
}

describe("open graph image", () => {
    it("declares the share-card contract social networks read", () => {
        expect(size).toEqual({ width: 1200, height: 630 });
        expect(contentType).toBe("image/png");
        expect(alt).toBe(en.CommonHome.brand);
    });

    it("renders a real PNG rather than failing the build", async () => {
        const response = OpenGraphImage();
        const rendered = Buffer.from(await response.arrayBuffer());
        expect(response.headers.get("content-type")).toBe("image/png");
        expect(rendered.subarray(0, 8)).toEqual(PNG_SIGNATURE);
        expect(rendered.byteLength).toBeGreaterThan(1_000);
    });
});

describe("manifest", () => {
    it("installs under the brand, in the brand colour", async () => {
        const resolved = await manifest();
        expect(resolved.name).toBe(en.AppMetadata.title);
        expect(resolved.short_name).toBe(en.CommonHome.brand);
        expect(resolved.description).toBe(en.AppMetadata.description);
        expect(resolved.start_url).toBe("/");
        expect(resolved.display).toBe("standalone");
        expect(resolved.theme_color).toBe("#73d200");
        expect(resolved.background_color).toBe("#73d200");
    });

    it("points every declared icon at a file that exists at the size it claims", async () => {
        const icons = (await manifest()).icons ?? [];
        expect(icons.map((icon) => icon.purpose)).toEqual(["any", "maskable", "any", "maskable"]);
        for (const icon of icons) {
            const [width] = (icon.sizes ?? "").split("x").map(Number);
            expect(pngDimensions(join("public", icon.src))).toEqual({ width, height: width });
        }
        expect(icons.some((icon) => icon.sizes === "192x192")).toBe(true);
        expect(icons.some((icon) => icon.sizes === "512x512")).toBe(true);
    });
});

describe("browser and home-screen icons", () => {
    it("ships an apple touch icon at the size the convention expects", () => {
        expect(pngDimensions("app/apple-icon.png")).toEqual({ width: 180, height: 180 });
    });

    it("still answers the literal /favicon.ico that older clients request", () => {
        const favicon = readFileSync(join(process.cwd(), "app/favicon.ico"));
        expect(favicon.readUInt16LE(0)).toBe(0);
        expect(favicon.readUInt16LE(2)).toBe(1);
        expect(favicon.readUInt16LE(4)).toBe(1);
        expect(statSync(join(process.cwd(), "app/icon.svg")).isFile()).toBe(true);
    });
});
