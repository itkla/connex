-- ============================================================================
-- Connex CRM : MySQL Schema
-- This file is the source of truth, and should be defined first before any Java entity classes or MyBatis mappers. DO NOT FORGET TO CHANGE THIS BAD BOY
-- ============================================================================


SET FOREIGN_KEY_CHECKS = 0;

-- DROP DATABASE IF EXISTS connexdb; is set due to testing. I will remove it later because application.yml specifies recreating the database on every run
DROP DATABASE IF EXISTS connexdb;
CREATE DATABASE connexdb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE connexdb;

DROP TABLE IF EXISTS deal_tag;
DROP TABLE IF EXISTS company_tag;
DROP TABLE IF EXISTS person_tag;
DROP TABLE IF EXISTS deal_person;
DROP TABLE IF EXISTS note;
DROP TABLE IF EXISTS task;
DROP TABLE IF EXISTS activity;
DROP TABLE IF EXISTS deal;
DROP TABLE IF EXISTS stage;
DROP TABLE IF EXISTS pipeline;
DROP TABLE IF EXISTS person;
DROP TABLE IF EXISTS tag;
DROP TABLE IF EXISTS company;
DROP TABLE IF EXISTS app_user;

SET FOREIGN_KEY_CHECKS = 1;


-- ----------------------------------------------------------------------------
-- app_user :  Connex account holder. Mapped to User bean. NOT Person (contacts)
-- ----------------------------------------------------------------------------
CREATE TABLE app_user (
    id              INT AUTO_INCREMENT PRIMARY KEY COMMENT 'User ID',
    username        VARCHAR(64) NOT NULL UNIQUE COMMENT 'Username',
    display_name    VARCHAR(128) NOT NULL COMMENT 'Display name for UI',
    email           VARCHAR(255) NOT NULL UNIQUE COMMENT 'Email address',
    password_hash   VARCHAR(255) COMMENT 'Hashed password',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp'
) DEFAULT CHARSET=utf8mb4 COMMENT='Application users';


-- ----------------------------------------------------------------------------
-- company : a business entity. A person (contact) can be linked to a company, and a deal can be associated with a company.
-- ----------------------------------------------------------------------------
CREATE TABLE company (
    id          INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Company ID',
    name        VARCHAR(255) NOT NULL COMMENT 'Company name',
    website     VARCHAR(255) COMMENT 'Company website',
    industry    VARCHAR(128) COMMENT 'Industry',
    phone       VARCHAR(64) COMMENT 'Phone number',
    address     VARCHAR(512) COMMENT 'Address',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp'
) DEFAULT CHARSET=utf8mb4 COMMENT='Companies';


-- ----------------------------------------------------------------------------
-- pipeline : a sales process template. Contains stages.
-- ----------------------------------------------------------------------------
CREATE TABLE pipeline (
    id          INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Pipeline ID',
    name        VARCHAR(128) NOT NULL COMMENT 'Pipeline name',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp'
) DEFAULT CHARSET=utf8mb4 COMMENT='Pipelines';


-- ----------------------------------------------------------------------------
-- tag : a category or label for organizing entities.
-- ----------------------------------------------------------------------------
CREATE TABLE tag (
    id      INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Tag ID',
    name    VARCHAR(64) NOT NULL UNIQUE COMMENT 'Tag name',
    color   VARCHAR(9) COMMENT 'Hex color code'
) DEFAULT CHARSET=utf8mb4 COMMENT='Tags';


-- ----------------------------------------------------------------------------
-- person : CRM contact. Belongs to a company (optional).
-- ----------------------------------------------------------------------------
CREATE TABLE person (
    id          INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Person ID',
    name        VARCHAR(255) NOT NULL COMMENT 'Person name',
    email       VARCHAR(255) COMMENT 'Person email',
    phone       VARCHAR(64) COMMENT 'Person phone',
    company_id  INT COMMENT 'Company ID',
    title       VARCHAR(128) COMMENT 'Person title',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    CONSTRAINT fk_person_company FOREIGN KEY (company_id) REFERENCES company(id) ON DELETE SET NULL,
    INDEX idx_person_company (company_id),
    INDEX idx_person_email   (email)
) DEFAULT CHARSET=utf8mb4 COMMENT='Persons';


