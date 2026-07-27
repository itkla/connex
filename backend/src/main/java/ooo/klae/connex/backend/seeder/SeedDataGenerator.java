package ooo.klae.connex.backend.seeder;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealStageHistory;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEmployment;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;

/**
 * Builds realistic CRM beans from stable logical coordinates without touching persistence.
 *
 * <p>Every seeded account uses the precomputed BCrypt hash
 * {@code $2a$10$hrxeTps4XcNryIJjj35hSu36Lm/yQpCuPKEYvqd8d5bUbekQlzh5.}.
 * Its documented plaintext is {@code seeder-password}; no password encoder runs during
 * seeding, so the persisted content remains deterministic.
 */
@Lazy
@Component
public class SeedDataGenerator {

    static final String SEEDED_PASSWORD_HASH =
        "$2a$10$hrxeTps4XcNryIJjj35hSu36Lm/yQpCuPKEYvqd8d5bUbekQlzh5.";

    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final long COMPANY_SALT = 0x434F4D50414E59L;
    private static final long PERSON_SALT = 0x504552534F4E00L;
    private static final long EMPLOYMENT_SALT = 0x454D504C4F5900L;
    private static final long DEAL_SALT = 0x4445414C000000L;
    private static final long ACTIVITY_SALT = 0x4143544956495459L;
    private static final long NOTE_SALT = 0x4E4F544500000000L;
    private static final long TASK_SALT = 0x5441534B00000000L;
    private static final long ATTACHMENT_SALT = 0x4154544143480000L;
    private static final String[] USER_LABELS = {
        "Seeder Owner",
        "Seeder Admin",
        "Jordan Lee",
        "佐藤 美咲",
        "Mika Johnson"
    };
    private static final String[] USER_ROLES = {"owner", "admin", "member", "member", "member"};
    private static final String[] COMPANY_NAMES = {
        "株式会社アオゾラ",
        "アオゾラ商事",
        "Northstar Systems",
        "Élan Data & Co.",
        "合同会社つながり",
        "Kintsugi Labs",
        "東京未来テクノロジー",
        "Pacific Bridge Partners",
        "Ωmega Research 株式会社",
        "Maple & Pine"
    };
    private static final String[] INDUSTRIES = {
        "Software",
        "Manufacturing",
        "Professional Services",
        "金融",
        "Healthcare",
        "物流",
        "Media",
        "Education"
    };
    private static final String[] PERSON_NAMES = {
        "Alice Chen",
        "山田 太郎",
        "ヤマダ タロウ",
        "やまだ たろう",
        "Renée O'Connor",
        "佐藤 美咲",
        "高橋 健",
        "Jordan Lee",
        "李 小龍",
        "Mika Johnson",
        "鈴木 さくら",
        "Noah Williams",
        "🚀 宇宙 太郎",
        "Amélie Dubois",
        "王 芳",
        "伊藤 翔太",
        "Sofia García",
        "中村 葵",
        "Min-jun Park",
        "斎藤 結衣"
    };
    private static final String[] TITLES = {
        "Chief Executive Officer",
        "営業部長",
        "Product Manager",
        "エンジニア",
        "Director of Partnerships",
        "財務責任者",
        "Customer Success Lead",
        "研究開発"
    };
    private static final String[] PIPELINE_NAMES = {"New Business", "更新・拡張"};
    private static final String[][] STAGE_NAMES = {
        {"Discovery", "Qualified", "Proposal", "Closed Won", "Closed Lost"},
        {"相談", "評価", "稟議", "更新成立", "見送り"}
    };
    private static final String[] TAG_NAMES = {
        "VIP",
        "Champion",
        "Decision Maker",
        "At Risk",
        "日本市場",
        "Partner",
        "Renewal",
        "Inbound",
        "Enterprise",
        "Startup",
        "イベント",
        "紹介"
    };
    private static final String[] TAG_COLORS = {
        "#7C3AED",
        "#059669",
        "#2563EB",
        "#DC2626",
        "#DB2777",
        "#0891B2",
        "#D97706",
        "#4F46E5",
        "#111827",
        "#65A30D",
        "#9333EA",
        "#EA580C"
    };

