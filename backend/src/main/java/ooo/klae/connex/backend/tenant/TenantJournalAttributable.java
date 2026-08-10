package ooo.klae.connex.backend.tenant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an HTTP handler whose resolved active organization is authoritative for a metadata-only
 * support-journal completion record.
 *
 * <p>Handlers with explicit organization or workspace targets must not use this marker unless
 * they independently prove that target is the resolved active tenant. An omitted record is safer
 * than attributing a cross-organization operation to the wrong tenant.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantJournalAttributable {
}
