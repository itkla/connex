package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

import java.util.List;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for logging and retrieving {@code Deal} records.
 * Handles mapping between {@code DealDto} and {@code Deal} bean.
 * Delegates persistence to {@code DealMapper}.
 */

@Service
@RequiredArgsConstructor
public class DealService {
    private final DealMapper dealMapper;
    private final PersonMapper personMapper;
    private final TagMapper tagMapper;

    /**
     * Retrieves all {@code Deal} records.
     * @return
     */
    public List<Deal> getAllDeals() {
        return dealMapper.getAllDeals();
    }

    public List<Deal> getDealsByPipelineId(int pipelineId) {
        return dealMapper.getDealsByPipelineId(pipelineId);
    }

    public List<Deal> getDealsByStageId(int stageId) {
        return dealMapper.getDealsByStageId(stageId);
    }

    public List<Deal> getDealsByCompanyId(int companyId) {
        return dealMapper.getDealsByCompanyId(companyId);
    }

    public List<Deal> getDealsByPersonId(int personId) {
        return dealMapper.getDealsByPersonId(personId);
    }

    public List<Deal> getDealsByTagId(int tagId) {
        return dealMapper.getDealsByTagId(tagId);
    }

    /**
     * Retrieves a {@code Deal} by ID, throwing a {@code ResourceNotFoundException} if not found.
     * @param id
     * @return
     */
    public Deal getDealById(int id) {
        Deal deal = dealMapper.getDealById(id);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + id);
        return deal;
    }

    /**
     * Creates a new {@code Deal} record.
     * @param deal
     * @return
     */
    public Deal create(Deal deal) {
        dealMapper.insert(deal);
        return deal;
    }

    /**
     * Updates an existing {@code Deal} record.
     * @param id
     * @param deal
     * @return
     */
    public Deal update(int id, Deal deal) {
        if (dealMapper.getDealById(id) == null) throw new ResourceNotFoundException("Deal not found with id: " + id);
        deal.setId(id);
        dealMapper.update(deal);
        return deal;
    }

    /**
     * Deletes a {@code Deal} record by ID, throwing a {@code ResourceNotFoundException} if not found.
     * @param id
     */
    public void delete(int id) {
        if (dealMapper.getDealById(id) == null) throw new ResourceNotFoundException("Deal not found with id: " + id);
        dealMapper.delete(id);
    }

    public List<Tag> getTagsByDealId(int dealId) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        return tagMapper.getTagsByDealId(dealId);
    }

    public void addTag(int dealId, int tagId) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        if (tagMapper.getTagById(tagId) == null) throw new ResourceNotFoundException("Tag not found with id: " + tagId);
        dealMapper.addTag(dealId, tagId);
    }

    public void removeTag(int dealId, int tagId) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        dealMapper.removeTag(dealId, tagId);
    }

    public List<Person> getPeopleByDealId(int dealId) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        return personMapper.getPersonsByDealId(dealId);
    }

    public void addPerson(int dealId, int personId) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        if (personMapper.getPersonById(personId) == null) throw new ResourceNotFoundException("Person not found with id: " + personId);
        dealMapper.addPerson(dealId, personId);
    }

    public void removePerson(int dealId, int personId) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        dealMapper.removePerson(dealId, personId);
    }

    @Transactional
    public List<Tag> replaceTags(int dealId, List<Integer> tagIds) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        dealMapper.clearTags(dealId);
        if (tagIds != null && !tagIds.isEmpty()) dealMapper.insertTags(dealId, tagIds);
        return tagMapper.getTagsByDealId(dealId);
    }

    @Transactional
    public List<Person> replacePeople(int dealId, List<Integer> personIds) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        dealMapper.clearPeople(dealId);
        if (personIds != null && !personIds.isEmpty()) dealMapper.insertPeople(dealId, personIds);
        return personMapper.getPersonsByDealId(dealId);
    }
}
