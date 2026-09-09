package ooo.klae.connex.backend.services;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowStepRun;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.WorkflowTextPart;
import ooo.klae.connex.backend.dto.WorkflowTextTemplate;
import ooo.klae.connex.backend.dto.WorkflowValueRef;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;

/** Resolves schema-v2 action bindings from frozen launch, locked record, and durable step state. */
@Service
@RequiredArgsConstructor
public class WorkflowActionBindingService {

    private static final int TITLE_LIMIT = 255;
    private static final int BODY_LIMIT = 2000;

    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final ObjectMapper objectMapper;

    /** Discovers the member targeted by an action before any workflow-run row is locked. */
    public Discovery discover(WorkflowRun run, RuleAction action) {
        return discover(run, action, null);
    }

    /** Discovers the member targeted by an action before any workflow-run row is locked. */
    public Discovery discover(
            WorkflowRun run,
            RuleAction action,
            WorkflowDefinitionValidator.CompiledWorkflow compiled) {
        if (action.getTargetUserRef() == null) {
            return new Discovery(action.getTargetUserId());
        }
        WorkflowValueRef ref = action.getTargetUserRef();
        JsonNode value = switch (normalized(ref.source())) {
            case "launch_input" -> launchInput(run, ref.key(), compiled);
            case "record_field" -> unlockedRecordField(run, ref.field());
            default -> null;
        };
        return new Discovery(requirePositiveInteger(value, "target user"));
    }

    /** Resolves only the current target-member binding for side-effect-free simulation. */
    public RuleAction resolvePreviewTarget(
            int workspaceId,
            String recordType,
            int recordId,
            Map<String, JsonNode> launchInputs,
            RuleAction action) {
        if (action.getTargetUserRef() == null) {
            return action;
        }
        WorkflowValueRef ref = action.getTargetUserRef();
        JsonNode value;
        if ("launch_input".equals(normalized(ref.source()))) {
            value = launchInputs.get(ref.key());
        } else if ("record_field".equals(normalized(ref.source()))) {
            WorkflowRun preview = new WorkflowRun();
            preview.setWorkspaceId(workspaceId);
            preview.setRecordType(recordType);
            preview.setRecordId(recordId);
            value = unlockedRecordField(preview, ref.field());
        } else {
            throw unresolved("The workflow target member is unavailable in simulation.");
        }
        RuleAction resolved = copy(action);
        resolved.setTargetUserId(requirePositiveInteger(value, "target user"));
        resolved.setTargetUserRef(null);
        return resolved;
    }

    /** Renders one bounded record-specific action disclosure without evaluating future outputs. */
    public Preview preview(
            int workspaceId,
            String recordType,
            int recordId,
            Map<String, JsonNode> launchInputs,
            RuleAction action) {
        RuleAction targetResolved = resolvePreviewTarget(
            workspaceId, recordType, recordId, launchInputs, action);
        WorkflowRun previewRun = new WorkflowRun();
        previewRun.setWorkspaceId(workspaceId);
        previewRun.setRecordType(recordType);
        previewRun.setRecordId(recordId);
        String title = action.getTitleTemplate() == null
            ? action.getTitle()
            : renderPreview(
                previewRun, launchInputs, action.getTitleTemplate(), TITLE_LIMIT, "title");
        String body = action.getBodyTemplate() == null
            ? action.getBody()
            : renderPreview(
                previewRun, launchInputs, action.getBodyTemplate(), BODY_LIMIT, "body");
        String dueDate = null;
        if (action.getDueDateRef() != null) {
            dueDate = requireDate(
                previewValue(previewRun, launchInputs, action.getDueDateRef()), "due date");
        } else if (action.getDueInDays() != null) {
            dueDate = LocalDate.now().plusDays(action.getDueInDays()).toString();
        }
        requireLength(title, TITLE_LIMIT, "title");
        requireLength(body, BODY_LIMIT, "body");
        return new Preview(title, body, targetResolved.getTargetUserId(), dueDate);
    }

