# Upload content inspection — the boundary contract

Every byte a user uploads to Connex passes through exactly one gate before it can be stored,
served, or forwarded: `UploadContentInspector`. This document is the contract that gate offers,
so that new ingress surfaces — the CRM-native file workspace of [#1122] (folders, standalone
uploads, external file requests, provider-backed references) and anything after it — reuse the
boundary instead of growing a second, weaker path.

Related: [`MALWARE_SCANNING.md`](MALWARE_SCANNING.md) (inspection precedes scanning),
[`SECURITY.md`](SECURITY.md), [`ENCRYPTION_GUARANTEE_MATRIX.md`](ENCRYPTION_GUARANTEE_MATRIX.md).

Pinned by `backend/src/test/java/ooo/klae/connex/backend/architecture/UploadContentInspectionBoundaryArchTest.java`
and exercised by `UploadContentInspectorTest` and `UploadMaliciousFixtureCorpusTest`.

## 1. The guarantee

Given a server-selected purpose and an untrusted upload, the inspector either returns an
`InspectedUpload` whose bytes have been structurally proven to be the format the purpose allows,
or it fails closed. There is no third outcome: no "unknown but probably fine", no deferred
inspection, no partial acceptance.

Refusals are deliberately uninformative to the caller, so a probe cannot map the rules:

| Condition | Result |
|---|---|
| Format, extension, declared type, or structure disagrees; parse error; active content; exceeded bound; timeout | `UnsupportedUploadMediaTypeException` → **415** |
| Empty upload, or an assistant image whose dimensions are rejected | `BadRequestException` → **400** |
| Upload exceeds the configured maximum length | `RequestBodyTooLargeException` → **413** |
| Inspector saturated, or the upload stream could not be read | `ServiceUnavailableException` → **503** |

Inspection runs on a bounded executor (4 workers, queue 8, abort policy) under a 5-second
wall-clock deadline that every walker checks cooperatively. Any other throwable is funnelled to
the 415 above, so a new parser bug cannot become an accept.

## 2. Entry points, and the only writer

```
UploadContentInspector.inspect(UploadPurpose, UploadSource)   → InspectedUpload
UploadContentInspector.inspectLegacyAttachment(UploadSource)  → InspectedUpload
UploadMalwareScanner.scan(InspectedUpload)                    → ScannedUpload
ManagedObjectService.storeInspectedAttachment(workspace, ScannedUpload)
```

- `inspect` is the only entry for new uploads. The purpose is chosen by the server, never by the
  client, and it selects the format allowlist.
- `inspectLegacyAttachment` infers the format from magic bytes for the historical migration path,
  never trusting the stored declared type, and then runs the identical inspection.
- `ManagedObjectService` holds the single `objectStorage.put(...)` call site.
- `storeMigratedAttachment` is package-private and callable only from
  `LegacyUploadMigrationTransaction`.
- `storeDocumentArtifact` serves `DOCUMENT_DELIVERY_ARTIFACT` and is fed by the same inspector.
- Image decoding happens in exactly two classes: `ImageUploadValidator` (the inspector's
  canonicalising decoder for direct raster uploads) and `BusinessCardImageValidator` (the
  business-card ingress, which decodes and re-encodes card scans to a canonical JPEG before
  `storeValidatedBusinessCardImage`). `UploadContentInspectionBoundaryArchTest` pins the
  `javax.imageio` reader and stream API to those two files.

## 3. `InspectedUpload` invariants

- Immutable: byte arrays are cloned in and out.
- Carries the SHA-256 of the exact returned bytes; `ManagedObjectService` re-verifies that digest
  with `MessageDigest.isEqual` immediately before writing.
- Constructed only inside `UploadContentInspector`.
- Rasters uploaded directly are **canonicalised**: decoded and re-encoded, so metadata and any
  appended payload are destroyed and the stored bytes differ from the upload.
- CSV is **rewritten** into a canonical, formula-neutralised form.
- PDF, ODF, OOXML, text, markdown, and JSON are **byte-identical** to the upload: they are proven,
  never rewritten, so a document's own digital signature keeps verifying.

## 4. Format allowlist per purpose

Source of truth: `UploadPolicy.FORMATS_BY_PURPOSE` (private, static, final — no runtime widening,
no per-deployment override).

