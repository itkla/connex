package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.tenant.TablePlaneRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.Cascade;

class RelationshipSignalRegistryArchTest {

    @Test
    void allTablesAreClassifiedForPlaneAndLifecycleWithActorStateCascading() {
        assertTrue(TablePlaneRegistry.ORG_DATA_TABLES.containsAll(List.of(
            "relationship_signal",
            "relationship_signal_state",
            "relationship_signal_family_state")));
        assertTrue(TenantLifecycleRegistry.require("relationship_signal").direct());
        assertTrue(TenantLifecycleRegistry.require("relationship_signal_family_state").direct());
        Cascade state = assertInstanceOf(Cascade.class, TenantLifecycleRegistry
            .require("relationship_signal_state").reach());
        assertEquals("relationship_signal", state.parentTable());
        assertEquals("fk_relationship_signal_state_signal", state.constraintName());
        assertEquals(2, state.columns().size());
    }
}