    Organization organization(long workspaceSeed, int workspaceIndex) {
        Organization organization = new Organization();
        organization.setName("Connex Seed Organization " + (workspaceIndex + 1));
        organization.setSlug("seed-org-" + key(workspaceSeed) + "-" + (workspaceIndex + 1));
        return organization;
    }

    Workspace workspace(long workspaceSeed, int workspaceIndex, int orgId) {
        Workspace workspace = new Workspace();
        workspace.setOrgId(orgId);
        workspace.setName("Connex Seed Workspace " + (workspaceIndex + 1));
        workspace.setSlug("seed-workspace-" + key(workspaceSeed) + "-" + (workspaceIndex + 1));
        return workspace;
    }

    List<User> users(long workspaceSeed, int workspaceIndex) {
        List<User> users = new ArrayList<>(USER_LABELS.length);
        for (int index = 0; index < USER_LABELS.length; index++) {
            User user = new User();
            String identity = key(workspaceSeed) + "-w" + (workspaceIndex + 1) + "-u" + (index + 1);
            user.setUsername("seed-" + identity);
            user.setDisplayName(USER_LABELS[index]);
            user.setEmail(identity + "@users.seed.invalid");
            user.setEmailVerified(true);
            user.setPasswordHash(SEEDED_PASSWORD_HASH);
            user.setTimezone(index == 3 ? "Asia/Tokyo" : "UTC");
            user.setLocale(index == 3 ? "ja" : "en");
            users.add(user);
        }
        return users;
    }

    String userRole(int index) {
        return USER_ROLES[index];
    }