    /** Resolves one action from values protected by the run and primary-record lock sequence. */
    public RuleAction resolveLocked(
            WorkflowRun run,
            RuleAction action,
            Discovery discovery,
            WorkspaceService.LockedPermissionSnapshot authorization,
            WorkflowDefinitionValidator.CompiledWorkflow compiled) {
        LockedRecord record = lockRecord(run, usesRelatedCompany(action));
        RuleAction resolved = copy(action);
        if (action.getTargetUserRef() != null) {
            int target = requirePositiveInteger(
                resolve(run, record, action.getTargetUserRef(), compiled), "target user");
            if (!Objects.equals(discovery.targetUserId(), target)) {
                throw unresolved("The workflow target member changed before execution.");
            }
            resolved.setTargetUserId(target);
            resolved.setTargetUserRef(null);
        }
        if (resolved.getTargetUserId() != null) {
            authorization.requireMember(resolved.getTargetUserId());
        }
        if (action.getDueDateRef() != null) {
            resolved.setResolvedDueDate(requireDate(
                resolve(run, record, action.getDueDateRef(), compiled), "due date"));
            resolved.setDueDateRef(null);
        }
        if (action.getValueRef() != null) {
            JsonNode value = resolve(run, record, action.getValueRef(), compiled);
            if (value == null || value.isNull()) {
                throw unresolved("The workflow field value is unavailable.");
            }
            resolved.setValue(value);
            resolved.setValueRef(null);
        }
        if (action.getTitleTemplate() != null) {
            resolved.setTitle(render(
                run, record, action.getTitleTemplate(), TITLE_LIMIT, "title", compiled));
            resolved.setTitleTemplate(null);
        }
        if (action.getBodyTemplate() != null) {
            resolved.setBody(render(
                run, record, action.getBodyTemplate(), BODY_LIMIT, "body", compiled));
            resolved.setBodyTemplate(null);
        }
        requireLength(resolved.getTitle(), TITLE_LIMIT, "title");
        requireLength(resolved.getBody(), BODY_LIMIT, "body");
        return resolved;
    }

    private LockedRecord lockRecord(WorkflowRun run, boolean includeRelatedCompany) {
        return switch (run.getRecordType()) {
            case "person" -> {
                Person person = personMapper.getVisiblePersonByIdForUpdate(
                    run.getWorkspaceId(), run.getRecordId());
                if (person == null) throw recordUnavailable();
                Company related = includeRelatedCompany && person.getCompany() != null
                    ? companyMapper.getVisibleCompanyByIdForUpdate(
                        run.getWorkspaceId(), person.getCompany().getId())
                    : null;
                if (includeRelatedCompany && person.getCompany() != null && related == null) {
                    throw unresolved("The workflow record relationship is unavailable.");
                }
                yield new LockedRecord(person, null, null, related);
            }
            case "company" -> {
                Company company = companyMapper.getVisibleCompanyByIdForUpdate(
                    run.getWorkspaceId(), run.getRecordId());
                if (company == null) throw recordUnavailable();
                yield new LockedRecord(null, company, null, null);
            }
            case "deal" -> {
                Deal deal = dealMapper.getDealByIdForUpdate(
                    run.getWorkspaceId(), run.getRecordId());
                if (deal == null) throw recordUnavailable();
                Company related = includeRelatedCompany && deal.getCompanyId() != null
                    ? companyMapper.getVisibleCompanyByIdForUpdate(
                        run.getWorkspaceId(), deal.getCompanyId())
                    : null;
                if (includeRelatedCompany && deal.getCompanyId() != null && related == null) {
                    throw unresolved("The workflow record relationship is unavailable.");
                }
                yield new LockedRecord(null, null, deal, related);
            }
            default -> throw recordUnavailable();
        };
    }

    private JsonNode resolve(
            WorkflowRun run,
            LockedRecord record,
            WorkflowValueRef ref,
            WorkflowDefinitionValidator.CompiledWorkflow compiled) {
        return switch (normalized(ref.source())) {
            case "launch_input" -> launchInput(run, ref.key(), compiled);
            case "record_field" -> lockedRecordField(record, ref.field());
            case "step_output" -> stepOutput(run, ref.nodeId(), ref.output());
            default -> null;
        };
    }

    private JsonNode launchInput(
            WorkflowRun run,
            String key,
            WorkflowDefinitionValidator.CompiledWorkflow compiled) {
        if (key == null) {
            return null;
        }
        try {
            JsonNode values = run.getLaunchInputsJson() == null
                ? null : objectMapper.readTree(run.getLaunchInputsJson());
            JsonNode frozen = values != null && values.isObject() ? values.get(key) : null;
            if (frozen != null || "manual".equals(run.getTriggerType())) {
                return frozen;
            }
            return compiled == null ? null : compiled.inputs().stream()
                .filter(input -> key.equals(input.key()))
                .map(ooo.klae.connex.backend.dto.WorkflowInputDefinition::defaultValue)
                .findFirst()
                .orElse(null);
        } catch (Exception exception) {
            throw unresolved("The workflow launch inputs are unavailable.");
        }
    }

    private JsonNode stepOutput(WorkflowRun run, String nodeId, String output) {
        WorkflowStepRun source = workflowRunMapper.getStepByNodeForUpdate(
            run.getWorkspaceId(), run.getId(), nodeId);
        if (source == null || !"succeeded".equals(source.getStatus())
                || source.getActionOutputsJson() == null) {
            return null;
        }
        try {
            JsonNode outputs = objectMapper.readTree(source.getActionOutputsJson());
            return outputs != null && outputs.isObject() ? outputs.get(output) : null;
        } catch (Exception exception) {
            throw unresolved("The workflow step output is unavailable.");
        }
    }

