package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonLeadSource;
import ooo.klae.connex.backend.dto.recordcreation.GuidedCompanyCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedPersonCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateUseDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationTemplateDto;
import ooo.klae.connex.backend.exceptions.RecordCreationTemplateException;
import ooo.klae.connex.backend.recordcreation.RecordCreationAugmentation;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

@Service
@RequiredArgsConstructor
public class GuidedRecordCreationService {
    private final PersonService personService;
    private final CompanyService companyService;
    private final DealService dealService;
    private final RecordCreationAugmentationService augmentationService;
    private final WorkspaceService workspaceService;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.PERSON_CREATE)
    public Person createPerson(GuidedPersonCreateRequestDto request) {
        lockPermission(Permission.PERSON_CREATE);
        ResolvedCreationTemplateDto resolved = augmentationService.resolvePreliminary(
            RecordCreationRecordType.person, request.templateUse());
        Map<Integer, JsonNode> customFields = submittedCustomFields(request.customFields());
        List<Integer> tagIds = submittedTagIds(request.tagIds());
        var record = request.record();
        String duplicateReviewToken = record.duplicateReviewToken();
        Person person = record.toBean();
        applyPersonDefaults(person, customFields, tagIds, resolved);
        validateRequiredPerson(person, customFields, tagIds, resolved);
        augmentationService.validatePersonReferences(
            person.getCompany() == null ? null : person.getCompany().getId(),
            person.getReferrerPersonId());
        return personService.createReviewed(
            person,
            duplicateReviewToken,
            augmentation(request.templateUse(), customFields, tagIds));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.COMPANY_CREATE)
    public Company createCompany(GuidedCompanyCreateRequestDto request) {
        lockPermission(Permission.COMPANY_CREATE);
        ResolvedCreationTemplateDto resolved = augmentationService.resolvePreliminary(
            RecordCreationRecordType.company, request.templateUse());
        Map<Integer, JsonNode> customFields = submittedCustomFields(request.customFields());
        List<Integer> tagIds = submittedTagIds(request.tagIds());
        var record = request.record();
        String duplicateReviewToken = record.duplicateReviewToken();
        Company company = record.toBean();
        applyCompanyDefaults(company, customFields, tagIds, resolved);
        validateRequiredCompany(company, customFields, tagIds, resolved);
        return companyService.createCompanyReviewed(
            company,
            duplicateReviewToken,
            augmentation(request.templateUse(), customFields, tagIds));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.DEAL_CREATE)
    public Deal createDeal(GuidedDealCreateRequestDto request) {
        lockPermission(Permission.DEAL_CREATE);
        ResolvedCreationTemplateDto resolved = augmentationService.resolvePreliminary(
            RecordCreationRecordType.deal, request.templateUse());
        Map<Integer, JsonNode> customFields = submittedCustomFields(request.customFields());
        List<Integer> tagIds = submittedTagIds(request.tagIds());
        var record = request.record();
        String duplicateReviewToken = record.duplicateReviewToken();
        Deal deal = record.toBean();
        applyDealDefaults(deal, customFields, tagIds, resolved);
        validateRequiredDeal(deal, customFields, tagIds, resolved);
        augmentationService.validateDealReferences(
            deal.getPipelineId(), deal.getStageId(), deal.getCompanyId());
        return dealService.createReviewed(
            deal,
            duplicateReviewToken,
            augmentation(request.templateUse(), customFields, tagIds));
    }

    private void lockPermission(Permission permission) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        workspaceService.lockAndRequirePermissions(
            workspaceId,
            Map.of(actorId, Set.of(permission)));
    }

    private void applyPersonDefaults(
            Person person,
            Map<Integer, JsonNode> customFields,
            List<Integer> tagIds,
            ResolvedCreationTemplateDto resolved) {
        for (ResolvedCreationFieldDto field : fields(resolved)) {
            applyCustomOrTagsDefault(field, customFields, tagIds);
            JsonNode value = field.defaultValue();
            if (value == null || field.customFieldId() != null) {
                continue;
            }
            switch (field.key()) {
                case "name" -> {
                    if (person.getName() == null) person.setName(text(value));
                }
                case "email" -> {
                    if (person.getEmail() == null) person.setEmail(text(value));
                }
                case "phone" -> {
                    if (person.getPhone() == null) person.setPhone(text(value));
                }
                case "title" -> {
                    if (person.getTitle() == null) person.setTitle(text(value));
                }
                case "company" -> {
                    if (person.getCompany() == null) person.setCompany(company(integer(value)));
                }
                case "leadSource" -> {
                    if (person.getLeadSource() == null) {
                        person.setLeadSource(PersonLeadSource.valueOf(text(value)));
                    }
                }
                case "leadSourceDetail" -> {
                    if (person.getLeadSourceDetail() == null) {
                        person.setLeadSourceDetail(text(value));
                    }
                }
                case "referrerPerson" -> {
                    if (person.getReferrerPersonId() == null) {
                        person.setReferrerPersonId(integer(value));
                    }
                }
                default -> {
                }
            }
        }
    }

    private void applyCompanyDefaults(
            Company company,
            Map<Integer, JsonNode> customFields,
            List<Integer> tagIds,
            ResolvedCreationTemplateDto resolved) {
        for (ResolvedCreationFieldDto field : fields(resolved)) {
            applyCustomOrTagsDefault(field, customFields, tagIds);
            JsonNode value = field.defaultValue();
            if (value == null || field.customFieldId() != null) {
                continue;
            }
            switch (field.key()) {
                case "name" -> {
                    if (company.getName() == null) company.setName(text(value));
                }
                case "website" -> {
                    if (company.getWebsite() == null) company.setWebsite(text(value));
                }
                case "industry" -> {
                    if (company.getIndustry() == null) company.setIndustry(text(value));
                }
                case "phone" -> {
                    if (company.getPhone() == null) company.setPhone(text(value));
                }
                case "address" -> {
                    if (company.getAddress() == null) company.setAddress(text(value));
                }
                default -> {
                }
            }
        }
    }

    private void applyDealDefaults(
            Deal deal,
            Map<Integer, JsonNode> customFields,
            List<Integer> tagIds,
            ResolvedCreationTemplateDto resolved) {
        for (ResolvedCreationFieldDto field : fields(resolved)) {
            applyCustomOrTagsDefault(field, customFields, tagIds);
            JsonNode value = field.defaultValue();
            if (value == null || field.customFieldId() != null) {
                continue;
            }
            switch (field.key()) {
                case "name" -> {
                    if (deal.getName() == null) deal.setName(text(value));
                }
                case "value" -> {
                    if (deal.getValue() == null) deal.setValue(decimal(value));
                }
                case "currency" -> {
                    if (deal.getCurrency() == null) deal.setCurrency(text(value));
                }
                case "pipeline" -> {
                    if (deal.getPipelineId() == null) deal.setPipelineId(integer(value));
                }
                case "stage" -> {
                    if (deal.getStageId() == null) deal.setStageId(integer(value));
                }
                case "company" -> {
                    if (deal.getCompanyId() == null) deal.setCompanyId(integer(value));
                }
                case "expectedCloseDate" -> {
                    if (deal.getExpectedCloseDate() == null) {
                        deal.setExpectedCloseDate(LocalDate.parse(text(value)).toString());
                    }
                }
                default -> {
                }
            }
        }
    }

    private void applyCustomOrTagsDefault(
            ResolvedCreationFieldDto field,
            Map<Integer, JsonNode> customFields,
            List<Integer> tagIds) {
        if (field.defaultValue() == null) {
            return;
        }
        if (field.customFieldId() != null) {
            customFields.putIfAbsent(field.customFieldId(), field.defaultValue());
            return;
        }
        if ("tags".equals(field.key()) && tagIds.isEmpty()) {
            for (JsonNode value : field.defaultValue()) {
                tagIds.add(integer(value));
            }
        }
    }

    private void validateRequiredPerson(
            Person person,
            Map<Integer, JsonNode> customFields,
            List<Integer> tagIds,
            ResolvedCreationTemplateDto resolved) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", person.getName());
        values.put("email", person.getEmail());
        values.put("phone", person.getPhone());
        values.put("title", person.getTitle());
        values.put("company", person.getCompany() == null ? null : person.getCompany().getId());
        values.put("leadSource", person.getLeadSource());
        values.put("leadSourceDetail", person.getLeadSourceDetail());
        values.put("referrerPerson", person.getReferrerPersonId());
        values.put("owner", workspaceService.getCurrentUserId());
        values.put("consentStatus", Boolean.TRUE);
        validateRequired(resolved, values, customFields, tagIds);
    }

    private void validateRequiredCompany(
            Company company,
            Map<Integer, JsonNode> customFields,
            List<Integer> tagIds,
            ResolvedCreationTemplateDto resolved) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", company.getName());
        values.put("website", company.getWebsite());
        values.put("industry", company.getIndustry());
        values.put("phone", company.getPhone());
        values.put("address", company.getAddress());
        values.put("owner", workspaceService.getCurrentUserId());
        validateRequired(resolved, values, customFields, tagIds);
    }

    private void validateRequiredDeal(
            Deal deal,
            Map<Integer, JsonNode> customFields,
            List<Integer> tagIds,
            ResolvedCreationTemplateDto resolved) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", deal.getName());
        values.put("value", deal.getValue());
        values.put("currency", deal.getCurrency());
        values.put("pipeline", deal.getPipelineId());
        values.put("stage", deal.getStageId());
        values.put("company", deal.getCompanyId());
        values.put("expectedCloseDate", deal.getExpectedCloseDate());
        values.put("owner", workspaceService.getCurrentUserId());
        validateRequired(resolved, values, customFields, tagIds);
    }

    private static void validateRequired(
            ResolvedCreationTemplateDto resolved,
            Map<String, Object> coreValues,
            Map<Integer, JsonNode> customFields,
            List<Integer> tagIds) {
        Set<Integer> activeCustomFields = fields(resolved).stream()
            .map(ResolvedCreationFieldDto::customFieldId)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!activeCustomFields.containsAll(customFields.keySet())) {
            throw RecordCreationTemplateException.of(
                HttpStatus.NOT_FOUND,
                "CUSTOM_FIELD_NOT_FOUND",
                "Custom field not found");
        }
        for (ResolvedCreationFieldDto field : fields(resolved)) {
            if (!field.required()) {
                continue;
            }
            boolean present;
            if (field.customFieldId() != null) {
                present = hasJsonValue(customFields.get(field.customFieldId()));
            } else if ("tags".equals(field.key())) {
                present = !tagIds.isEmpty();
            } else {
                present = hasValue(coreValues.get(field.key()));
            }
            if (!present) {
                throw new RecordCreationTemplateException(
                    HttpStatus.BAD_REQUEST,
                    "TEMPLATE_FIELD_NOT_SUBMITTED",
                    "A required template field was not submitted",
                    Map.of(field.key(), "A value is required"),
                    null,
                    null,
                    null,
                    null);
            }
        }
    }

    private static Map<Integer, JsonNode> submittedCustomFields(Map<Integer, JsonNode> submitted) {
        Map<Integer, JsonNode> values = new LinkedHashMap<>();
        for (Map.Entry<Integer, JsonNode> entry : submitted.entrySet()) {
            if (entry.getKey() == null
                    || entry.getKey() < 1
                    || entry.getValue() == null
                    || entry.getValue().isNull()) {
                throw invalidValues();
            }
            values.put(entry.getKey(), entry.getValue());
        }
        return values;
    }

    private static List<Integer> submittedTagIds(List<Integer> submitted) {
        if (submitted.stream().anyMatch(id -> id == null || id < 1)) {
            throw invalidValues();
        }
        return new java.util.ArrayList<>(submitted.stream().distinct().sorted().toList());
    }

    private static RecordCreationAugmentation augmentation(
            RecordCreationTemplateUseDto use,
            Map<Integer, JsonNode> customFields,
            List<Integer> tagIds) {
        return new RecordCreationAugmentation(
            use.templateId(),
            use.templateVersion(),
            use.templateSetRevision(),
            use.entryPoint(),
            use.context(),
            customFields,
            tagIds);
    }

    private static List<ResolvedCreationFieldDto> fields(ResolvedCreationTemplateDto resolved) {
        return resolved.groups().stream().flatMap(group -> group.fields().stream()).toList();
    }

    private static boolean hasValue(Object value) {
        return value != null && (!(value instanceof String text) || !text.isBlank());
    }

    private static boolean hasJsonValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isTextual()) {
            return !value.textValue().isBlank();
        }
        return !(value.isArray() || value.isObject()) || !value.isEmpty();
    }

    private static String text(JsonNode value) {
        return value.textValue();
    }

    private static int integer(JsonNode value) {
        return value.intValue();
    }

    private static BigDecimal decimal(JsonNode value) {
        return value.decimalValue();
    }

    private static Company company(int id) {
        Company company = new Company();
        company.setId(id);
        return company;
    }

    private static RecordCreationTemplateException invalidValues() {
        return RecordCreationTemplateException.of(
            HttpStatus.BAD_REQUEST,
            "VALIDATION_FAILED",
            "Guided record values are invalid");
    }
}
