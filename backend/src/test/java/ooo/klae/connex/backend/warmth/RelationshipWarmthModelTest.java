package ooo.klae.connex.backend.warmth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Exact numeric regression tests for the versioned warmth formula and its SQL parameter contract.
 */
class RelationshipWarmthModelTest {
    private final RelationshipWarmthModel model = RelationshipWarmthModel.current();

    @Test
    void currentMeetingHasPinnedScoreBandTrendAndPrediction() {
        double rawWeight = model.decayedContribution(model.activityWeight("meeting"), 0.0);

        assertEquals(1.0, rawWeight);
        assertEquals(63, model.score(rawWeight));
        assertEquals("hot", model.band(model.score(rawWeight)));
        assertEquals("rising", model.trend(1.0, 0.0, 0));
        assertEquals(78L, Math.round(model.daysToCold(rawWeight).orElseThrow()));
        assertEquals("warmth-v1", model.version());
    }

    @Test
    void thirtyDayMeetingHasPinnedDecayScoreTrendAndPrediction() {
        double rawWeight = model.decayedContribution(model.activityWeight("meeting"), 30.0);

        assertEquals(0.5, rawWeight, 1.0e-15);
        assertEquals(39, model.score(rawWeight));
        assertEquals("warm", model.band(model.score(rawWeight)));
        assertEquals("cooling", model.trend(0.0, 1.0, 30));
        assertEquals(48L, Math.round(model.daysToCold(rawWeight).orElseThrow()));
    }

    @Test
    void warmBoundaryMatchesJavaHalfUpRoundingExactly() {
        double boundary = model.sqlParameters().warmMinimumRawWeight();

        assertEquals(0x1.b58efa77a4532p-2, boundary);
        assertEquals(35, model.score(boundary));
        assertEquals("warm", model.band(model.score(boundary)));
        assertEquals(34, model.score(Math.nextDown(boundary)));
        assertEquals("cool", model.band(model.score(Math.nextDown(boundary))));
    }

    @Test
    void sqlParametersExposeTheSameWeightsAndDecayDefinition() {
        RelationshipWarmthModel.SqlParameters parameters = model.sqlParameters();

        assertEquals(2.0, parameters.decayBase());
        assertEquals(30.0, parameters.halfLifeDays());
        assertEquals(86_400_000_000.0, parameters.microsecondsPerDay());
        assertEquals(21, parameters.recentWindowDays());
        assertEquals(120, parameters.priorWindowDays());
        assertEquals(1.0, parameters.meetingWeight());
        assertEquals(0.8, parameters.callWeight());
        assertEquals(0.6, parameters.emailWeight());
        assertEquals(0.5, parameters.otherActivityWeight());
        assertEquals(0.4, parameters.noteWeight());
        assertEquals(0.3, parameters.taskWeight());
        assertEquals(parameters.meetingWeight(), model.activityWeight("meeting"));
        assertEquals(parameters.callWeight(), model.activityWeight("CALL"));
        assertEquals(parameters.emailWeight(), model.activityWeight("email"));
        assertEquals(parameters.otherActivityWeight(), model.activityWeight("other"));
        assertEquals(parameters.noteWeight(), model.noteWeight());
        assertEquals(parameters.taskWeight(), model.taskWeight());
    }

    @Test
    void bandAndTrendBoundariesRemainPinnedToVersionOne() {
        assertEquals("cold", model.band(14));
        assertEquals("cool", model.band(15));
        assertEquals("cool", model.band(34));
        assertEquals("warm", model.band(35));
        assertEquals("warm", model.band(59));
        assertEquals("hot", model.band(60));
        assertEquals("steady", model.trend(0.0, 0.799_999, 21));
        assertEquals("steady", model.trend(0.4, 0.8, 21));
        assertEquals("steady", model.trend(0.0, 0.8, 20));
        assertEquals("cooling", model.trend(0.399_999, 0.8, 21));
    }
}
