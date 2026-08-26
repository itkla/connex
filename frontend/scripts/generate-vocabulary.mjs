#!/usr/bin/env node
import { writeFileSync } from "node:fs";
import { resolve } from "node:path";

import { buildVocabularyModel, readProductGuide } from "../lint/vocabulary.mjs";

/**
 * Regenerates `frontend/lint/vocabulary.generated.json` from §4 of `docs/PRODUCT.md`.
 * Run it after editing the glossary; `test/unit/vocabularyGenerator.test.ts` fails until
 * the committed model matches the section again.
 */

const OUTPUT_PATH = resolve(import.meta.dirname, "..", "lint", "vocabulary.generated.json");

const model = buildVocabularyModel(readProductGuide());
writeFileSync(OUTPUT_PATH, `${JSON.stringify(model, null, 4)}\n`, "utf8");
process.stdout.write(`Wrote ${model.terms.length} banned terms and ${model.skipped.length} curated skips to ${OUTPUT_PATH}\n`);
