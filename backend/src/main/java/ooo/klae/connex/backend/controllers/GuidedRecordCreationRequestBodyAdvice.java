package ooo.klae.connex.backend.controllers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.recordcreation.GuidedCompanyCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedPersonCreateRequestDto;

@ControllerAdvice(assignableTypes = GuidedRecordCreationController.class)
@RequiredArgsConstructor
public class GuidedRecordCreationRequestBodyAdvice extends RequestBodyAdviceAdapter {
    private static final Set<String> REQUEST_FIELDS =
        Set.of("record", "templateUse", "customFields", "tagIds");
    private static final Set<String> TEMPLATE_USE_FIELDS =
        Set.of("templateId", "templateVersion", "templateSetRevision", "entryPoint", "context");
    private static final Set<String> CONTEXT_FIELDS = Set.of("relatedCompanyId");
    private static final Map<Class<?>, Set<String>> RECORD_FIELDS = Map.of(
        GuidedPersonCreateRequestDto.class,
        Set.of(
            "name", "email", "phone", "companyId", "title", "leadSource",
            "leadSourceDetail", "referrerPersonId", "duplicateReviewToken"),
        GuidedCompanyCreateRequestDto.class,
        Set.of("name", "website", "industry", "phone", "address", "duplicateReviewToken"),
        GuidedDealCreateRequestDto.class,
        Set.of(
            "name", "value", "currency", "pipeline", "stage", "company",
            "expectedCloseDate", "duplicateReviewToken"));

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(
            MethodParameter methodParameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType instanceof Class<?> type && RECORD_FIELDS.containsKey(type);
    }

    @Override
    public HttpInputMessage beforeBodyRead(
            HttpInputMessage inputMessage,
            MethodParameter parameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        byte[] body = inputMessage.getBody().readAllBytes();
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception exception) {
            return replay(inputMessage.getHeaders(), body);
        }
        Class<?> requestType = (Class<?>) targetType;
        requireOnly(root, REQUEST_FIELDS, inputMessage);
        requireOnly(root == null ? null : root.get("record"), RECORD_FIELDS.get(requestType), inputMessage);
        JsonNode templateUse = root == null ? null : root.get("templateUse");
        requireOnly(templateUse, TEMPLATE_USE_FIELDS, inputMessage);
        requireOnly(templateUse == null ? null : templateUse.get("context"), CONTEXT_FIELDS, inputMessage);
        return replay(inputMessage.getHeaders(), body);
    }

    private static void requireOnly(
            JsonNode node,
            Set<String> allowed,
            HttpInputMessage inputMessage) {
        if (node == null || !node.isObject()) {
            return;
        }
        Collection<String> properties = node.propertyNames();
        if (!allowed.containsAll(properties)) {
            throw new HttpMessageNotReadableException("Unknown guided record creation property", inputMessage);
        }
    }

    private static HttpInputMessage replay(HttpHeaders headers, byte[] body) {
        return new HttpInputMessage() {
            @Override
            public ByteArrayInputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }
}
