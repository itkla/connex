package ooo.klae.connex.backend.ai.mission;

import java.util.List;
import java.util.Objects;

import ooo.klae.connex.backend.ai.mission.AiAgentMemory.DurableText;

/** Immutable lifetime item inventory; display order never reallocates an item identity. */
public record AiAgentPlan(int schemaVersion, String revision, int nextItemOrdinal,
        List<Item> items, List<String> displayOrder) {
    public AiAgentPlan {
        Objects.requireNonNull(revision);
        items = List.copyOf(items);
        displayOrder = List.copyOf(displayOrder);
    }

    /** One server-keyed item whose screened label is independent of its completion fact. */
    public record Item(String key, DurableText label, String status) {
        public Item {
            Objects.requireNonNull(key);
            Objects.requireNonNull(label);
            Objects.requireNonNull(status);
        }
    }
}
