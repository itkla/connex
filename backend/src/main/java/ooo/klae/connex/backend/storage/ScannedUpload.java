package ooo.klae.connex.backend.storage;

import java.util.Objects;

import ooo.klae.connex.backend.storage.UploadContentInspector.InspectedUpload;
import ooo.klae.connex.backend.storage.malware.MalwareScanReport;

/**
 * Proof that an inspected upload received an explicit clean malware-scan verdict.
 * Possession of this type is the storage boundary's authority to persist the wrapped bytes.
 */
public final class ScannedUpload {
    private final InspectedUpload upload;
    private final MalwareScanReport report;

    ScannedUpload(InspectedUpload upload, MalwareScanReport report) {
        this.upload = Objects.requireNonNull(upload, "upload");
        this.report = Objects.requireNonNull(report, "report");
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
