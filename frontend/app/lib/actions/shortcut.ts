/**
 * Chords owned by existing global keyboard listeners (the search `/` shortcut and the shell `Escape`
 * handler). The registry refuses to bind them so its metadata never promises a chord it cannot honor.
 */
export const RESERVED_CHORDS: ReadonlySet<string> = new Set(["/", "escape"]);

const MODIFIER_ORDER = ["mod", "ctrl", "alt", "shift"] as const;

const APPLE_MODIFIER_LABELS: Record<string, string> = {
    mod: "⌘",
    ctrl: "⌃",
    alt: "⌥",
    shift: "⇧",
};

const OTHER_MODIFIER_LABELS: Record<string, string> = {
    mod: "Ctrl",
    ctrl: "Ctrl",
    alt: "Alt",
    shift: "Shift",
};

const KEY_LABELS: Record<string, string> = {
    arrowdown: "↓",
    arrowleft: "←",
    arrowright: "→",
    arrowup: "↑",
    backspace: "Backspace",
    delete: "Delete",
    end: "End",
    enter: "Enter",
    escape: "Esc",
    home: "Home",
    pagedown: "Page Down",
    pageup: "Page Up",
    space: "Space",
    tab: "Tab",
};

const MODIFIER_ALIASES: Record<string, string> = {
    mod: "mod",
    cmd: "mod",
    command: "mod",
    meta: "mod",
    "⌘": "mod",
    ctrl: "ctrl",
    control: "ctrl",
    alt: "alt",
    opt: "alt",
    option: "alt",
    shift: "shift",
};

/**
 * Canonicalizes a keyboard chord so equivalent chords compare equal: tokens are lowercased and
 * trimmed, modifiers de-aliased (`cmd`/`command`/`meta`/`⌘` → `mod`, `control` → `ctrl`,
 * `opt`/`option` → `alt`), ordered `mod → ctrl → alt → shift`, then joined to a single key with `+`.
 * For example `Cmd+Shift+K` normalizes to `mod+shift+k`.
 *
 * @param chord - a human-authored chord such as `mod+shift+k`, or a bare key like `/`
 * @returns the normalized chord string
 */
export function normalizeShortcut(chord: string): string {
    const tokens = chord
        .trim()
        .toLowerCase()
        .split("+")
        .map((token) => token.trim())
        .filter(Boolean);
    if (tokens.length === 0) return "";

    const modifiers = new Set<string>();
    let key = "";
    for (const token of tokens) {
        const alias = MODIFIER_ALIASES[token];
        if (alias) modifiers.add(alias);
        else key = token;
    }

    const ordered = MODIFIER_ORDER.filter((modifier) => modifiers.has(modifier));
    return key ? [...ordered, key].join("+") : ordered.join("+");
}

/** The keyboard-label family appropriate for the current operating system. */
export type ShortcutPlatform = "apple" | "other";

/**
 * Resolves whether shortcut hints should use Apple modifier glyphs from the browser platform token.
 * Unknown or privacy-reduced values deliberately fall back to the broadly understood Ctrl labels.
 */
export function resolveShortcutPlatform(platform: string): ShortcutPlatform {
    return /^(?:Mac|iPhone|iPad|iPod)/i.test(platform) ? "apple" : "other";
}

/**
 * Formats platform-neutral shortcut metadata for display after normalizing modifier aliases and order.
 * Apple hints use compact glyphs; other platforms use explicit modifier names separated by plus signs.
 */
export function formatShortcut(chord: string, platform: ShortcutPlatform): string {
    const normalized = normalizeShortcut(chord);
    if (!normalized) return "";

    const modifierLabels = platform === "apple" ? APPLE_MODIFIER_LABELS : OTHER_MODIFIER_LABELS;
    const labels = normalized.split("+").map((token) => {
        const modifier = modifierLabels[token];
        if (modifier) return modifier;
        const key = KEY_LABELS[token];
        if (key) return key;
        return token.toUpperCase();
    });
    const distinctLabels = labels.filter((label, index) => index === 0 || label !== labels[index - 1]);
    return distinctLabels.join(platform === "apple" ? "" : "+");
}
