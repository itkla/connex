import type { AppAction } from "./types";

/**
 * Resolves an action's name and the text it is searched by, for every surface that lists actions.
 *
 * An action names itself in one of three ways — the reader's own data, a key in a committed manifest,
 * or a key in the `Actions` catalog — and #1340 PR 7 added the middle one so a settings destination
 * carries the single name the manifest gives it onto every surface that offers it. One resolver
 * rather than a copy per surface, because a palette row and a record menu row showing the same
 * action under two names is exactly the drift the manifest exists to remove.
 */

/** Resolves a message key against a next-intl namespace or the whole catalog. */
export type MessageResolver = (key: string) => string;

/**
 * The name to render for an action.
 *
 * @param action - the registered action
 * @param translateAction - resolves a key within the `Actions` namespace
 * @param translateMessage - resolves an absolute key, namespace included
 * @returns the label, or an empty string for a registration that names none
 */
export function actionLabel(
    action: AppAction,
    translateAction: MessageResolver,
    translateMessage: MessageResolver,
): string {
    if (action.label !== undefined) return action.label;
    if (action.labelMessageKey !== undefined) return translateMessage(action.labelMessageKey);
    return action.labelKey === undefined ? "" : translateAction(action.labelKey);
}

/**
 * The lowercased haystack an action is matched against in the command palette: its name, its
 * supporting text, and every alias list it carries.
 *
 * @param action - the registered action
 * @param translateAction - resolves a key within the `Actions` namespace
 * @param translateMessage - resolves an absolute key, namespace included
 * @returns the searchable text
 */
export function actionSearchText(
    action: AppAction,
    translateAction: MessageResolver,
    translateMessage: MessageResolver,
): string {
    const parts = [actionLabel(action, translateAction, translateMessage)];
    if (action.descriptionKey) parts.push(translateAction(action.descriptionKey));
    if (action.keywords) parts.push(...action.keywords);
    if (action.keywordsKey) parts.push(translateAction(action.keywordsKey));
    if (action.keywordsMessageKey) parts.push(translateMessage(action.keywordsMessageKey));
    return parts.join(" ").toLowerCase();
}

/** Whether a registration names itself at all; see {@link AppAction.labelKey}. */
export function actionNamesItself(action: AppAction): boolean {
    return (
        action.label !== undefined
        || action.labelMessageKey !== undefined
        || action.labelKey !== undefined
    );
}
