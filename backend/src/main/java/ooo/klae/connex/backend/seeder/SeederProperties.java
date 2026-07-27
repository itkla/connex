package ooo.klae.connex.backend.seeder;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import lombok.Data;

/**
 * Configuration for deterministic tenant fixture generation.
 *
 * <p>The default seed is {@code 853}. An omitted anchor date resolves once from the
 * application UTC clock when the run begins.
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "connex.seeder")
public class SeederProperties {

    private boolean enabled;

    @NotNull
    private Profile profile = Profile.SMALL;

    private long seed = 853L;

    @Min(1)
    @Max(100)
    private int workspaces = 1;

    private LocalDate anchorDate;

    private boolean allowRemoteHost;

    /** Supported deterministic fixture sizes. */
    public enum Profile {
        SMALL(50, 10, 20, 200, 50, 30, 10),
        VOLUME(5_000, 1_000, 2_000, 20_000, 5_000, 3_000, 1_000);

        private final int persons;
        private final int companies;
        private final int deals;
        private final int activities;
        private final int notes;
        private final int tasks;
        private final int attachments;

        Profile(
                int persons,
                int companies,
                int deals,
                int activities,
                int notes,
                int tasks,
                int attachments) {
            this.persons = persons;
            this.companies = companies;
            this.deals = deals;
            this.activities = activities;
            this.notes = notes;
            this.tasks = tasks;
            this.attachments = attachments;
        }

        public int persons() {
            return persons;
        }

        public int companies() {
            return companies;
        }

        public int deals() {
            return deals;
        }

        public int activities() {
            return activities;
        }

        public int notes() {
            return notes;
        }

        public int tasks() {
            return tasks;
        }

        public int attachments() {
            return attachments;
        }
    }
}