    Pipeline pipeline(int workspaceId, int index) {
        Pipeline pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspaceId);
        pipeline.setName(PIPELINE_NAMES[index]);
        return pipeline;
    }

    Stage stage(int workspaceId, Pipeline pipeline, int pipelineIndex, int position) {
        Stage stage = new Stage();
        stage.setWorkspaceId(workspaceId);
        stage.setPipeline(pipeline);
        stage.setName(STAGE_NAMES[pipelineIndex][position]);
        stage.setPosition(position);
        stage.setSuccess(position == 3);
        stage.setFailure(position == 4);
        return stage;
    }

    Tag tag(int workspaceId, int index) {
        Tag tag = new Tag();
        tag.setWorkspaceId(workspaceId);
        tag.setName(TAG_NAMES[index]);
        tag.setColor(TAG_COLORS[index]);
        return tag;
    }

    Company company(
            int workspaceId,
            long workspaceSeed,
            int workspaceIndex,
            int index,
            List<User> users,
            LocalDate anchorDate) {
        Company company = new Company();
        company.setWorkspaceId(workspaceId);
        company.setOwnerId(index % 7 == 0 ? null : users.get(
            DeterministicSeederRandom.bounded(workspaceSeed, COMPANY_SALT, index, 0, users.size())).getId());
        company.setName(indexedName(COMPANY_NAMES, index, "拠点"));
        company.setWebsite(index == 0 || index % 19 == 0
            ? null
            : "https://company-" + (index + 1) + "-w" + (workspaceIndex + 1) + ".seed.invalid");
        company.setIndustry(INDUSTRIES[
            DeterministicSeederRandom.bounded(workspaceSeed, COMPANY_SALT, index, 1, INDUSTRIES.length)]);
        company.setPhone(index % 11 == 0 ? "" : "+81-3-" + fourDigits(index * 37 + 1200));
        company.setAddress(index % 9 == 0
            ? "東京都千代田区1-" + (index + 1) + " 🗼"
            : (100 + index) + " Seed Avenue");
        company.setLogoUrl(index % 5 == 0
            ? null
            : "https://assets.seed.invalid/company/" + (workspaceIndex + 1) + "/" + (index + 1) + ".svg");
        company.setCreatedAt(timestamp(
            anchorDate,
            DeterministicSeederRandom.bounded(workspaceSeed, COMPANY_SALT, index, 2, 548),
            600
        ));
        return company;
    }

    Person person(
            int workspaceId,
            long workspaceSeed,
            int workspaceIndex,
            int index,
            List<User> users,
            List<Company> companies,
            LocalDate anchorDate) {
        Person person = new Person();
        person.setWorkspaceId(workspaceId);
        person.setOwnerId(index % 13 == 0 ? null : users.get(
            DeterministicSeederRandom.bounded(workspaceSeed, PERSON_SALT, index, 0, users.size())).getId());
        person.setName(indexedName(PERSON_NAMES, index, "組"));
        person.setEmail(email(workspaceSeed, workspaceIndex, index, companies));
        person.setPhone(index == 0 || index % 17 == 0
            ? ""
            : index % 9 == 0 ? null : "+81-90-" + fourDigits(2000 + index * 17));
        if (index % 7 != 4) {
            person.setCompany(companies.get(
                DeterministicSeederRandom.bounded(workspaceSeed, PERSON_SALT, index, 1, companies.size())));
        }
        person.setTitle(TITLES[
            DeterministicSeederRandom.bounded(workspaceSeed, PERSON_SALT, index, 2, TITLES.length)]);
        person.setImageUrl(index % 6 == 0
            ? null
            : "https://assets.seed.invalid/person/" + (workspaceIndex + 1) + "/" + (index + 1) + ".webp");
        person.setCreatedAt(timestamp(
            anchorDate,
            DeterministicSeederRandom.bounded(workspaceSeed, PERSON_SALT, index, 3, 548),
            660
        ));
        return person;
    }

    List<PersonEmployment> employments(
            int workspaceId,
            long workspaceSeed,
            int index,
            Person person,
            List<Company> companies,
            LocalDate anchorDate) {
        if (person.getCompany() == null) {
            return List.of();
        }

        int currentBack = 90 + DeterministicSeederRandom.bounded(
            workspaceSeed, EMPLOYMENT_SALT, index, 0, 1_100);
        PersonEmployment current = employment(
            workspaceId,
            person,
            person.getCompany(),
            person.getTitle(),
            timestamp(anchorDate, currentBack, 540),
            null
        );
        if (index % 10 != 3 || companies.size() < 2) {
            return List.of(current);
        }

        int currentCompanyIndex = companies.indexOf(person.getCompany());
        Company historicalCompany = companies.get((currentCompanyIndex + 1
            + DeterministicSeederRandom.bounded(
                workspaceSeed, EMPLOYMENT_SALT, index, 1, companies.size() - 1)) % companies.size());
        String endedAt = timestamp(anchorDate, currentBack + 1, 540);
        String startedAt = timestamp(
            anchorDate,
            currentBack + 365 + DeterministicSeederRandom.bounded(
                workspaceSeed, EMPLOYMENT_SALT, index, 2, 1_200),
            540
        );
        PersonEmployment historical = employment(
            workspaceId,
            person,
            historicalCompany,
            TITLES[(index + 3) % TITLES.length],
            startedAt,
            endedAt
        );
        return List.of(historical, current);
    }

    Deal deal(
            int workspaceId,
            long workspaceSeed,
            int index,
            List<User> users,
            List<Company> companies,
            List<PipelineSeed> pipelines,
            LocalDate anchorDate) {
        Deal deal = new Deal();
        int pipelineIndex =
            DeterministicSeederRandom.bounded(workspaceSeed, DEAL_SALT, index, 0, pipelines.size());
        PipelineSeed pipeline = pipelines.get(pipelineIndex);
        int outcome = index % 10;
        int stagePosition = outcome < 2
            ? 3
            : outcome == 2
                ? 4
                : DeterministicSeederRandom.bounded(workspaceSeed, DEAL_SALT, index, 1, 3);
        boolean usd = index % 4 == 0;
        double value = usd
            ? 5_000.0 + 2_500.0 * DeterministicSeederRandom.bounded(
                workspaceSeed, DEAL_SALT, index, 2, 80)
            : 500_000.0 + 250_000.0 * DeterministicSeederRandom.bounded(
                workspaceSeed, DEAL_SALT, index, 2, 120);
        int createdBack = 20 + DeterministicSeederRandom.bounded(
            workspaceSeed, DEAL_SALT, index, 3, 528);

        deal.setWorkspaceId(workspaceId);
        deal.setOwnerId(index % 11 == 0 ? null : users.get(
            DeterministicSeederRandom.bounded(workspaceSeed, DEAL_SALT, index, 4, users.size())).getId());
        deal.setName((pipelineIndex == 0 ? "Opportunity" : "更新案件") + " "
            + String.format(Locale.ROOT, "%05d", index + 1));
        deal.setValue(value);
        deal.setActualValue(outcome < 2
            ? value * (85 + DeterministicSeederRandom.bounded(
                workspaceSeed, DEAL_SALT, index, 5, 31)) / 100.0
            : 0.0);
        deal.setCurrency(usd ? "USD" : "JPY");
        deal.setPipelineId(pipeline.pipeline().getId());
        deal.setStageId(pipeline.stages().get(stagePosition).getId());
        deal.setCompanyId(index % 9 == 0 ? null : companies.get(
            DeterministicSeederRandom.bounded(workspaceSeed, DEAL_SALT, index, 6, companies.size())).getId());
        deal.setCreatedAt(timestamp(anchorDate, createdBack, 570));

        if (outcome <= 2) {
            int closeBack = DeterministicSeederRandom.bounded(
                workspaceSeed, DEAL_SALT, index, 7, createdBack);
            LocalDate closeDate = anchorDate.minusDays(closeBack);
            deal.setExpectedCloseDate(closeDate.toString());
            deal.setClosedAt(timestamp(anchorDate, closeBack, 900));
            deal.setWon(outcome < 2);
            deal.setClosedReason(outcome < 2
                ? "Agreement signed after stakeholder review"
                : "Budget deferred to a later planning cycle");
        } else {
            int expectedOffset = DeterministicSeederRandom.bounded(
                workspaceSeed, DEAL_SALT, index, 8, 181) - 60;
            deal.setExpectedCloseDate(anchorDate.plusDays(expectedOffset).toString());
            deal.setWon(null);
        }
        return deal;
    }

    List<DealStageHistory> dealStageHistory(
            int workspaceId,
            long workspaceSeed,
            int index,
            Deal deal,
            PipelineSeed pipeline,
            LocalDate anchorDate) {
        int currentPosition = stagePosition(pipeline, deal.getStageId());
        List<Integer> reachedPositions = new ArrayList<>();
        if (currentPosition == 4) {
            int prior = 1 + DeterministicSeederRandom.bounded(
                workspaceSeed, DEAL_SALT, index, 9, 3);
            for (int position = 0; position < prior; position++) {
                reachedPositions.add(position);
            }
            reachedPositions.add(4);
        } else {
            for (int position = 0; position <= currentPosition; position++) {
                reachedPositions.add(position);
            }
        }

        LocalDateTime start = LocalDateTime.parse(deal.getCreatedAt(), MYSQL_DATETIME);
        LocalDateTime end = deal.getClosedAt() == null
            ? anchorDate.atTime(12, 0)
            : LocalDateTime.parse(deal.getClosedAt(), MYSQL_DATETIME);
        long totalSeconds = Math.max(Duration.between(start, end).getSeconds(), reachedPositions.size());
        List<DealStageHistory> history = new ArrayList<>(reachedPositions.size());
        for (int sequence = 0; sequence < reachedPositions.size(); sequence++) {
            int position = reachedPositions.get(sequence);
            Stage stage = pipeline.stages().get(position);
            DealStageHistory entry = new DealStageHistory();
            entry.setWorkspaceId(workspaceId);
            entry.setDealId(deal.getId());
            entry.setStageId(stage.getId());
            entry.setStageName(stage.getName());
            entry.setAchievedAt(start.plusSeconds(
                totalSeconds * (sequence + 1L) / reachedPositions.size()).format(MYSQL_DATETIME));
            entry.setConversionEligible(true);
            history.add(entry);
        }
        return history;
    }

    List<Integer> dealPersonIndexes(long workspaceSeed, int dealIndex, int personCount) {
        int count = 1 + DeterministicSeederRandom.bounded(
            workspaceSeed, DEAL_SALT, dealIndex, 10, Math.min(3, personCount));
        int first = DeterministicSeederRandom.bounded(
            workspaceSeed, DEAL_SALT, dealIndex, 11, personCount);
        List<Integer> indexes = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            indexes.add((first + offset * 17) % personCount);
        }
        return indexes;
    }

    Activity activity(
            int workspaceId,
            long workspaceSeed,
            int index,
            List<User> users,
            List<Person> persons,
            List<Deal> deals,
            LocalDate anchorDate) {
        InteractionTarget target = interactionTarget(workspaceSeed, ACTIVITY_SALT, index, persons.size());
        Person person = persons.get(target.personIndex());
        Activity activity = new Activity();
        activity.setWorkspaceId(workspaceId);
        activity.setType(activityType(workspaceSeed, index, target.cohort()));
        activity.setSubject(activitySubject(activity.getType(), person.getName(), index));
        activity.setNotes(index % 8 == 0 ? "Follow-up captured from a deterministic seeder interaction." : null);
        activity.setPerson(person);
        if (index % 3 == 0) {
            activity.setDeal(deals.get(
                DeterministicSeederRandom.bounded(workspaceSeed, ACTIVITY_SALT, index, 7, deals.size())));
        }
        activity.setCreatedBy(users.get(
            DeterministicSeederRandom.bounded(workspaceSeed, ACTIVITY_SALT, index, 8, users.size())));
        activity.setTimestamp(interactionTimestamp(
            workspaceSeed, ACTIVITY_SALT, index, target.cohort(), anchorDate));
        return activity;
    }

    Note note(
            int workspaceId,
            long workspaceSeed,
            int index,
            List<User> users,
            List<Person> persons,
            List<Deal> deals,
            LocalDate anchorDate) {
        InteractionTarget target = interactionTarget(workspaceSeed, NOTE_SALT, index, persons.size());
        Note note = new Note();
        note.setWorkspaceId(workspaceId);
        note.setTitle(index % 4 == 0 ? "Relationship context " + (index + 1) : null);
        note.setContent("Workspace-visible seed note " + (index + 1)
            + " for " + persons.get(target.personIndex()).getName() + ".");
        note.setVisibility("workspace");
        note.setAuthor(users.get(
            DeterministicSeederRandom.bounded(workspaceSeed, NOTE_SALT, index, 7, users.size())));
        note.setPerson(persons.get(target.personIndex()));
        if (index % 5 == 0) {
            note.setDeal(deals.get(
                DeterministicSeederRandom.bounded(workspaceSeed, NOTE_SALT, index, 8, deals.size())));
        }
        note.setCreatedAt(interactionTimestamp(
            workspaceSeed, NOTE_SALT, index, target.cohort(), anchorDate));
        return note;
    }

    Task task(
            int workspaceId,
            long workspaceSeed,
            int index,
            List<User> users,
            List<Person> persons,
            List<Deal> deals,
            LocalDate anchorDate) {
        InteractionTarget target = interactionTarget(workspaceSeed, TASK_SALT, index, persons.size());
        String status = index % 5 == 0 ? "done" : index % 3 == 0 ? "in_progress" : "todo";
        Task task = new Task();
        task.setWorkspaceId(workspaceId);
        task.setDescription("Seed follow-up " + (index + 1)
            + " with " + persons.get(target.personIndex()).getName());
        task.setCompleted("done".equals(status));
        task.setStatus(status);
        task.setDueDate(anchorDate.plusDays(
            DeterministicSeederRandom.bounded(workspaceSeed, TASK_SALT, index, 7, 121) - 45L).toString());
        task.setAssignedTo(users.get(
            DeterministicSeederRandom.bounded(workspaceSeed, TASK_SALT, index, 8, users.size())));
        task.setPerson(persons.get(target.personIndex()));
        if (index % 4 == 0) {
            task.setDeal(deals.get(
                DeterministicSeederRandom.bounded(workspaceSeed, TASK_SALT, index, 9, deals.size())));
        }
        task.setCreatedAt(interactionTimestamp(
            workspaceSeed, TASK_SALT, index, target.cohort(), anchorDate));
        return task;
    }

    Attachment attachment(
            int workspaceId,
            long workspaceSeed,
            int workspaceIndex,
            int index,
            List<User> users,
            List<Company> companies,
            List<Person> persons,
            List<Deal> deals) {
        Attachment attachment = new Attachment();
        attachment.setWorkspaceId(workspaceId);
        int entityKind = index % 3;
        if (entityKind == 0) {
            Person person = persons.get(
                DeterministicSeederRandom.bounded(
                    workspaceSeed, ATTACHMENT_SALT, index, 0, persons.size()));
            attachment.setEntityType("person");
            attachment.setEntityId(person.getId());
        } else if (entityKind == 1) {
            Company company = companies.get(
                DeterministicSeederRandom.bounded(
                    workspaceSeed, ATTACHMENT_SALT, index, 0, companies.size()));
            attachment.setEntityType("company");
            attachment.setEntityId(company.getId());
        } else {
            Deal deal = deals.get(
                DeterministicSeederRandom.bounded(
                    workspaceSeed, ATTACHMENT_SALT, index, 0, deals.size()));
            attachment.setEntityType("deal");
            attachment.setEntityId(deal.getId());
        }
        String extension = index % 4 == 0 ? "png" : "pdf";
        attachment.setFileName("seed-document-" + String.format(Locale.ROOT, "%05d", index + 1) + "." + extension);
        attachment.setUrl("https://seed.invalid/attachments/w" + (workspaceIndex + 1)
            + "/" + (index + 1) + "/" + attachment.getFileName());
        attachment.setContentType("png".equals(extension) ? "image/png" : "application/pdf");
        attachment.setSize(8_192L + DeterministicSeederRandom.bounded(
            workspaceSeed, ATTACHMENT_SALT, index, 1, 2_000_000));
        attachment.setUploadedBy(index % 13 == 0 ? null : users.get(
            DeterministicSeederRandom.bounded(
                workspaceSeed, ATTACHMENT_SALT, index, 2, users.size())));
        return attachment;
    }

    List<Integer> tagIndexes(long workspaceSeed, long entitySalt, int index) {
        int first = DeterministicSeederRandom.bounded(
            workspaceSeed, entitySalt, index, 20, TAG_NAMES.length);
        int second = (first + 1 + DeterministicSeederRandom.bounded(
            workspaceSeed, entitySalt, index, 21, TAG_NAMES.length - 1)) % TAG_NAMES.length;
        return index % 4 == 0 ? List.of(first) : List.of(first, second);
    }

    long companySalt() {
        return COMPANY_SALT;
    }

    long personSalt() {
        return PERSON_SALT;
    }

    long dealSalt() {
        return DEAL_SALT;
    }

    private static PersonEmployment employment(
            int workspaceId,
            Person person,
            Company company,
            String title,
            String startedAt,
            String endedAt) {
        PersonEmployment employment = new PersonEmployment();
        employment.setWorkspaceId(workspaceId);
        employment.setPersonId(person.getId());
        employment.setCompanyId(company.getId());
        employment.setCompanyName(company.getName());
        employment.setTitle(title);
        employment.setStartedAt(startedAt);
        employment.setEndedAt(endedAt);
        return employment;
    }

    private static String email(
            long workspaceSeed,
            int workspaceIndex,
            int index,
            List<Company> companies) {
        if (index == 4 || index % 11 == 4) {
            return null;
        }
        if (index == 1) {
            return "taro.yamada@aozora.seed.invalid";
        }
        if (index == 2) {
            return "taro.yamada-katakana@aozora.seed.invalid";
        }
        if (index == 3) {
            return "taro.yamada-hiragana@aozora.seed.invalid";
        }
        int companyIndex = DeterministicSeederRandom.bounded(
            workspaceSeed, PERSON_SALT, index, 4, companies.size());
        return "contact-" + String.format(Locale.ROOT, "%05d", index + 1)
            + "-w" + (workspaceIndex + 1)
            + "@company-" + (companyIndex + 1) + ".seed.invalid";
    }

    private static String indexedName(String[] values, int index, String suffix) {
        String base = values[index % values.length];
        int cycle = index / values.length;
        if (cycle == 0) {
            return base;
        }
        return base + " " + suffix + String.format(Locale.ROOT, "%03d", cycle + 1);
    }

    private static int stagePosition(PipelineSeed pipeline, int stageId) {
        for (int position = 0; position < pipeline.stages().size(); position++) {
            if (pipeline.stages().get(position).getId() == stageId) {
                return position;
            }
        }
        throw new IllegalStateException("Seeded deal stage does not belong to its pipeline");
    }

    private static InteractionTarget interactionTarget(
            long workspaceSeed,
            long salt,
            int index,
            int personCount) {
        int cohortSelector = index % 10;
        int hotEnd = Math.max(1, personCount / 10);
        int warmEnd = Math.max(hotEnd + 1, personCount * 3 / 10);
        int coolEnd = Math.max(warmEnd + 1, personCount * 6 / 10);
        int activeEnd = Math.max(coolEnd + 1, personCount * 9 / 10);
        int start;
        int end;
        int cohort;
        if (cohortSelector < 4) {
            start = 0;
            end = hotEnd;
            cohort = 0;
        } else if (cohortSelector < 7) {
            start = hotEnd;
            end = warmEnd;
            cohort = 1;
        } else if (cohortSelector < 9) {
            start = warmEnd;
            end = coolEnd;
            cohort = 2;
        } else {
            start = coolEnd;
            end = activeEnd;
            cohort = 3;
        }
        end = Math.min(end, personCount);
        start = Math.min(start, end - 1);
        int personIndex = start + DeterministicSeederRandom.bounded(
            workspaceSeed, salt, index, 0, end - start);
        return new InteractionTarget(personIndex, cohort);
    }

    private static String activityType(long workspaceSeed, int index, int cohort) {
        int selector = DeterministicSeederRandom.bounded(
            workspaceSeed, ACTIVITY_SALT, index, 5, 10);
        if (cohort == 0) {
            return selector < 6 ? "meeting" : "call";
        }
        if (cohort == 1) {
            return selector < 3 ? "meeting" : selector < 7 ? "call" : "email";
        }
        if (cohort == 2) {
            return selector < 3 ? "call" : "email";
        }
        return selector == 0 ? "call" : "email";
    }

    private static String activitySubject(String type, String personName, int index) {
        return switch (type) {
            case "meeting" -> "Relationship review with " + personName;
            case "call" -> "Follow-up call with " + personName;
            default -> "Email touchpoint " + (index + 1) + " for " + personName;
        };
    }

    private static String interactionTimestamp(
            long workspaceSeed,
            long salt,
            int index,
            int cohort,
            LocalDate anchorDate) {
        int daysBack = switch (cohort) {
            case 0 -> DeterministicSeederRandom.bounded(workspaceSeed, salt, index, 1, 21);
            case 1 -> 14 + DeterministicSeederRandom.bounded(workspaceSeed, salt, index, 1, 107);
            case 2 -> 90 + DeterministicSeederRandom.bounded(workspaceSeed, salt, index, 1, 181);
            default -> 271 + DeterministicSeederRandom.bounded(workspaceSeed, salt, index, 1, 277);
        };
        int minute = DeterministicSeederRandom.bounded(workspaceSeed, salt, index, 2, 1_440);
        return timestamp(anchorDate, daysBack, minute);
    }

    private static String timestamp(LocalDate anchorDate, int daysBack, int minuteOfDay) {
        return anchorDate.minusDays(daysBack)
            .atStartOfDay()
            .plusMinutes(minuteOfDay)
            .format(MYSQL_DATETIME);
    }

    private static String fourDigits(int value) {
        return String.format(Locale.ROOT, "%04d", Math.floorMod(value, 10_000));
    }

    private static String key(long workspaceSeed) {
        return Long.toUnsignedString(workspaceSeed, 36);
    }

    record PipelineSeed(Pipeline pipeline, List<Stage> stages) {
    }

    private record InteractionTarget(int personIndex, int cohort) {
    }
}
