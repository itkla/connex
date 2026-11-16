/** Detects active IME input, including Safari's confirmation key code after compositionend. */
export function isSearchComposing(event: { isComposing: boolean; keyCode: number }, composing: boolean): boolean {
    return composing || event.isComposing || event.keyCode === 229;
}
