import { describe, expect, it } from "vitest";
import {
    RESERVED_CHORDS,
    formatShortcut,
    normalizeShortcut,
    resolveShortcutPlatform,
} from "@/app/lib/actions/shortcut";

describe("normalizeShortcut", () => {
    it("lowercases, de-aliases, and orders modifiers mod-ctrl-alt-shift", () => {
        expect(normalizeShortcut("Cmd+Shift+K")).toBe("mod+shift+k");
        expect(normalizeShortcut("shift+CONTROL+x")).toBe("ctrl+shift+x");
        expect(normalizeShortcut("Option+Meta+P")).toBe("mod+alt+p");
    });

    it("collapses duplicate modifier aliases", () => {
        expect(normalizeShortcut("cmd+command+meta+k")).toBe("mod+k");
    });

    it("handles bare keys, whitespace, and empty input", () => {
        expect(normalizeShortcut("/")).toBe("/");
        expect(normalizeShortcut("  mod + k ")).toBe("mod+k");
        expect(normalizeShortcut("")).toBe("");
        expect(normalizeShortcut("mod+")).toBe("mod");
    });
});

describe("resolveShortcutPlatform", () => {
    it("maps Apple platform tokens to apple and everything else to other", () => {
        expect(resolveShortcutPlatform("MacIntel")).toBe("apple");
        expect(resolveShortcutPlatform("iPhone")).toBe("apple");
        expect(resolveShortcutPlatform("Win32")).toBe("other");
        expect(resolveShortcutPlatform("")).toBe("other");
    });
});

describe("formatShortcut", () => {
    it("uses compact glyphs on apple and joined labels elsewhere", () => {
        expect(formatShortcut("mod+k", "apple")).toBe("⌘K");
        expect(formatShortcut("mod+k", "other")).toBe("Ctrl+K");
        expect(formatShortcut("mod+shift+enter", "apple")).toBe("⌘⇧Enter");
    });

    it("dedupes adjacent identical labels (mod+ctrl both render Ctrl)", () => {
        expect(formatShortcut("mod+ctrl+k", "other")).toBe("Ctrl+K");
    });

    it("labels special keys and uppercases plain ones", () => {
        expect(formatShortcut("arrowup", "other")).toBe("↑");
        expect(formatShortcut("escape", "other")).toBe("Esc");
        expect(formatShortcut("g", "other")).toBe("G");
        expect(formatShortcut("", "other")).toBe("");
    });
});

describe("RESERVED_CHORDS", () => {
    it("reserves the search and escape chords in normalized form", () => {
        for (const chord of RESERVED_CHORDS) {
            expect(normalizeShortcut(chord)).toBe(chord);
        }
        expect(RESERVED_CHORDS.has("/")).toBe(true);
        expect(RESERVED_CHORDS.has("escape")).toBe(true);
    });
});
