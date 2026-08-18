import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

import { errorVoiceConfig } from "./lint/errorVoice.mjs";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // Error-voice gates and their shrinking exclusion inventory — see lint/errorVoice.mjs (#1337).
  ...errorVoiceConfig(),
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
    // Standalone React Email authoring package with its own toolchain/deps.
    "emails/**",
  ]),
]);

export default eslintConfig;