| `UploadPurpose` | Allowed `UploadFormat` |
|---|---|
| `ATTACHMENT` | `JPEG`, `PNG`, `GIF`, `WEBP`, `PDF`, `DOCX`, `XLSX`, `PPTX`, `ODT`, `ODS`, `ODP`, `TEXT`, `CSV` |
| `INLINE_IMAGE` | `JPEG`, `PNG`, `GIF`, `WEBP` |
| `ASSISTANT_CONTEXT` | `JPEG`, `PNG`, `WEBP`, `TEXT`, `CSV`, `MARKDOWN`, `JSON` |
| `PROFILE_IMAGE` | `JPEG`, `PNG`, `WEBP` |
| `BUSINESS_CARD_IMAGE` | `JPEG`, `PNG`, `WEBP` |
| `CSV_IMPORT_SOURCE` | `CSV` |
| `DOCUMENT_DELIVERY_ARTIFACT` | `PDF`, `JSON` |

## 5. Package member policy

A document package is a ZIP. Its directory is walked manually first (single disk, STORED or
DEFLATED only, no ZIP64, unique names and offsets, contiguous local layout from offset 0, local
header agreeing with the central directory), then the members are streamed once and each member's
length and CRC32 must match the directory.

Every member is then assigned exactly one inspection class. **A member matching no class is
refused**, rather than stored uninspected.

| Class | Members | What is proven |
|---|---|---|
| `XML` | `*.xml`, `*.rels`, `*.vml`, `META-INF/manifest.xml`, `manifest.rdf` | Parsed with DTDs, external entities, and processing instructions disabled, against the namespace/element allowlists in §6 |
| `SIGNATURE` | `META-INF/documentsignatures.xml`, `_xmlsignatures/*.xml` | Parsed against the closed signature vocabulary (§6) |
| `SIGNATURE_ORIGIN` | `_xmlsignatures/origin.sigs` | Must be empty and bound by the origin relationship |
| `MIMETYPE` | ODF `mimetype` | STORED at offset 0 and equal to the format's media type |
| `RASTER` | `*.png`, `*.jpg`, `*.jpeg`, `*.gif`, `*.webp` | Walked by the same structural inspectors used for direct image uploads; bytes are not decoded or re-encoded |
| `SNIFFED_OPAQUE` | `*.emf`, `*.wmf`, `*.tif`, `*.tiff`, `*.bmp`; ODF `Fonts/*.ttf\|*.otf\|*.ttc` | Magic plus an internal length that agrees with the member length: the EMF header byte count, the WMF header word count (after the placeable header when present), the TIFF first-directory offset, and the BMP file size. ODF embedded fonts must carry an sfnt tag (`00 01 00 00`, `OTTO`, `true`, `ttcf`) whose table or font directory fits the member, and be declared `application/x-font-ttf`, `application/x-font-otf`, `font/ttf`, `font/otf`, or `application/vnd.ms-opentype` |
| `DECLARED_OPAQUE` | `(word\|xl\|ppt)/fonts/*.odttf\|*.fntdata`, `(word\|xl\|ppt)/printerSettings/printerSettingsN.bin`, ODF `layout-cache` | Declared type, size bound, and a negative header sniff that refuses executables, archives, compound files, documents, and markup |
| `DIRECTORY` | names ending `/` | Must be empty |
| `REFUSED` | everything else | Refused before any bytes are retained |

ODF embedded fonts are the ODF counterpart of the OOXML obfuscated-font decision: LibreOffice
writes them under `Fonts/` when a document is saved with "embed fonts", they were stored
uninspected before package-member inspection existed, and they are now admitted only through the
sfnt sniff and declared-type binding above, inside the 16 MiB font bound.

Refused by name include `vbaProject.bin` and every other `.bin` except printer settings,
`vbaProjectSignature*.bin`, `activeX/`, `embeddings/`, `oleObject*`, `customUI/`, `externalLinks/`,
`ddeLinks/`, `oleLinks/`, `queryTables/`, `connections.xml`, `Scripts/`, `Basic/`, `Dialogs/`,
`Object N/`, `ObjectReplacements/`, `[trash]/`, `afchunk*`, and the executable/markup/script
extension families (`.svg`, `.svm`, `.wdp`, `.hdp`, `.html`, `.htm`, `.xhtml`, `.mht`, `.mhtml`,
`.js`, `.exe`, `.dll`, `.com`, `.scr`, `.msi`, `.jar`, `.class`, `.ps1`, `.sh`, `.bat`, `.cmd`,
`.hta`, `.swf`, `.vbs`, `.wsf`, `.lnk`). Non-directory members under `Configurations2/` are
refused, and every `META-INF/` part other than `manifest.xml` and `documentsignatures.xml` is
refused, so `macrosignatures.xml` never gets an exemption.

