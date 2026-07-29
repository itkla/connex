package ooo.klae.connex.backend.tenant;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Routed connection capable of temporarily entering the physical control
 * catalog while retaining the same transaction-bound database session.
 */
public interface ControlCatalogConnection extends Connection {

    /**
     * Switches to the configured control catalog.
     *
     * @return the catalog that must be restored after the control-plane statement
     * @throws SQLException when the current catalog cannot be read or switched
     */
    String enterControlCatalog() throws SQLException;

    /**
     * Restores the catalog returned by {@link #enterControlCatalog()}.
     *
     * @param catalog catalog active before the control-plane statement
     * @throws SQLException when the catalog cannot be restored
     */
    void restoreCatalog(String catalog) throws SQLException;
}