    private JsonNode unlockedRecordField(WorkflowRun run, String field) {
        Object record = switch (run.getRecordType()) {
            case "person" -> personMapper.getPersonById(run.getWorkspaceId(), run.getRecordId());
            case "company" -> companyMapper.getCompanyById(run.getWorkspaceId(), run.getRecordId());
            case "deal" -> dealMapper.getDealById(run.getWorkspaceId(), run.getRecordId());
            default -> null;
        };
        if (record == null) {
            throw recordUnavailable();
        }
        Company related = null;
        if ("companyName".equals(field) && record instanceof Person person
                && person.getCompany() != null) {
            related = companyMapper.getCompanyById(
                run.getWorkspaceId(), person.getCompany().getId());
        } else if ("companyName".equals(field) && record instanceof Deal deal
                && deal.getCompanyId() != null) {
            related = companyMapper.getCompanyById(run.getWorkspaceId(), deal.getCompanyId());
        }
        Company relatedCompany = related;
        return switch (record) {
            case Person person -> personField(person, relatedCompany, field);
            case Company company -> companyField(company, field);
            case Deal deal -> dealField(deal, relatedCompany, field);
            default -> null;
        };
    }

    private JsonNode lockedRecordField(LockedRecord record, String field) {
        if (record.person() != null) {
            return personField(record.person(), record.relatedCompany(), field);
        }
        if (record.company() != null) {
            return companyField(record.company(), field);
        }
        return dealField(record.deal(), record.relatedCompany(), field);
    }

    private JsonNode personField(Person person, Company related, String field) {
        return value(switch (field == null ? "" : field) {
            case "id" -> person.getId();
            case "name" -> person.getName();
            case "ownerId" -> person.getOwnerId();
            case "email" -> person.getEmail();
            case "title" -> person.getTitle();
            case "companyId" -> person.getCompany() == null ? null : person.getCompany().getId();
            case "companyName" -> related == null ? null : related.getName();
            default -> null;
        });
    }

    private JsonNode companyField(Company company, String field) {
        return value(switch (field == null ? "" : field) {
            case "id" -> company.getId();
            case "name" -> company.getName();
            case "ownerId" -> company.getOwnerId();
            case "industry" -> company.getIndustry();
            default -> null;
        });
    }

    private JsonNode dealField(Deal deal, Company related, String field) {
        return value(switch (field == null ? "" : field) {
            case "id" -> deal.getId();
            case "name" -> deal.getName();
            case "ownerId" -> deal.getOwnerId();
            case "status" -> deal.getWon() == null ? "open" : deal.getWon() ? "won" : "lost";
            case "expectedCloseDate" -> deal.getExpectedCloseDate();
            case "companyId" -> deal.getCompanyId();
            case "companyName" -> related == null ? null : related.getName();
            default -> null;
        });
    }

    private JsonNode value(Object value) {
        return value == null ? null : objectMapper.valueToTree(value);
    }

    private String render(
            WorkflowRun run,
            LockedRecord record,
            WorkflowTextTemplate template,
            int limit,
            String fieldName,
            WorkflowDefinitionValidator.CompiledWorkflow compiled) {
        List<String> rendered = new ArrayList<>();
        int length = 0;
        for (WorkflowTextPart part : template.parts()) {
            String value;
            if (part.text() != null) {
                value = part.text();
            } else {
                JsonNode bound = resolve(run, record, part.ref(), compiled);
                if (bound == null || bound.isNull()) {
                    if ("empty".equals(template.missingValue())) {
                        value = "";
                    } else {
                        throw unresolved("The workflow " + fieldName + " binding is unavailable.");
                    }
                } else if (bound.isValueNode()) {
                    value = bound.isTextual() ? bound.textValue() : bound.asString();
                } else {
                    throw unresolved("The workflow " + fieldName + " binding is invalid.");
                }
            }
            length += value.length();
            if (length > limit) {
                throw unresolved("The rendered workflow " + fieldName + " is too long.");
            }
            rendered.add(value);
        }
        return String.join("", rendered);
    }

