import { Extension } from "@tiptap/core";

type FileReferenceStorage = {
    openRequest: number;
};

declare module "@tiptap/core" {
    interface Commands<ReturnType> {
        fileReference: {
            /**
             * Request the host UI to open the file-reference picker at the
             * current selection. Does not insert content by itself.
             */
            openFileReference: () => ReturnType;
        };
    }

    interface Storage {
        fileReference: FileReferenceStorage;
    }
}

/**
 * Bridge between the slash/toolbar file command and the React file-reference
 * picker. Incrementing `openRequest` lets the host open the popover without
 * wiring React refs into TipTap extension configuration during render.
 */
export const FileReference = Extension.create({
    name: "fileReference",

    addStorage(): FileReferenceStorage {
        return { openRequest: 0 };
    },

    addCommands() {
        return {
            openFileReference:
                () =>
                ({ editor, tr, dispatch }) => {
                    editor.storage.fileReference.openRequest += 1;
                    tr.setMeta("fileReferenceOpen", editor.storage.fileReference.openRequest);
                    dispatch?.(tr);
                    return true;
                },
        };
    },
});
