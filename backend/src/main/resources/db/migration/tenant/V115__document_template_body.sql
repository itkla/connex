-- Free-form block body for document templates (#714). Stores ProseMirror/Tiptap JSON authored in the
-- block builder: regular text plus blocks (line-items placeholder, terms, footer) with per-block
-- alignment. Legacy title/intro/terms/footer stay for backward-compatible rendering of older templates.
ALTER TABLE document_template
    ADD COLUMN body LONGTEXT NULL COMMENT 'Block-builder document body as ProseMirror/Tiptap JSON (merge tokens resolved at generation)' AFTER footer;