    private String renderPreview(
            WorkflowRun run,
            Map<String, JsonNode> launchInputs,
            WorkflowTextTemplate template,
            int limit,
            String fieldName) {
        if (template.parts().stream()
                .map(WorkflowTextPart::ref)
                .filter(Objects::nonNull)
                .anyMatch(ref -> "step_output".equals(normalized(ref.source())))) {
            return null;
        }
        List<String> rendered = new ArrayList<>();
        int length = 0;
        for (WorkflowTextPart part : template.parts()) {
            String value;
            if (part.text() != null) {
                value = part.text();
            } else {
                JsonNode bound = previewValue(run, launchInputs, part.ref());
                if (bound == null || bound.isNull()) {
                    if ("empty".equals(template.missingValue())) {
                        value = "";
                    } else {
                        throw unresolved(
                            "The workflow " + fieldName + " binding is unavailable.");
                    }
                } else if (bound.isValueNode()) {
                    value = bound.isTextual() ? bound.textValue() : bound.asString();
                } else {
                    throw unresolved("The workflow " + fieldName + " binding is invalid.");
                }
            }
            length += value.length();
            if (length > limit) {
                throw unresolved("The rendered workflow " + fieldName + " is too long.");
            }
            rendered.add(value);
        }
        return String.join("", rendered);
    }

    private JsonNode previewValue(
            WorkflowRun run,
            Map<String, JsonNode> launchInputs,
            WorkflowValueRef ref) {
        return switch (normalized(ref.source())) {
            case "launch_input" -> launchInputs.get(ref.key());
            case "record_field" -> unlockedRecordField(run, ref.field());
            case "step_output" -> null;
            default -> null;
        };
    }

    private static boolean usesRelatedCompany(RuleAction action) {
        List<WorkflowValueRef> refs = new ArrayList<>();
        refs.add(action.getTargetUserRef());
        refs.add(action.getDueDateRef());
        refs.add(action.getValueRef());
        addTemplateRefs(refs, action.getTitleTemplate());
        addTemplateRefs(refs, action.getBodyTemplate());
        return refs.stream().filter(Objects::nonNull)
            .anyMatch(ref -> "record_field".equals(normalized(ref.source()))
                && "companyName".equals(ref.field()));
    }

    private static void addTemplateRefs(
            List<WorkflowValueRef> refs, WorkflowTextTemplate template) {
        if (template != null && template.parts() != null) {
            template.parts().stream().map(WorkflowTextPart::ref)
                .filter(Objects::nonNull).forEach(refs::add);
        }
    }

    private static int requirePositiveInteger(JsonNode value, String field) {
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToInt() || value.intValue() < 1) {
            throw unresolved("The workflow " + field + " binding is unavailable.");
        }
        return value.intValue();
    }

    private static String requireDate(JsonNode value, String field) {
        if (value == null || !value.isTextual()
                || !value.textValue().matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw unresolved("The workflow " + field + " binding is unavailable.");
        }
        try {
            LocalDate parsed = LocalDate.parse(value.textValue());
            if (!parsed.toString().equals(value.textValue())) throw new DateTimeException("date");
            return value.textValue();
        } catch (DateTimeException exception) {
            throw unresolved("The workflow " + field + " binding is invalid.");
        }
    }

    private static void requireLength(String value, int maximum, String field) {
        if (value != null && value.length() > maximum) {
            throw unresolved("The rendered workflow " + field + " is too long.");
        }
    }

    private static RuleAction copy(RuleAction action) {
        RuleAction copy = new RuleAction();
        copy.setType(action.getType());
        copy.setTitle(action.getTitle());
        copy.setBody(action.getBody());
        copy.setActivityType(action.getActivityType());
        copy.setTagId(action.getTagId());
        copy.setDueInDays(action.getDueInDays());
        copy.setDueInHours(action.getDueInHours());
        copy.setSeverity(action.getSeverity());
        copy.setTargetUserId(action.getTargetUserId());
        copy.setTargetStageId(action.getTargetStageId());
        copy.setCampaignMessageId(action.getCampaignMessageId());
        copy.setCampaignMessageVersion(action.getCampaignMessageVersion());
        copy.setTargetUserRef(action.getTargetUserRef());
        copy.setDueDateRef(action.getDueDateRef());
        copy.setTitleTemplate(action.getTitleTemplate());
        copy.setBodyTemplate(action.getBodyTemplate());
        copy.setField(action.getField());
        copy.setValue(action.getValue());
        copy.setValueRef(action.getValueRef());
        return copy;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static WorkflowExecutionException unresolved(String message) {
        return new WorkflowExecutionException("binding_unresolved", message, true);
    }

    private static WorkflowExecutionException recordUnavailable() {
        return new WorkflowExecutionException(
            "record_unavailable",
            "The workflow record is no longer available for automation.",
            true);
    }

    /** Unlocked member-discovery result that must match the later locked record reread. */
    public record Discovery(Integer targetUserId) { }

    /** Safe record-specific values disclosed by preparation. */
    public record Preview(
        String title,
        String body,
        Integer targetUserId,
        String dueDate
    ) { }

    private record LockedRecord(
        Person person,
        Company company,
        Deal deal,
        Company relatedCompany
    ) { }
}
