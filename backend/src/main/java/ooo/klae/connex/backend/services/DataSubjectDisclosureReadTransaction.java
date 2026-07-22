package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.PersonDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DataSubjectDisclosureMapper;

/** Reads a complete subject disclosure from one routed tenant-catalog snapshot. */
@Component
@RequiredArgsConstructor
public class DataSubjectDisclosureReadTransaction {
    private final DataSubjectDisclosureMapper dataSubjectDisclosureMapper;

    @Transactional(readOnly = true)
    public boolean subjectPersonExists(int workspaceId, int personId) {
        return dataSubjectDisclosureMapper.subjectPersonExists(workspaceId, personId);
    }

    @Transactional(readOnly = true)
    public DataSubjectDisclosureDto assemble(int workspaceId, int personId, List<Integer> workspaceIds) {
        if (workspaceIds.isEmpty()) {
            throw new IllegalArgumentException("A disclosure workspace allowlist cannot be empty");
        }
        PersonDto person = dataSubjectDisclosureMapper.findPerson(workspaceId, personId, workspaceIds);
        if (person == null) {
            throw new ResourceNotFoundException("Linked subject person not found: " + personId);
        }
        DataSubjectDisclosureDto disclosure = new DataSubjectDisclosureDto();
        disclosure.setPerson(person);
        disclosure.setTags(dataSubjectDisclosureMapper.findTags(workspaceId, personId, workspaceIds));
        disclosure.setCustomFieldValues(
            dataSubjectDisclosureMapper.findCustomFields(workspaceId, personId, workspaceIds));
        disclosure.setActivities(dataSubjectDisclosureMapper.findActivities(workspaceId, personId, workspaceIds));
        disclosure.setNotes(dataSubjectDisclosureMapper.findNotes(workspaceId, personId, workspaceIds));
        disclosure.setTasks(dataSubjectDisclosureMapper.findTasks(workspaceId, personId, workspaceIds));
        disclosure.setAttachments(
            dataSubjectDisclosureMapper.findAttachments(workspaceId, personId, workspaceIds));
        disclosure.setEmploymentHistory(
            dataSubjectDisclosureMapper.findEmployment(workspaceId, personId, workspaceIds));
        disclosure.setRelationshipEdges(dataSubjectDisclosureMapper.findEdges(workspaceId, personId, workspaceIds));
        disclosure.setDealAssociations(dataSubjectDisclosureMapper.findDeals(workspaceId, personId, workspaceIds));
        disclosure.setIntroductions(
            dataSubjectDisclosureMapper.findIntroductions(workspaceId, personId, workspaceIds));
        disclosure.setThirdPartyProvisions(
            dataSubjectDisclosureMapper.findProvisions(workspaceId, personId, workspaceIds));
        return disclosure;
    }
}
