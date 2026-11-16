package ooo.klae.connex.backend.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;

class RecordInsertBatchesTest {
    @Test
    void expandedBulkNamesKeepOriginalBeansAndOrderWithinTheRowBound() {
        List<Person> people = new ArrayList<>();
        List<Company> companies = new ArrayList<>();
        List<Deal> deals = new ArrayList<>();
        for (int index = 0; index < 5_000; index++) {
            String name = "ﷺ".repeat(250) + "%05d".formatted(index);
            Person person = new Person();
            person.setName(name);
            people.add(person);
            Company company = new Company();
            company.setName(name);
            companies.add(company);
            Deal deal = new Deal();
            deal.setName(name);
            deals.add(deal);
        }
        for (var batches : List.of(RecordInsertBatches.people(people), RecordInsertBatches.companies(companies), RecordInsertBatches.deals(deals))) {
            assertTrue(batches.size() >= 50);
            assertTrue(batches.stream().allMatch(batch -> !batch.isEmpty() && batch.size() <= RecordInsertBatches.MAX_ROWS));
        }
        assertEquals(people, RecordInsertBatches.people(people).stream().flatMap(List::stream).toList());
        assertEquals(companies, RecordInsertBatches.companies(companies).stream().flatMap(List::stream).toList());
        assertEquals(deals, RecordInsertBatches.deals(deals).stream().flatMap(List::stream).toList());
    }

    @Test
    void largeOtherColumnsSplitByPayloadBeforeReachingTheRowLimit() {
        List<Company> companies = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            Company company = new Company();
            company.setName("Company " + index);
            company.setAddress("x".repeat(60_000));
            companies.add(company);
        }
        assertEquals(List.of(2, 2, 1), RecordInsertBatches.companies(companies).stream().map(List::size).toList());
    }
}
