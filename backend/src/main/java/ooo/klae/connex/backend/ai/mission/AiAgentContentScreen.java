package ooo.klae.connex.backend.ai.mission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.ai.masking.AiGeneratedContentScreen;
import ooo.klae.connex.backend.ai.masking.Demasker;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.DurableText;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.MemberProvenance;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.WorkspaceService;

/** Screens all continuation prose before any durable argument or result is written. */
@Service
@RequiredArgsConstructor
public class AiAgentContentScreen {
    private static final Pattern HANDLE = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])[rt][1-9][0-9]*(?![\\p{L}\\p{N}_])");
    private static final Pattern BARE_PLACEHOLDER = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])[PCDEHM][1-9][0-9]*(?![\\p{L}\\p{N}_])");
    private static final Pattern CANONICAL_REFERENCE = Pattern.compile(
            "(?:person|company|deal|user|member|task)\\s*:\\s*[1-9][0-9]*"
                    + "|(?:user|member)[ _-]?id\\s*(?::|=|#)\\s*[1-9][0-9]*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTROL = Pattern.compile(
            "ignore\\s+(?:all\\s+)?(?:previous|prior|above)\\s+instructions?"
                    + "|system\\s+prompt|developer\\s+(?:message|instructions?)"
                    + "|tool\\s+(?:call|command)|crm_data|model_output|step\\s+schema",
            Pattern.CASE_INSENSITIVE);
    private final WorkspaceService workspaceService;

    /** Demasks only exposed bindings and resolves bounded member provenance by current authority. */
    public DurableText screen(String raw, MaskingContext context, AiAgentExecutionPolicy policy) {
        if (raw == null || AiGeneratedContentScreen.rejectionReason(raw) != null
                || AiGeneratedContentScreen.containsBarePlaceholder(raw)) {
            throw refused();
        }
        Demasker.DemaskResult demasked = Demasker.demask(raw, context);
        String text = demasked.text();
        String canonical = java.text.Normalizer.normalize(
                ooo.klae.connex.backend.ai.masking.MaskingEngine.prepareConversationalText(text),
                java.text.Normalizer.Form.NFKC);
        if (demasked.warnings() != 0 || AiGeneratedContentScreen.containsPlaceholder(text)
                || BARE_PLACEHOLDER.matcher(text).find()
                || AiGeneratedContentScreen.rejectionReason(text) != null
                || AiGeneratedContentScreen.rejectionReason(canonical) != null
                || BARE_PLACEHOLDER.matcher(canonical).find()
                || HANDLE.matcher(canonical).find() || CONTROL.matcher(canonical).find()
                || CANONICAL_REFERENCE.matcher(text).find()
                || CANONICAL_REFERENCE.matcher(canonical).find()
                || text.codePoints().anyMatch(value -> Character.isISOControl(value)
                        && value != '\n' && value != '\r' && value != '\t')) {
            throw refused();
        }
        Map<String, Integer> members = new LinkedHashMap<>();
        List<User> current = workspaceService.getMembers(policy.turn().workspaceId());
        for (User member : current) {
            if (member == null || member.getId() <= 0) {
                continue;
            }
            String label = member.getDisplayName();
            if (label == null || label.isBlank()) {
                label = member.getUsername();
            }
            register(members, label, member.getId());
        }
        var snapshot = policy.persistence().snapshot(policy);
        for (DurableText stored : texts(snapshot)) {
            for (MemberProvenance provenance : stored.memberProvenance()) {
                if (current.stream().anyMatch(member -> member != null
                        && provenance.userId() == member.getId())) {
                    register(members, provenance.originalLabel(), provenance.userId());
                }
            }
        }
        List<MemberProvenance> provenance = new ArrayList<>();
        for (String label : context.memberCandidatesIn(text)) {
            Integer id = members.get(label);
            if (id == null || id <= 0 || label.length() > 200
                    || AiGeneratedContentScreen.rejectionReason(label) != null
                    || AiGeneratedContentScreen.containsPlaceholder(label)
                    || HANDLE.matcher(label).find() || CONTROL.matcher(label).find()) {
                throw refused();
            }
            provenance.add(new MemberProvenance(id, label));
        }
        if (provenance.size() > 20) {
            throw refused();
        }
        return new DurableText(text, List.copyOf(provenance));
    }

    /** Returns every persisted prose holding whose provenance must be freshly authorized. */
    public static List<DurableText> texts(AiAgentContinuationCodec.Snapshot snapshot) {
        List<DurableText> texts = new ArrayList<>();
        snapshot.plan().items().forEach(item -> texts.add(item.label()));
        snapshot.checkpoint().completedFacts().forEach(fact -> texts.add(fact.summary()));
        texts.add(snapshot.memory().summary());
        return List.copyOf(texts);
    }

    private static void register(Map<String, Integer> labels, String label, int userId) {
        if (label == null || label.isBlank()) {
            return;
        }
        Integer previous = labels.putIfAbsent(label, userId);
        if (previous != null && previous != userId) {
            labels.put(label, -1);
        }
    }

    private static AiAssistantLoopException refused() {
        return AiAssistantLoopException.refusedArguments("mission_content_refused");
    }
}