-- ----------------------------------------------------------------------------
-- stage : a step within a pipeline.
-- ----------------------------------------------------------------------------
CREATE TABLE stage (
    id          INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Stage ID',
    name        VARCHAR(128) NOT NULL COMMENT 'Stage name',
    pipeline_id INT NOT NULL COMMENT 'Pipeline ID',
    position    INT NOT NULL COMMENT 'Stage position',
    CONSTRAINT fk_stage_pipeline FOREIGN KEY (pipeline_id) REFERENCES pipeline(id) ON DELETE CASCADE,
    INDEX idx_stage_pipeline (pipeline_id),
    UNIQUE KEY uq_stage_pipeline_position (pipeline_id, position)
) DEFAULT CHARSET=utf8mb4 COMMENT='Stages';


-- ----------------------------------------------------------------------------
-- deal : a sales opportunity. Lives in a stage of a pipeline; optionally
-- linked to a company.
-- ----------------------------------------------------------------------------
CREATE TABLE deal (
    id                  INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Deal ID',
    name                VARCHAR(255) NOT NULL COMMENT 'Deal name',
    value               DECIMAL(15, 2) NOT NULL DEFAULT 0,
    currency            VARCHAR(8) NOT NULL DEFAULT 'USD',
    pipeline_id         INT NOT NULL COMMENT 'Pipeline ID',
    stage_id            INT NOT NULL COMMENT 'Stage ID',
    company_id          INT COMMENT 'Company ID',
    expected_close_date DATETIME COMMENT 'Expected close date',
    closed_at           DATETIME COMMENT 'Close date',
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    CONSTRAINT fk_deal_pipeline FOREIGN KEY (pipeline_id) REFERENCES pipeline(id) ON DELETE RESTRICT,
    CONSTRAINT fk_deal_stage    FOREIGN KEY (stage_id)    REFERENCES stage(id)    ON DELETE RESTRICT,
    CONSTRAINT fk_deal_company  FOREIGN KEY (company_id)  REFERENCES company(id)  ON DELETE SET NULL,
    INDEX idx_deal_pipeline (pipeline_id),
    INDEX idx_deal_stage    (stage_id),
    INDEX idx_deal_company  (company_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Deals';


-- ----------------------------------------------------------------------------
-- activity : a logged interaction (call, email, meeting). Linked to a person
-- and/or a deal; always created by a user.
-- ----------------------------------------------------------------------------
CREATE TABLE activity (
    id              INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Activity ID',
    type            VARCHAR(32)  NOT NULL COMMENT 'Activity type',            -- "call" | "email" | "meeting" | etc.
    subject         VARCHAR(255) NOT NULL COMMENT 'Activity subject',
    notes           TEXT COMMENT 'Activity notes',
    person_id       INT COMMENT 'Person ID',
    deal_id         INT COMMENT 'Deal ID',
    created_by_id   INT NOT NULL COMMENT 'Created by User ID',
    timestamp       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Activity timestamp',
    CONSTRAINT fk_activity_person     FOREIGN KEY (person_id)     REFERENCES person(id)   ON DELETE SET NULL,
    CONSTRAINT fk_activity_deal       FOREIGN KEY (deal_id)       REFERENCES deal(id)     ON DELETE SET NULL,
    CONSTRAINT fk_activity_created_by FOREIGN KEY (created_by_id) REFERENCES app_user(id) ON DELETE RESTRICT,
    INDEX idx_activity_person     (person_id),
    INDEX idx_activity_deal       (deal_id),
    INDEX idx_activity_created_by (created_by_id),
    INDEX idx_activity_timestamp  (timestamp)
) DEFAULT CHARSET=utf8mb4 COMMENT='Activities';


-- ----------------------------------------------------------------------------
-- task : a to-do item assigned to a user, optionally linked to a person/deal.
-- ----------------------------------------------------------------------------
CREATE TABLE task (
    id              INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Task ID',
    description     TEXT NOT NULL COMMENT 'Task description',
    completed       BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Completion status',
    due_date        DATETIME COMMENT 'Due date',
    assigned_to_id  INT NOT NULL COMMENT 'Assigned to User ID',
    person_id       INT COMMENT 'Person ID',
    deal_id         INT COMMENT 'Deal ID',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    CONSTRAINT fk_task_assigned FOREIGN KEY (assigned_to_id) REFERENCES app_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_person   FOREIGN KEY (person_id)      REFERENCES person(id)   ON DELETE SET NULL,
    CONSTRAINT fk_task_deal     FOREIGN KEY (deal_id)        REFERENCES deal(id)     ON DELETE SET NULL,
    INDEX idx_task_assigned  (assigned_to_id),
    INDEX idx_task_person    (person_id),
    INDEX idx_task_deal      (deal_id),
    INDEX idx_task_due_date  (due_date),
    INDEX idx_task_completed (completed)
) DEFAULT CHARSET=utf8mb4 COMMENT='Tasks';


-- ----------------------------------------------------------------------------
-- note : a free-form text entry created by a user, linked to a person and/or deal.
-- ----------------------------------------------------------------------------
CREATE TABLE note (
    id          INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Note ID',
    content     TEXT NOT NULL COMMENT 'Note content',
    author_id   INT NOT NULL COMMENT 'Author User ID',
    person_id   INT COMMENT 'Person ID',
    deal_id     INT COMMENT 'Deal ID',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    CONSTRAINT fk_note_author FOREIGN KEY (author_id) REFERENCES app_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_note_person FOREIGN KEY (person_id) REFERENCES person(id)   ON DELETE SET NULL,
    CONSTRAINT fk_note_deal   FOREIGN KEY (deal_id)   REFERENCES deal(id)     ON DELETE SET NULL,
    INDEX idx_note_author (author_id),
    INDEX idx_note_person (person_id),
    INDEX idx_note_deal   (deal_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Notes';


-- ============================================================================
-- Junction tables
-- ============================================================================

-- deal_person : a deal can involve multiple people (decision makers, champions).
CREATE TABLE deal_person (
    deal_id     INT NOT NULL,
    person_id   INT NOT NULL,
    role        VARCHAR(64),
    PRIMARY KEY (deal_id, person_id),
    CONSTRAINT fk_deal_person_deal   FOREIGN KEY (deal_id)   REFERENCES deal(id)   ON DELETE CASCADE,
    CONSTRAINT fk_deal_person_person FOREIGN KEY (person_id) REFERENCES person(id) ON DELETE CASCADE,
    INDEX idx_deal_person_person (person_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Deal-Person Relationships';

-- person_tag, company_tag, deal_tag - labels for records
CREATE TABLE person_tag (
    person_id INT NOT NULL,
    tag_id    INT NOT NULL,
    PRIMARY KEY (person_id, tag_id),
    CONSTRAINT fk_person_tag_person FOREIGN KEY (person_id) REFERENCES person(id) ON DELETE CASCADE,
    CONSTRAINT fk_person_tag_tag    FOREIGN KEY (tag_id)    REFERENCES tag(id)    ON DELETE CASCADE,
    INDEX idx_person_tag_tag (tag_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Person-Tag Relationships';

CREATE TABLE company_tag (
    company_id INT NOT NULL,
    tag_id     INT NOT NULL,
    PRIMARY KEY (company_id, tag_id),
    CONSTRAINT fk_company_tag_company FOREIGN KEY (company_id) REFERENCES company(id) ON DELETE CASCADE,
    CONSTRAINT fk_company_tag_tag     FOREIGN KEY (tag_id)     REFERENCES tag(id)     ON DELETE CASCADE,
    INDEX idx_company_tag_tag (tag_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Company-Tag Relationships';

CREATE TABLE deal_tag (
    deal_id INT NOT NULL,
    tag_id  INT NOT NULL,
    PRIMARY KEY (deal_id, tag_id),
    CONSTRAINT fk_deal_tag_deal FOREIGN KEY (deal_id) REFERENCES deal(id) ON DELETE CASCADE,
    CONSTRAINT fk_deal_tag_tag  FOREIGN KEY (tag_id)  REFERENCES tag(id)  ON DELETE CASCADE,
    INDEX idx_deal_tag_tag (tag_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Deal-Tag Relationships';
