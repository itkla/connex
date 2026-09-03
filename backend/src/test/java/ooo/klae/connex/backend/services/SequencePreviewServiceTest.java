package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Sequence;
import ooo.klae.connex.backend.beans.SequenceVersion;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.sequence.SequencePreviewDto;
import ooo.klae.connex.backend.dto.sequence.SequencePreviewRequest;
import ooo.klae.connex.backend.dto.sequence.SequenceStepDto;
import ooo.klae.connex.backend.dto.sequence.SequenceStepType;
import ooo.klae.connex.backend.exceptions.SequenceException;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;

@ExtendWith(MockitoExtension.class)
class SequencePreviewServiceTest {
    @Mock private SequenceService sequenceService;
    @Mock private SequenceVersionService versionService;
    @Mock private PersonMapper personMapper;
    @Mock private DealMapper dealMapper;
    @Mock private UserService userService;
    @Mock private AuthService authService;
    @Mock private WorkspaceService workspaceService;

    private SequencePreviewService service;
    private Sequence sequence;
    private SequenceVersion version;
    private Person person;
    private User actor;

    @BeforeEach
    void setUp() {
        SequenceMergeFieldResolver resolver = new SequenceMergeFieldResolver(
            personMapper, dealMapper, userService);
        service = new SequencePreviewService(
            sequenceService, versionService, resolver, workspaceService, authService);
        actor = new User();
        actor.setId(9);
        actor.setDisplayName("Owner");
        actor.setEmail("owner@example.com");
        sequence = new Sequence();
        sequence.setId(41);
        sequence.setOwnerId(9);
        person = new Person();
        person.setId(73);
        person.setOwnerId(9);
        person.setName("<Mina>");
        version = new SequenceVersion();
        version.setVersionNumber(2);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(authService.getCurrentUser()).thenReturn(actor);
        LocaleContextHolder.setLocale(Locale.JAPANESE);
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void previewFallsBackToEnglishEscapesHtmlAndReportsExactUnresolvedFields() {
        when(sequenceService.requireVisible(7, 41, 9)).thenReturn(sequence);
        when(versionService.requireVersion(7, 41, 2)).thenReturn(version);
        when(userService.getActiveWorkspaceUser(7, 9)).thenReturn(actor);
        when(personMapper.getSequencePreviewPerson(eq(7), eq(73), any(MemberScope.class)))
            .thenReturn(person);
        when(dealMapper.getSequencePreviewDeals(eq(7), eq(73), any(MemberScope.class)))
            .thenReturn(List.of());
        when(versionService.parseSteps(version)).thenReturn(List.of(new SequenceStepDto(
            0,
            SequenceStepType.SEND_EMAIL,
            0,
            "hours",
            "automatic",
            List.of(new SequenceStepDto.ContentDto(
                "en",
                "Hello {{person.name}}",
                "At {{company.name}}",
                "<p>Hello {{person.name}}</p>")))));

        SequencePreviewDto preview = service.preview(41, 2, new SequencePreviewRequest(73));

        assertEquals("en", preview.steps().getFirst().locale());
        assertEquals("Hello <Mina>", preview.steps().getFirst().subject());
        assertEquals("<p>Hello &lt;Mina&gt;</p>", preview.steps().getFirst().bodyHtml());
        assertEquals(List.of("company.name"), preview.unresolvedMergeFields());
        verify(personMapper).getSequencePreviewPerson(eq(7), eq(73), any(MemberScope.class));
        verify(sequenceService).requireViewPermission(7, 9);
    }

    @Test
    void previewResolvesPrimaryCompanyAndDealFields() {
        when(sequenceService.requireVisible(7, 41, 9)).thenReturn(sequence);
        when(versionService.requireVersion(7, 41, 2)).thenReturn(version);
        Company company = new Company();
        company.setId(101);
        company.setName("Acme & Partners");
        person.setCompany(company);
        Deal deal = new Deal();
        deal.setId(202);
        deal.setName("Expansion");
        deal.setValue(new BigDecimal("12500.00"));
        deal.setCurrency("JPY");
        when(personMapper.getSequencePreviewPerson(eq(7), eq(73), any(MemberScope.class)))
            .thenReturn(person);
        when(dealMapper.getSequencePreviewDeals(eq(7), eq(73), any(MemberScope.class)))
            .thenReturn(List.of(deal));
        when(versionService.parseSteps(version)).thenReturn(List.of(new SequenceStepDto(
            0,
            SequenceStepType.SEND_EMAIL,
            0,
            "hours",
            "automatic",
            List.of(new SequenceStepDto.ContentDto(
                "ja",
                "{{company.name}} / {{deal.name}}",
                "{{deal.value}} {{deal.currency}}",
                "<p>{{company.name}}</p>")))));

        SequencePreviewDto preview = service.preview(41, 2, new SequencePreviewRequest(73));

        assertEquals("Acme & Partners / Expansion", preview.steps().getFirst().subject());
        assertEquals("12500 JPY", preview.steps().getFirst().bodyText());
        assertEquals("<p>Acme &amp; Partners</p>", preview.steps().getFirst().bodyHtml());
        assertEquals(List.of(), preview.unresolvedMergeFields());
    }

    @Test
    void mergeFieldCatalogRechecksViewPermission() {
        service.mergeFields();

        verify(sequenceService).requireViewPermission(7, 9);
        verify(workspaceService).getCurrentWorkspaceId();
    }

    @Test
    void previewRefusesContactsExcludedByTheMemberScopedMapper() {
        when(sequenceService.requireVisible(7, 41, 9)).thenReturn(sequence);
        when(versionService.requireVersion(7, 41, 2)).thenReturn(version);
        when(personMapper.getSequencePreviewPerson(eq(7), eq(73), any(MemberScope.class)))
            .thenReturn(null);
        assertThrows(SequenceException.class,
            () -> service.preview(41, 2, new SequencePreviewRequest(73)));
    }
}
