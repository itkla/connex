package ooo.klae.connex.backend.ai.brief;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DealBriefSourceRegistry {
    private final Map<String, DealBriefSource> sources = new LinkedHashMap<>();
    private final Map<DealBriefSource, String> positionalIds = new LinkedHashMap<>();
    private final Map<String, Integer> nextIndexByKind = new LinkedHashMap<>();
    private final Set<Integer> contributorPersonIds = new LinkedHashSet<>();

    String register(String kind, int realId) {
        if (realId <= 0) {
            return "";
        }
        DealBriefSource source = new DealBriefSource(kind, realId);
        String existing = positionalIds.get(source);
        if (existing != null) {
            return existing;
        }
        int nextIndex = nextIndexByKind.getOrDefault(kind, 0);
        String positionalId = kind + '.' + nextIndex;
        nextIndexByKind.put(kind, nextIndex + 1);
        positionalIds.put(source, positionalId);
        sources.put(positionalId, source);
        if ("person".equals(kind)) {
            contributorPersonIds.add(realId);
        }
        return positionalId;
    }

    void contributePerson(int personId) {
        if (personId > 0) {
            contributorPersonIds.add(personId);
        }
    }

    Map<String, DealBriefSource> snapshot() {
        return Map.copyOf(sources);
    }

    List<Integer> contributorPersonIds() {
        List<Integer> sorted = new ArrayList<>(contributorPersonIds);
        sorted.sort(Integer::compareTo);
        return List.copyOf(sorted);
    }
}
