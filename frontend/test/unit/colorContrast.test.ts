import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { compile } from "tailwindcss";

const css = readFileSync("app/globals.css", "utf8");
type Color = readonly [number, number, number];

function declarations(source: string): Record<string, string> {
    return Object.fromEntries(Array.from(source.replace(/\/\*[\s\S]*?\*\//g, "").matchAll(/(--[\w-]+):\s*([^;]+);/g), ([, name, value]) => [name, value]));
}

function themeTokens(theme: "light" | "dark"): Record<string, string> {
    const palette = css.match(/@theme \{([^}]+)\}/)?.[1];
    const light = css.match(/:root,\s*\.light-surface \{([^}]+)\}/)?.[1];
    const dark = css.match(/\.dark \{([^}]+)\}/)?.[1];
    const aliases = css.match(/@theme inline \{([^}]+)\}/)?.[1];
    const feedbackAliases = css.match(/@theme static \{([^}]+)\}/)?.[1];
    if (!palette || !light || !dark || !aliases) throw new Error("Missing theme token block");
    return declarations([palette, aliases, feedbackAliases, light, theme === "dark" ? dark : ""].join("\n"));
}

function linear(channel: number): number {
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
}

function encoded(channel: number): number {
    return channel <= 0.0031308 ? channel * 12.92 : 1.055 * channel ** (1 / 2.4) - 0.055;
}

function color(tokens: Record<string, string>, name: string, background?: Color, visited = new Set<string>()): Color {
    if (visited.has(name)) throw new Error(`Circular token ${name}`);
    visited.add(name);
    const value = tokens[name];
    if (!value) throw new Error(`Undefined token ${name}`);
    const alias = value.match(/^var\((--[\w-]+)\)$/);
    if (alias) return color(tokens, alias[1], background, visited);
    if (/^#[\da-f]{6}$/i.test(value)) {
        return [linear(parseInt(value.slice(1, 3), 16) / 255), linear(parseInt(value.slice(3, 5), 16) / 255), linear(parseInt(value.slice(5, 7), 16) / 255)];
    }
    const oklch = value.match(/^oklch\(([\d.]+) ([\d.]+) ([\d.]+)(?: \/ ([\d.]+)%)?\)$/);
    if (!oklch) throw new Error(`Unsupported color ${value}`);
    const lightness = Number(oklch[1]);
    const chroma = Number(oklch[2]);
    const hue = Number(oklch[3]) * Math.PI / 180;
    const a = chroma * Math.cos(hue);
    const b = chroma * Math.sin(hue);
    const l = (lightness + 0.3963377774 * a + 0.2158037573 * b) ** 3;
    const m = (lightness - 0.1055613458 * a - 0.0638541728 * b) ** 3;
    const s = (lightness - 0.0894841775 * a - 1.2914855480 * b) ** 3;
    const rgb: Color = [
        Math.max(0, Math.min(1, 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s)),
        Math.max(0, Math.min(1, -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s)),
        Math.max(0, Math.min(1, -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)),
    ];
    if (!oklch[4]) return rgb;
    if (!background) throw new Error(`An alpha surface needs its background: ${name}`);
    const alpha = Number(oklch[4]) / 100;
    const blend = (index: 0 | 1 | 2) => linear(encoded(rgb[index]) * alpha + encoded(background[index]) * (1 - alpha));
    return [blend(0), blend(1), blend(2)];
}

function luminance([r, g, b]: Color): number {
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(foreground: Color, background: Color): number {
    const values = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
    return (values[0] + 0.05) / (values[1] + 0.05);
}

const surfaces = ["--background", "--card", "--popover", "--muted", "--sidebar", "--sidebar-accent", "--color-brand-light", "--input"];
const feedback = ["--color-brand", "--color-destructive", "--color-info", "--color-warning"];

for (const theme of ["light", "dark"] as const) {
    describe(`${theme} WCAG AA text contrast`, () => {
        const tokens = themeTokens(theme);
        for (const surface of surfaces) {
            for (const text of ["--color-brand-text", "--muted-foreground"]) {
                it(`${text} on ${surface} is at least 4.5:1`, () => {
                    const ratio = contrast(color(tokens, text), color(tokens, surface, color(tokens, "--background")));
                    expect(ratio, `${theme} ${text} on ${surface}: ${ratio.toFixed(2)}:1`).toBeGreaterThanOrEqual(4.5);
                });
            }
        }
        for (const background of feedback) {
            it(`${background} title and inherited description are at least 4.5:1`, () => {
                const ratio = contrast(color(tokens, `${background}-foreground`), color(tokens, background));
                expect(ratio, `${theme} ${background}: ${ratio.toFixed(2)}:1`).toBeGreaterThanOrEqual(4.5);
            });
        }
    });
}

it("preserves the brand lime and makes Sonner descriptions inherit their variant foreground", () => {
    expect(themeTokens("light")["--color-brand"]).toBe("#73d200");
    expect(css).toMatch(/\.cn-toast\[data-sonner-toast\]\[data-styled="true"\] \[data-description\]\s*\{\s*color: inherit;/);
});


it("emits feedback variables even when no utility consumes their colors", async () => {
    const themes = Array.from(css.matchAll(/@theme(?: inline| static)? \{[^}]+\}/g), ([block]) => block).join("\n");
    const compiler = await compile(themes);
    const output = compiler.build([]);
    for (const token of ["--color-info", "--color-warning", "--color-info-foreground", "--color-warning-foreground", "--color-destructive-foreground"]) {
        expect(output).toContain(`${token}:`);
    }
});

for (const theme of ["light", "dark"] as const) {
    for (const paper of ["report-paper", "document-paper"]) {
        it(`${paper} uses readable light green text when printing from ${theme} mode`, () => {
            const overrides = css.match(new RegExp(`\\.${paper} \\{([^}]+)\\}`))?.[1];
            if (!overrides) throw new Error(`Missing ${paper} print surface`);
            const tokens = { ...themeTokens(theme), ...declarations(overrides) };
            expect(tokens["--brand-text"]).toBe(themeTokens("light")["--brand-text"]);
            expect(contrast(color(tokens, "--color-brand-text"), color(tokens, "--card"))).toBeGreaterThanOrEqual(4.5);
        });
    }

    it(`the forced-light onboarding surface keeps readable green text inside ${theme} mode`, () => {
        const overrides = css.match(/:root,\s*\.light-surface \{([^}]+)\}/)?.[1];
        if (!overrides) throw new Error("Missing forced-light surface");
        const tokens = { ...themeTokens(theme), ...declarations(overrides) };
        expect(tokens["--brand-text"]).toBe(themeTokens("light")["--brand-text"]);
        expect(contrast(color(tokens, "--color-brand-text"), color(tokens, "--background"))).toBeGreaterThanOrEqual(4.5);
    });
}