### Declared-type binding

The package's own declared media types are bound to the members they describe, **after** the pass,
because a producer may write `[Content_Types].xml` or `META-INF/manifest.xml` last:

- **OOXML** — the `Override` whose `PartName` matches, otherwise the `Default` for the member
  extension (the OPC rule). A non-XML member with no declared type is refused.
- **ODF** — the `manifest:file-entry` for the member. ODF 1.3 Part 2 §3.2 requires exactly one
  entry for every member other than `mimetype` and `META-INF/`, so a member missing from the
  manifest and an entry naming an absent member are both refused.
- A `RASTER` or `SNIFFED_OPAQUE` member whose declared type disagrees with the type its bytes prove
  is refused. **A member declared `image/svg+xml` is refused whatever it is called.**
- Declared types that are active anywhere in the package are refused even with no matching member:
  `image/svg+xml`, `text/html`, `application/xhtml+xml`, the script types, `audio/*`, `video/*`,
  OLE/package/ActiveX/control-properties types, and anything containing `vba` or `macroEnabled`.

### Bounds

| Bound | Value |
|---|---|
| Archive entries | 512 |
| Expanded package | 64 MiB |
| Compression ratio | 100:1 (per entry and overall) |
| XML or signature part | 4 MiB |
| Raster, EMF/WMF/TIFF/BMP member | 16 MiB |
| Embedded font member | 16 MiB |
| Printer settings, ODF layout cache | 1 MiB |
| XML depth / attributes | 128 / 256 |
| Image metadata | 1 MiB |
| Wall clock | 5 s |

## 6. Active-content posture

- **PDF** — `JavaScript`, `JS`, `OpenAction`, `AA`, `Launch`, `SubmitForm`, `ImportData`, `Movie`,
  `Sound`, `Rendition`, `RichMedia`, `3D`, `XFA`, and embedded files are refused. Encrypted PDFs
  are refused.
- **Word fields** — an allowlist of inert display, numbering, metadata, merge, index, and
  cross-reference commands. `DDEAUTO`, `INCLUDETEXT`, `INCLUDEPICTURE`, `LINK`, `DATABASE`, `ASK`,
  `FILLIN`, `MACROBUTTON`, and `GOTOBUTTON` are refused, including when reconstructed across
  `w:instrText` runs after a field result.
- **Spreadsheet and chart formulas** — refused entirely, including `calculatedColumnFormula` and
  `definedName`/`refersTo`. The VML namespace is exempt, because `v:formulas`/`v:f` there are
  static shape geometry.
- **OOXML relationships** — a closed active-kind blocklist (`oleObject`, `package`, `vbaProject`,
  `activeXControl`, `attachedTemplate`, `control`, `ctrlProp`, `customUI`, `ddeLink`,
  `embeddedObject`, `embeddedPackage`, `externalLink`, `queryTable`, `audio`, `video`, `media`).
  `External` targets are allowed only for `hyperlink`, and only `http`, `https`, `mailto`, `tel`.
  Every internal target must resolve to a member that exists.
- **ODF** — an element-namespace allowlist plus closed `office:`/`anim:` element allowlists;
  `office:scripts` and `office:forms` must be empty; the DDE family and `automatic-update` are
  refused; `office:binary-data` is excluded, so inline base64 images cannot dodge inspection.
  `META-INF/manifest.xml` admits only `manifest` and `file-entry`, so `manifest:encryption-data` is
  refused by absence and an encrypted ODF fails closed. `manifest.rdf` has its own closed RDF/ODF
  metadata vocabulary, and every `rdf:about` / `rdf:resource` value must be empty, an ODF metadata
  vocabulary URI, or a member of the package; external URIs and absent members refuse.
- **PresentationML actions** — the `action` attribute of `a:hlinkClick`, `a:hlinkHover`, and
  `a:hlinkMouseOver` must be one of `ppaction://noaction`, `ppaction://media`,
  `ppaction://hlinksldjump`, `ppaction://hlinkshowjump?jump=…`, or
  `ppaction://customshow?id=N[&return=true]`. `program`, `macro`, `ole`, `hlinkfile`, and
  `hlinkpres` are refused (enumeration from MS-OI29500 §21.1.2.3.5).
