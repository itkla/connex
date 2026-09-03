package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Sequence;
import ooo.klae.connex.backend.beans.SequenceStep;
import ooo.klae.connex.backend.beans.SequenceStepContent;
import ooo.klae.connex.backend.beans.SequenceVersion;

class SequenceMapperTest extends AbstractMapperTest {

    @Autowired private SequenceMapper sequenceMapper;
    @Autowired private SequenceVersionMapper versionMapper;
    @Autowired private OrganizationMapper organizationMapper;

    @BeforeEach
    @Override
    void setUpWorkspace() {
        String suffix = unique();
        Organization organization = new Organization();
        organization.setName("Sequence mapper " + suffix);
        organization.setSlug("sequence-mapper-" + suffix);
        organizationMapper.insert(organization);
        workspace = new ooo.klae.connex.backend.beans.Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Sequence mapper " + suffix);
        workspace.setSlug("sequence-mapper-" + suffix);
        workspaceMapper.insert(workspace);
    }

    @Test
    void workspaceAndPersonalVisibilityPredicatesFailClosed() {
        Sequence personal = insertSequence("personal", 101);
        Sequence shared = insertSequence("shared", 101);

        assertNotNull(sequenceMapper.getVisibleSequence(workspace.getId(), personal.getId(), 101));
        assertNull(sequenceMapper.getVisibleSequence(workspace.getId(), personal.getId(), 202));
        assertNotNull(sequenceMapper.getVisibleSequence(workspace.getId(), shared.getId(), 202));
        assertNull(sequenceMapper.getVisibleSequence(workspace.getId() + 1, shared.getId(), 101));
        assertEquals(
            List.of(shared.getId()),
            sequenceMapper.getVisibleSequences(workspace.getId(), 202).stream()
                .map(Sequence::getId)
                .toList());
    }

    @Test
    void draftStepsAndLocalizedContentRemainOrderedAndWorkspaceScoped() {
        Sequence sequence = insertSequence("personal", 101);
        SequenceStep second = insertStep(sequence, 1, "wait");
        SequenceStep first = insertStep(sequence, 0, "send_email");
        insertContent(first, "ja", "こんにちは");
        insertContent(first, "en", "Hello");

        assertEquals(
            List.of(first.getId(), second.getId()),
            sequenceMapper.getSteps(workspace.getId(), sequence.getId()).stream()
                .map(SequenceStep::getId)
                .toList());
        assertEquals(
            List.of(first.getId(), second.getId()),
            sequenceMapper.getStepsForShare(workspace.getId(), sequence.getId()).stream()
                .map(SequenceStep::getId)
                .toList());
        assertEquals(
            List.of("en", "ja"),
            sequenceMapper.getStepContents(workspace.getId(), List.of(first.getId())).stream()
                .map(SequenceStepContent::getLocale)
                .toList());
        assertEquals(
            List.of("en", "ja"),
            sequenceMapper.getStepContentsForShare(workspace.getId(), List.of(first.getId())).stream()
                .map(SequenceStepContent::getLocale)
                .toList());
        assertEquals(
            List.of(),
            sequenceMapper.getStepContents(workspace.getId() + 1, List.of(first.getId())));
    }

    @Test
    void publishedVersionRowsRemainByteIdenticalAfterAnotherInsert() throws Exception {
        Sequence sequence = insertSequence("personal", 101);
        SequenceVersion first = insertVersion(sequence, 1, "{\"schemaVersion\":1,\"steps\":[]}");
        byte[] originalHash = first.getDefinitionHash().clone();
        String originalJson = first.getDefinitionJson();

        insertVersion(sequence, 2, "{\"schemaVersion\":1,\"steps\":[{}]}");

        SequenceVersion retained = versionMapper.getVersion(workspace.getId(), sequence.getId(), 1);
        assertEquals(originalJson, retained.getDefinitionJson());
        assertArrayEquals(originalHash, retained.getDefinitionHash());
        assertEquals(3, versionMapper.nextVersionNumberForUpdate(workspace.getId(), sequence.getId()));
        assertNull(versionMapper.getVersion(workspace.getId() + 1, sequence.getId(), 1));
    }

    private Sequence insertSequence(String visibility, int ownerId) {
        Sequence sequence = new Sequence();
        sequence.setWorkspaceId(workspace.getId());
        sequence.setName("Sequence " + unique());
        sequence.setOwnerId(ownerId);
        sequence.setVisibility(visibility);
        sequence.setStatus("draft");
        sequence.setTimezone("UTC");
        sequence.setWeekdayMask(31);
        sequence.setSendWindowStart(LocalTime.of(9, 0));
        sequence.setSendWindowEnd(LocalTime.of(17, 0));
        sequence.setCreatedById(ownerId);
        sequence.setUpdatedById(ownerId);
        sequenceMapper.insertSequence(sequence);
        return sequence;
    }

    private SequenceStep insertStep(Sequence sequence, int position, String type) {
        SequenceStep step = new SequenceStep();
        step.setWorkspaceId(workspace.getId());
        step.setSequenceId(sequence.getId());
        step.setPosition(position);
        step.setStepType(type);
        step.setDelayValue(0);
        step.setDelayUnit("hours");
        step.setAdvancePolicy("automatic");
        sequenceMapper.insertStep(step);
        return step;
    }

    private void insertContent(SequenceStep step, String locale, String subject) {
        SequenceStepContent content = new SequenceStepContent();
        content.setWorkspaceId(workspace.getId());
        content.setStepId(step.getId());
        content.setLocale(locale);
        content.setSubject(subject);
        sequenceMapper.insertStepContent(content);
    }

    private SequenceVersion insertVersion(Sequence sequence, int number, String definition)
            throws Exception {
        SequenceVersion version = new SequenceVersion();
        version.setWorkspaceId(workspace.getId());
        version.setSequenceId(sequence.getId());
        version.setVersionNumber(number);
        version.setDefinitionJson(definition);
        version.setDefinitionHash(MessageDigest.getInstance("SHA-256")
            .digest(definition.getBytes(StandardCharsets.UTF_8)));
        version.setPublishedById(101);
        versionMapper.insertVersion(version);
        versionMapper.insertVersionPublisher(
            sequence.getWorkspaceId(), version.getId(), version.getPublishedById());
        return version;
    }
}
