package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.Size;
import ooo.klae.connex.backend.dto.ActivityDto;
import ooo.klae.connex.backend.dto.BusinessCardCompanyAction;
import ooo.klae.connex.backend.dto.BusinessCardContactRequest;
import ooo.klae.connex.backend.dto.CompanyDto;
import ooo.klae.connex.backend.dto.CompanyDuplicatePreflightRequest;
import ooo.klae.connex.backend.dto.ImportRequest;
import ooo.klae.connex.backend.dto.LoginDto;
import ooo.klae.connex.backend.dto.PersonDto;
import ooo.klae.connex.backend.dto.PersonDuplicatePreflightRequest;
import ooo.klae.connex.backend.dto.RegisterDto;
import ooo.klae.connex.backend.dto.SendDeliveryRequest;
import ooo.klae.connex.backend.dto.TaskDto;

/** Exercises the application's configured interpolator against string and container constraints. */
class ValidationMessagesTest {
    private static final LocalValidatorFactoryBean VALIDATOR = configuredValidator();

    private static LocalValidatorFactoryBean configuredValidator() {
        ValidationConfig configuration = new ValidationConfig();
        LocalValidatorFactoryBean validator = configuration.defaultValidator(configuration.validationMessageSource());
        validator.afterPropertiesSet();
        return validator;
    }

    @AfterAll
    static void closeValidator() {
        VALIDATOR.close();
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @ParameterizedTest
    @ValueSource(strings = { "en", "ja" })
    void emptyDeliveryRecipientsUseTypeNeutralRangeCopy(String language) {
        LocaleContextHolder.setLocale(Locale.forLanguageTag(language));
        String expected = language.equals("ja")
            ? "サイズは1〜20の範囲で指定してください"
            : "Keep the size between 1 and 20";
        String message = sizeMessage(VALIDATOR.validate(new SendDeliveryRequest()));
        assertEquals(expected, message);
        assertFalse(message.contains("characters"));
        assertFalse(message.contains("文字"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "en", "ja" })
    void importMapLimitUsesTypeNeutralMaximumCopy(String language) {
        LocaleContextHolder.setLocale(Locale.forLanguageTag(language));
        var links = IntStream.rangeClosed(0, 5000).boxed()
            .collect(Collectors.toMap(index -> index, index -> 1));
        String expected = language.equals("ja")
            ? "サイズは5000以下にしてください"
            : "Keep the size at or below 5000";
        assertEquals(expected, sizeMessage(VALIDATOR.validateValue(ImportRequest.class, "companyLinks", links)));
    }

    @ParameterizedTest
    @ValueSource(strings = { "en", "ja" })
    void defaultMaxOnlyStringOmitsZeroLowerBound(String language) {
        LocaleContextHolder.setLocale(Locale.forLanguageTag(language));
        String expected = language.equals("ja")
            ? "サイズは2000以下にしてください"
            : "Keep the size at or below 2000";
        assertEquals(expected, sizeMessage(VALIDATOR.validateValue(
            SendDeliveryRequest.class, "message", "a".repeat(2001))));
    }

    static Stream<Arguments> formStringLimits() {
        return Stream.of(
            Arguments.of(RegisterDto.class, "displayName", 128),
            Arguments.of(RegisterDto.class, "email", 255),
            Arguments.of(RegisterDto.class, "timezone", 64),
            Arguments.of(LoginDto.class, "username", 255),
            Arguments.of(LoginDto.class, "password", 255),
            Arguments.of(ActivityDto.class, "type", 32),
            Arguments.of(ActivityDto.class, "timestamp", 32),
            Arguments.of(TaskDto.class, "description", 1000),
            Arguments.of(TaskDto.class, "dueDate", 10),
            Arguments.of(PersonDto.class, "name", 255),
            Arguments.of(PersonDto.class, "email", 255),
            Arguments.of(PersonDto.class, "phone", 64),
            Arguments.of(PersonDto.class, "title", 128),
            Arguments.of(PersonDto.class, "leadSourceDetail", 255),
            Arguments.of(CompanyDto.class, "name", 255),
            Arguments.of(CompanyDto.class, "website", 255),
            Arguments.of(CompanyDto.class, "industry", 128),
            Arguments.of(CompanyDto.class, "phone", 64),
            Arguments.of(CompanyDto.class, "address", 512),
            Arguments.of(BusinessCardContactRequest.class, "name", 255),
            Arguments.of(BusinessCardContactRequest.class, "email", 255),
            Arguments.of(BusinessCardContactRequest.class, "phone", 64),
            Arguments.of(BusinessCardContactRequest.class, "title", 128),
            Arguments.of(BusinessCardCompanyAction.Create.class, "companyName", 255),
            Arguments.of(PersonDuplicatePreflightRequest.class, "name", 255),
            Arguments.of(CompanyDuplicatePreflightRequest.class, "name", 255))
            .flatMap(arguments -> Stream.of("en", "ja").map(language -> Arguments.of(
                arguments.get()[0], arguments.get()[1], arguments.get()[2], language)));
    }

    @ParameterizedTest
    @MethodSource("formStringLimits")
    void maxOnlyFormStringsUseCharacterSpecificCopy(Class<?> type, String field, int max, String language) {
        LocaleContextHolder.setLocale(Locale.forLanguageTag(language));
        String expected = language.equals("ja")
            ? max + "文字以内で入力してください"
            : "Use " + max + " characters or fewer";
        assertEquals(expected, sizeMessage(VALIDATOR.validateValue(type, field, "a".repeat(max + 1))));
    }

    @ParameterizedTest
    @ValueSource(strings = { "en", "ja" })
    void nestedPreflightValuesKeepCharacterLimitsWhileListsKeepTypeNeutralLimits(String language) {
        LocaleContextHolder.setLocale(Locale.forLanguageTag(language));
        String expected = language.equals("ja")
            ? "2048文字以内で入力してください"
            : "Use 2048 characters or fewer";
        assertEquals(expected, sizeMessage(VALIDATOR.validate(new PersonDuplicatePreflightRequest(
            "Contact", List.of("a".repeat(2049)), List.of()))));
        assertEquals(expected, sizeMessage(VALIDATOR.validate(new CompanyDuplicatePreflightRequest(
            "Company", List.of("a".repeat(2049)), List.of()))));
        expected = language.equals("ja") ? "サイズは8以下にしてください" : "Keep the size at or below 8";
        assertEquals(expected, sizeMessage(VALIDATOR.validate(new PersonDuplicatePreflightRequest(
            "Contact", java.util.Collections.nCopies(9, "valid"), List.of()))));
    }

    private static String sizeMessage(Set<? extends ConstraintViolation<?>> violations) {
        var sizes = violations.stream()
            .filter(violation -> violation.getConstraintDescriptor().getAnnotation() instanceof Size)
            .map(ConstraintViolation::getMessage)
            .toList();
        assertEquals(1, sizes.size());
        return sizes.getFirst();
    }
}
