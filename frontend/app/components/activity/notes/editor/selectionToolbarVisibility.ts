export type SelectionToolbarContext = {
    editable: boolean;
    textSelection: boolean;
    from: number;
    to: number;
    codeBlock: boolean;
};

/** Determines whether an inline-format toolbar belongs beside the current selection. */
export function canShowSelectionToolbar(context: SelectionToolbarContext): boolean {
    return context.editable
        && context.textSelection
        && context.from !== context.to
        && !context.codeBlock;
}
