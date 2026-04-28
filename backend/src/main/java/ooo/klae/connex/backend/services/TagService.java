package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

import java.util.List;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for {@code Tag} CRUD and tag-association reads.
 * Delegates persistence to {@code TagMapper}.
 */

@Service
@RequiredArgsConstructor
public class TagService {
    private final TagMapper tagMapper;

    public List<Tag> getAllTags() {
        return tagMapper.getAllTags();
    }

    public Tag getTagById(int id) {
        Tag tag = tagMapper.getTagById(id);
        if (tag == null) throw new ResourceNotFoundException("Tag not found with id: " + id);
        return tag;
    }

    public Tag create(Tag tag) {
        tagMapper.insert(tag);
        return tag;
    }

    public Tag update(int id, Tag tag) {
        if (tagMapper.getTagById(id) == null) throw new ResourceNotFoundException("Tag not found with id: " + id);
        tag.setId(id);
        tagMapper.update(tag);
        return tag;
    }

    public void delete(int id) {
        if (tagMapper.getTagById(id) == null) throw new ResourceNotFoundException("Tag not found with id: " + id);
        tagMapper.delete(id);
    }

    public List<Tag> getTagsByPersonId(int personId) {
        return tagMapper.getTagsByPersonId(personId);
    }

    public List<Tag> getTagsByCompanyId(int companyId) {
        return tagMapper.getTagsByCompanyId(companyId);
    }

    public List<Tag> getTagsByDealId(int dealId) {
        return tagMapper.getTagsByDealId(dealId);
    }
}