- **Signatures** — an ODF `META-INF/documentsignatures.xml` and OOXML `_xmlsignatures/*.xml` are
  admitted through closed XMLDSig, OPC digital-signature, Microsoft Office signature-information,
  and XAdES 1.3.2/1.4.1 element vocabularies. OOXML signature parts must additionally be declared
  with the OPC signature content type, be targeted from `_xmlsignatures/_rels/origin.sigs.rels`,
  and have an empty `_xmlsignatures/origin.sigs` reached by the root `digital-signature/origin`
  relationship. Signature `Reference` and `RetrievalMethod` URIs may only address a fragment or
  `/part?ContentType=…` where the part exists and its declared type is not macro, OLE, ActiveX,
  control, or package content; URL-bearing signature text (XAdES `SPURI`, Office
  `SignatureProviderUrl`) must meet the same rule or be an ordinary `http`, `https`, `mailto`, or
  `tel` hyperlink. `<Object>` remains refused everywhere outside a signature part, and macro
  signatures stay refused. **A signed macro is still a macro.**

## 7. What #1122 and every later pipeline MUST do

**MUST**

1. Call `UploadContentInspector.inspect(purpose, source)` on the exact request bytes, with a
   purpose chosen by the server, before anything else touches them.
2. Derive stored bytes, length, digest, content type, extension, and file name **only** from the
   returned `InspectedUpload`, never from the original source or the client's headers.
3. Pass the artifact through `UploadMalwareScanner.scan(...)` and store only the resulting
   `ScannedUpload` through `ManagedObjectService`.
4. Serve stored objects through the existing attachment read boundary, with the stored content
   type and a download disposition.

**MUST NOT**

1. Add a raw writer (a `storeAttachment*`-shaped method) or a second `objectStorage.put(...)`
   call site.
2. Construct `InspectedUpload` or `ScannedUpload` outside the storage package.
3. Widen `UploadPolicy` per deployment, per tenant, or per feature flag.
4. Inspect asynchronously after the bytes are already readable, or mark a record ready before
   inspection and scanning have both succeeded.
5. Serve an individual package member, or unpack a package server-side for preview.
6. Trust a provider-supplied media type for a provider-backed file reference; fetched bytes are
   untrusted uploads and go through the same gate.

## 8. Known residuals

These are deliberate, and each is a decision that can be revisited with a test rather than an
accident:

- Office documents containing **SVG icons** (Office 2016+ writes `image/svg+xml` alongside a PNG
  fallback) are refused. SVG is scriptable and there is no in-repository SVG sanitiser.
- ODF documents with **embedded charts or OLE objects** (`Object N/`) are refused.
- OOXML **certificate parts** (`_xmlsignatures/*.cer`) are refused, so a package that stores the
  signer certificate outside `KeyInfo` is refused.
- **Audio and video** package members are refused.
- A **JPEG member with bytes after `FFD9`**, more than 1 MiB of APPn/ICC metadata, or a PNG with
  an unknown critical chunk refuses the whole document.
- EMF, WMF, TIFF, BMP, embedded fonts, and printer settings are accepted as bounded opaque bytes:
  their internal structure is not parsed, so a client-side parser exploit inside one of them is
  not detectable here. ClamAV still scans every stored byte.
- An embedded font member larger than 16 MiB refuses the document. LibreOffice embeds CJK fonts
  whole, and a single CJK TrueType collection can exceed that bound, so a Japanese document saved
  with "embed fonts" may be refused; raise `MAX_OPAQUE_FONT_BYTES` with a test if that proves
  common.
- Signing tools whose XAdES or Office signature markup falls outside the enumerated schemas are
  refused by absence. That is the intended fail-closed posture; widen the vocabulary from the
  published schema, never by relaxing the allowlist.
- ODF `META-INF/documentsignatures.xml` references (`Reference`, `RetrievalMethod`) are admitted
  by vocabulary only; their URIs are not bound to package members the way OOXML signature
  references are. Pre-existing, unchanged here.
- LibreOffice-saved `.xlsx` workbooks are refused because Calc writes `showFormulas="false"` on
  `sheetView` and the attribute rule refuses any attribute name containing `formula`.
  Pre-existing and independent of package-member inspection; a follow-up outside this change.

[#1122]: https://github.com/itkla/connex/issues/1122
