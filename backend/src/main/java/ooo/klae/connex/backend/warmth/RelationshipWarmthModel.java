package ooo.klae.connex.backend.warmth;

import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Versioned definition of the relationship-warmth model shared by Java and SQL scoring paths.
 *
 * <p>The model is immutable. SQL callers bind {@link SqlParameters} from this instance so tuning
 * values cannot drift into mapper-local literals.
 */
public final class RelationshipWarmthModel {
    private static final RelationshipWarmthModel CURRENT = new RelationshipWarmthModel(
        "warmth-v1",
        2.0,
        30.0,
        0.7,
        60,
        35,
        15,
        21,
        120,
        0.8,
        0.5,
        1.0,
        0.8,
        0.6,
        0.5,
        0.4,
        0.3,
        86_400_000L
    );

    private final String version;
    private final double decayBase;
    private final double halfLifeDays;
    private final double saturation;
    private final int hotMinimumScore;
    private final int warmMinimumScore;
    private final int coolMinimumScore;
    private final int recentWindowDays;
    private final int priorWindowDays;
    private final double coolingPriorMinimum;
    private final double coolingRecentRatio;
    private final double meetingWeight;
    private final double callWeight;
    private final double emailWeight;
    private final double otherActivityWeight;
    private final double noteWeight;
    private final double taskWeight;
    private final long millisecondsPerDay;
    private final double coldRawWeight;
    private final double warmMinimumRawWeight;
    private final SqlParameters sqlParameters;

    private RelationshipWarmthModel(
            String version,
            double decayBase,
            double halfLifeDays,
            double saturation,
            int hotMinimumScore,
            int warmMinimumScore,
            int coolMinimumScore,
            int recentWindowDays,
            int priorWindowDays,
            double coolingPriorMinimum,
            double coolingRecentRatio,
            double meetingWeight,
            double callWeight,
            double emailWeight,
            double otherActivityWeight,
            double noteWeight,
            double taskWeight,
            long millisecondsPerDay) {
        this.version = version;
        this.decayBase = decayBase;
        this.halfLifeDays = halfLifeDays;
        this.saturation = saturation;
        this.hotMinimumScore = hotMinimumScore;
        this.warmMinimumScore = warmMinimumScore;
        this.coolMinimumScore = coolMinimumScore;
        this.recentWindowDays = recentWindowDays;
        this.priorWindowDays = priorWindowDays;
        this.coolingPriorMinimum = coolingPriorMinimum;
        this.coolingRecentRatio = coolingRecentRatio;
        this.meetingWeight = meetingWeight;
        this.callWeight = callWeight;
        this.emailWeight = emailWeight;
        this.otherActivityWeight = otherActivityWeight;
        this.noteWeight = noteWeight;
        this.taskWeight = taskWeight;
        this.millisecondsPerDay = millisecondsPerDay;
        coldRawWeight = rawWeightForExactScore(coolMinimumScore);
        warmMinimumRawWeight = rawWeightForRoundedScore(warmMinimumScore);
        sqlParameters = new SqlParameters(
            decayBase,
            halfLifeDays,
            millisecondsPerDay * 1_000.0,
            recentWindowDays,
            priorWindowDays,
            meetingWeight,
            callWeight,
            emailWeight,
            otherActivityWeight,
            noteWeight,
            taskWeight,
            warmMinimumRawWeight
        );
    }

    /** Returns the model used for every live and historical warmth calculation. */
    public static RelationshipWarmthModel current() {
        return CURRENT;
    }

    /** Returns the stable identifier attached to every score produced by this model. */
    public String version() {
        return version;
    }

    /** Returns the immutable parameter set bound into MyBatis warmth statements. */
    public SqlParameters sqlParameters() {
        return sqlParameters;
    }

    /** Returns the base weight for an activity type. */
    public double activityWeight(String type) {
        return switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case "meeting" -> meetingWeight;
            case "call" -> callWeight;
            case "email" -> emailWeight;
            default -> otherActivityWeight;
        };
    }

    /** Returns the base weight for a workspace-visible note. */
    public double noteWeight() {
        return noteWeight;
    }

    /** Returns the base weight for a task. */
    public double taskWeight() {
        return taskWeight;
    }

    /** Returns the age in model days, clamped so future timestamps have zero age. */
    public double ageDays(long referenceEpochMillis, long touchEpochMillis) {
        return Math.max(0.0, (referenceEpochMillis - touchEpochMillis) / (double) millisecondsPerDay);
    }

    /** Returns one touch's decayed contribution at the supplied age. */
    public double decayedContribution(double weight, double ageDays) {
        return weight * Math.pow(decayBase, -ageDays / halfLifeDays);
    }

    /** Returns whether an age belongs to the recent trend window. */
    public boolean isRecent(double ageDays) {
        return ageDays <= recentWindowDays;
    }

    /** Returns whether an age belongs to the prior trend window. */
    public boolean isPrior(double ageDays) {
        return ageDays > recentWindowDays && ageDays <= priorWindowDays;
    }

    /** Squashes a raw decayed weight into the model's rounded 0–100 score. */
    public int score(double rawWeight) {
        int score = (int) Math.round(100.0 * (1.0 - Math.pow(decayBase, -rawWeight / saturation)));
        return Math.max(0, Math.min(100, score));
    }

    /** Classifies a rounded score into its public warmth band. */
    public String band(int score) {
        if (score >= hotMinimumScore) {
            return "hot";
        }
        if (score >= warmMinimumScore) {
            return "warm";
        }
        if (score >= coolMinimumScore) {
            return "cool";
        }
        return "cold";
    }

    /** Classifies recent and prior base weight into the public warmth trend. */
    public String trend(double recentWeight, double priorWeight, long daysSinceTouch) {
        if (priorWeight >= coolingPriorMinimum
                && recentWeight < priorWeight * coolingRecentRatio
                && daysSinceTouch >= recentWindowDays) {
            return "cooling";
        }
        if (recentWeight > priorWeight) {
            return "rising";
        }
        return "steady";
    }

    /** Returns whole elapsed model days between two epoch-millisecond timestamps. */
    public long wholeDaysSince(long referenceEpochMillis, long touchEpochMillis) {
        return (referenceEpochMillis - touchEpochMillis) / millisecondsPerDay;
    }

    /** Returns an epoch-millisecond timestamp after applying a fractional model-day interval. */
    public long plusDays(long referenceEpochMillis, double days) {
        return referenceEpochMillis + Math.round(days * millisecondsPerDay);
    }

    /** Returns the predicted decay interval to the cold threshold when one exists. */
    public OptionalDouble daysToCold(double rawWeight) {
        if (rawWeight <= coldRawWeight) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(halfLifeDays * log2(rawWeight / coldRawWeight));
    }

    /** Parameters that MyBatis binds into every SQL implementation of the model. */
    public record SqlParameters(
        double decayBase,
        double halfLifeDays,
        double microsecondsPerDay,
        int recentWindowDays,
        int priorWindowDays,
        double meetingWeight,
        double callWeight,
        double emailWeight,
        double otherActivityWeight,
        double noteWeight,
        double taskWeight,
        double warmMinimumRawWeight
    ) {}

    private double rawWeightForExactScore(double score) {
        return -saturation * log2(1.0 - score / 100.0);
    }

    private double rawWeightForRoundedScore(int score) {
        double boundary = rawWeightForExactScore(score - 0.5);
        while (score(Math.nextDown(boundary)) >= score) {
            boundary = Math.nextDown(boundary);
        }
        while (score(boundary) < score) {
            boundary = Math.nextUp(boundary);
        }
        return boundary;
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(decayBase);
    }
}
