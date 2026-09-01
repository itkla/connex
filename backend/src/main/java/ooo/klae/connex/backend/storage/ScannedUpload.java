package ooo.klae.connex.backend.storage;

import java.util.Objects;

import ooo.klae.connex.backend.storage.UploadContentInspector.InspectedUpload;
import ooo.klae.connex.backend.storage.malware.MalwareScanReport;
import ooo.klae.connex.backend.storage.malware.MalwareScanVerdict;

/**
 * Proof that an inspected upload received an explicit clean malware-scan verdict.
 * Possession of this type is the storage boundary's authority to persist the wrapped bytes.
 *
 * <p>The canonical constructor is package-private so only {@code ooo.klae.connex.backend.storage}
 * can produce one, and it rejects any non-clean report outright. Both halves are required: package
 * scoping alone would let a same-package helper pair arbitrary bytes with an infected report, so
 * the verdict check is what makes the carrier load-bearing rather than merely conventional.
 */
public final class ScannedUpload {
    private final InspectedUpload upload;
    private final MalwareScanReport report;

    ScannedUpload(InspectedUpload upload, MalwareScanReport report) {
        this.upload = Objects.requireNonNull(upload, "upload");
        this.report = Objects.requireNonNull(report, "report");
        if (report.verdict() != MalwareScanVerdict.CLEAN) {
            throw new IllegalArgumentException(
                    "Storage authority requires a clean malware-scan verdict");
        }
    }

    public InspectedUpload upload() {
        return upload;
    }

    public MalwareScanReport report() {
        return report;
    }

    public String fileName() {
        return upload.fileName();
    }

    public String contentType() {
        return upload.contentType();
    }

    public byte[] sha256() {
        return upload.sha256();
    }

    public long contentLength() {
        return upload.contentLength();
    }
}
