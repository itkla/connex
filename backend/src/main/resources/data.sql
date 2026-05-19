-- Password hash for every user below is "L3tme!n!".

USE connexdb;

-- app_user
INSERT INTO app_user (id, username, display_name, email, password_hash) VALUES
    (1, 'alice',   'Alice Anderson', 'alice@connex.test',   '$2a$10$fjpXXw/Y3WgsJz7gHEhrJuIi6c660HFpkPz/QlbPT9hPeG1VmAkxm'),
    (2, 'bob',     'Bob Brown',      'bob@connex.test',     '$2a$10$fjpXXw/Y3WgsJz7gHEhrJuIi6c660HFpkPz/QlbPT9hPeG1VmAkxm'),
    (3, 'carol',   'Carol Chen',     'carol@connex.test',   '$2a$10$fjpXXw/Y3WgsJz7gHEhrJuIi6c660HFpkPz/QlbPT9hPeG1VmAkxm'),
    (4, 'dave',    'Dave Davis',     'dave@connex.test',    '$2a$10$fjpXXw/Y3WgsJz7gHEhrJuIi6c660HFpkPz/QlbPT9hPeG1VmAkxm'),
    (5, 'eve',     'Eve Edwards',    'eve@connex.test',     '$2a$10$fjpXXw/Y3WgsJz7gHEhrJuIi6c660HFpkPz/QlbPT9hPeG1VmAkxm');

-- company
INSERT INTO company (id, name, website, industry, phone, address, logo_url) VALUES
    (1, 'Acme Corp',            'https://acme.example.com',     'Manufacturing',     '+1-415-555-0101', '1 Acme Way, San Francisco, CA', null),
    (2, 'Globex Inc',            'https://globex.example.com',   'Technology',        '+1-212-555-0102', '500 Globex Plaza, New York, NY', null),
    (3, 'Initech',               'https://initech.example.com',  'Software',          '+1-512-555-0103', '88 Initech Blvd, Austin, TX', null),
    (4, 'Soylent Corp',          'https://soylent.example.com',  'Food & Beverage',   '+1-310-555-0104', '12 Green St, Los Angeles, CA', null),
    (5, 'Umbrella Corporation',  'https://umbrella.example.com', 'Pharmaceuticals',   '+1-206-555-0105', '7 Raccoon Ave, Seattle, WA', null),
    (6, 'Stark Industries',      'https://stark.example.com',    'Aerospace',         '+1-646-555-0106', '10880 Malibu Pt, Malibu, CA', null),
    (7, 'Wayne Enterprises',     'https://wayne.example.com',    'Conglomerate',      '+1-201-555-0107', '1007 Mountain Dr, Gotham, NJ', null),
    (8, 'Cyberdyne Systems',     'https://cyberdyne.example.com','Robotics',          '+1-408-555-0108', '18144 El Camino Real, Sunnyvale, CA', null);

-- pipeline
INSERT INTO pipeline (id, name) VALUES
    (1, 'Sales Pipeline'),
    (2, 'Renewals Pipeline');

-- stage
INSERT INTO stage (id, name, pipeline_id, position) VALUES
    (1, 'Lead',           1, 1),
    (2, 'Qualified',      1, 2),
    (3, 'Proposal',       1, 3),
    (4, 'Negotiation',    1, 4),
    (5, 'Closed Won',     1, 5),
    (6, 'Closed Lost',    1, 6),
    (7, 'Upcoming',       2, 1),
    (8, 'In Discussion',  2, 2),
    (9, 'Renewed',        2, 3),
    (10, 'Churned',       2, 4);

-- tag
INSERT INTO tag (id, name, color) VALUES
    (1,  'VIP',            '#FFD700'),
    (2,  'Enterprise',     '#1E90FF'),
    (3,  'SMB',            '#32CD32'),
    (4,  'Hot Lead',       '#FF4500'),
    (5,  'Cold',           '#87CEEB'),
    (6,  'Strategic',      '#8A2BE2'),
    (7,  'Decision Maker', '#DC143C'),
    (8,  'Champion',       '#FF69B4'),
    (9,  'Technical',      '#20B2AA'),
    (10, 'Executive',      '#B8860B');

-- person  (mix of with-company and unaffiliated contacts)
INSERT INTO person (id, name, email, phone, company_id, title, image_url) VALUES
    (1,  'Wile E. Coyote',       'wile@acme.example.com',         '+1-415-555-1101', 1,    'Head of R&D', "https://images.unsplash.com/photo-1595211877493-41a4e5f236b3?q=80&w=715&auto=format&fit=crop&ixlib=rb-4.1.0&ixid=M3wxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8fA%3D%3D"),
    (2,  'Road Runner',          'rr@acme.example.com',           '+1-415-555-1102', 1,    'Logistics Lead', null),
    (3,  'Hank Scorpio',         'hank@globex.example.com',       '+1-212-555-1103', 2,    'CEO', null),
    (4,  'Bill Lumbergh',        'bill@initech.example.com',      '+1-512-555-1104', 3,    'VP of Operations', null),
    (5,  'Peter Gibbons',        'peter@initech.example.com',     '+1-512-555-1105', 3,    'Software Engineer', null),
    (6,  'Michael Bolton',       'michael@initech.example.com',   '+1-512-555-1106', 3,    'Software Engineer', null),
    (7,  'Samir Nagheenanajar',  'samir@initech.example.com',     '+1-512-555-1107', 3,    'Software Engineer', null),
    (8,  'Sheev Soylent',        'sheev@soylent.example.com',     '+1-310-555-1108', 4,    'Director of Sourcing', null),
    (9,  'Albert Wesker',        'wesker@umbrella.example.com',   '+1-206-555-1109', 5,    'Chief Scientist', null),
    (10, 'Tony Stark',           'tony@stark.example.com',        '+1-646-555-1110', 6,    'CEO / CTO', null),
    (11, 'Pepper Potts',         'pepper@stark.example.com',      '+1-646-555-1111', 6,    'COO', null),
    (12, 'Lucius Fox',           'lucius@wayne.example.com',      '+1-201-555-1112', 7,    'CEO', null),
    (13, 'Miles Dyson',          'miles@cyberdyne.example.com',   '+1-408-555-1113', 8,    'Director of Special Projects', null),
    (14, 'Independent Imani',    'imani@freelance.test',          '+1-555-000-1114', NULL, 'Independent Consultant', null),
    (15, 'Solo Sven',            'sven@freelance.test',           '+1-555-000-1115', NULL, 'Freelancer', null);

-- deal
INSERT INTO deal (id, name, value, currency, pipeline_id, stage_id, company_id, expected_close_date, closed_at) VALUES
    (1,  'Acme - Anvil Restock Q3',           45000.00,  'USD', 1, 2, 1,    '2026-07-15 00:00:00', NULL),
    (2,  'Globex - Enterprise License',       250000.00, 'USD', 1, 3, 2,    '2026-08-01 00:00:00', NULL),
    (3,  'Initech - TPS Reporting Module',    75000.00,  'USD', 1, 4, 3,    '2026-06-30 00:00:00', NULL),
    (4,  'Soylent - Bulk Supply Agreement',   180000.00, 'USD', 1, 5, 4,    '2026-04-10 00:00:00', '2026-04-08 14:30:00'),
    (5,  'Umbrella - Lab Equipment',          92000.00,  'USD', 1, 6, 5,    '2026-03-15 00:00:00', '2026-03-20 09:00:00'),
    (6,  'Stark - Defense Contract',          1500000.00,'USD', 1, 4, 6,    '2026-09-30 00:00:00', NULL),
    (7,  'Wayne - Security Overhaul',         420000.00, 'USD', 1, 3, 7,    '2026-07-22 00:00:00', NULL),
    (8,  'Cyberdyne - AI Platform Trial',     35000.00,  'USD', 1, 1, 8,    '2026-08-15 00:00:00', NULL),
    (9,  'Freelance - Imani Retainer',        12000.00,  'USD', 1, 2, NULL, '2026-06-01 00:00:00', NULL),
    (10, 'Acme - Annual License Renewal',     60000.00,  'USD', 2, 8, 1,    '2026-06-30 00:00:00', NULL),
    (11, 'Globex - Renewal',                  300000.00, 'USD', 2, 9, 2,    '2026-05-01 00:00:00', '2026-04-28 11:15:00'),
    (12, 'Initech - Renewal',                 80000.00,  'USD', 2, 10, 3,   '2026-04-30 00:00:00', '2026-05-02 16:45:00');

-- activity
INSERT INTO activity (id, type, subject, notes, person_id, deal_id, created_by_id, timestamp) VALUES
    (1,  'call',    'Discovery call with Wile E.',          'Discussed anvil volume needs for Q3.',                  1,    1,    2, '2026-04-01 10:15:00'),
    (2,  'email',   'Pricing follow-up to Acme',            'Sent updated pricing PDF.',                             1,    1,    2, '2026-04-03 09:00:00'),
    (3,  'meeting', 'Onsite with Globex execs',             'Toured Globex HQ, met Hank and team.',                  3,    2,    1, '2026-04-05 14:00:00'),
    (4,  'email',   'TPS report scope clarification',       'Bill wants pivot tables included.',                     4,    3,    3, '2026-04-06 08:45:00'),
    (5,  'call',    'Tech deep-dive with Peter',            'Walked through API integration plan.',                  5,    3,    3, '2026-04-07 11:30:00'),
    (6,  'meeting', 'Soylent contract signing',             'Closed bulk supply deal.',                              8,    4,    1, '2026-04-08 14:30:00'),
    (7,  'call',    'Umbrella post-mortem',                 'Lost to incumbent vendor.',                             9,    5,    4, '2026-03-22 10:00:00'),
    (8,  'meeting', 'Stark defense scoping',                'Reviewed clearance requirements.',                      10,   6,    1, '2026-04-10 16:00:00'),
    (9,  'email',   'Stark - revised proposal v3',          'Pepper requested additional SLA terms.',                11,   6,    1, '2026-04-12 09:20:00'),
    (10, 'meeting', 'Wayne security workshop',              'Lucius walked us through current systems.',             12,   7,    2, '2026-04-15 13:00:00'),
    (11, 'call',    'Cyberdyne trial kickoff',              'Miles will pilot AI platform with 3 engineers.',        13,   8,    5, '2026-04-18 11:00:00'),
    (12, 'email',   'Imani retainer terms',                 'Negotiating monthly hours.',                            14,   9,    3, '2026-04-20 10:00:00'),
    (13, 'call',    'Acme renewal check-in',                'Considering expanding to two more sites.',              2,    10,   2, '2026-04-22 15:30:00'),
    (14, 'meeting', 'Globex renewal celebration',           'Renewed 12-month, room to grow.',                       3,    11,   1, '2026-04-28 11:15:00'),
    (15, 'call',    'Initech churn discussion',             'Budget cuts; renewal not approved.',                    4,    12,   3, '2026-05-02 16:45:00'),
    (16, 'email',   'Generic prospect outreach',            'Cold email to Sven re: freelance partnership.',         15,   NULL, 5, '2026-04-25 08:00:00'),
    (17, 'call',    'Internal sync on Stark deal',          'Strategy huddle, no client present.',                   NULL, 6,    1, '2026-04-11 09:00:00'),
    (18, 'meeting', 'Globex quarterly review',              'Standalone QBR, not tied to a deal.',                   3,    NULL, 1, '2026-04-30 14:00:00'),
    (19, 'email',   'Welcome packet to Cyberdyne',          'Onboarding materials sent.',                            13,   8,    5, '2026-04-19 09:30:00'),
    (20, 'call',    'Champion building - Pepper',           'Cultivating Pepper as internal champion.',              11,   6,    1, '2026-04-14 10:30:00');

-- task
INSERT INTO task (id, description, completed, due_date, assigned_to_id, person_id, deal_id) VALUES
    (1,  'Send follow-up pricing PDF to Acme',         TRUE,  '2026-04-03 17:00:00', 2, 1,    1),
    (2,  'Schedule onsite with Globex',                TRUE,  '2026-04-05 12:00:00', 1, 3,    2),
    (3,  'Draft proposal v2 for Initech',              TRUE,  '2026-04-08 17:00:00', 3, 4,    3),
    (4,  'Prepare Stark contract redlines',            FALSE, '2026-05-15 17:00:00', 1, 10,   6),
    (5,  'Wayne - submit security questionnaire',      FALSE, '2026-05-12 12:00:00', 2, 12,   7),
    (6,  'Confirm Cyberdyne pilot accounts',           FALSE, '2026-05-10 09:00:00', 5, 13,   8),
    (7,  'Imani - draft retainer SOW',                 FALSE, '2026-05-09 17:00:00', 3, 14,   9),
    (8,  'Acme renewal - send updated quote',          FALSE, '2026-05-20 17:00:00', 2, 1,    10),
    (9,  'Internal: update CRM stage hygiene',         FALSE, '2026-05-30 17:00:00', 4, NULL, NULL),
    (10, 'Quarterly forecast review',                  FALSE, '2026-05-31 17:00:00', 4, NULL, NULL),
    (11, 'Call back Sven re: partnership',             FALSE, '2026-05-11 15:00:00', 5, 15,   NULL),
    (12, 'Stark - get clearance docs from legal',      FALSE, '2026-05-18 12:00:00', 1, 11,   6),
    (13, 'Send post-loss thank you to Umbrella',       TRUE,  '2026-03-25 17:00:00', 4, 9,    5),
    (14, 'Initech churn - exit interview',             TRUE,  '2026-05-04 14:00:00', 3, 4,    12),
    (15, 'Welcome call for Globex renewal',            FALSE, '2026-05-15 11:00:00', 1, 3,    11);

-- note
INSERT INTO note (id, content, author_id, person_id, deal_id) VALUES
    (1,  'Wile E. mentioned interest in expanding to rocket-powered roller skates next year.', 2, 1,    1),
    (2,  'Hank is highly responsive — replies within an hour on email.',                       1, 3,    2),
    (3,  'Bill prefers Friday meetings. Avoid Mondays.',                                       3, 4,    3),
    (4,  'Peter is technical champion; route engineering questions through him.',              3, 5,    3),
    (5,  'Soylent deal closed — celebration lunch booked for the team.',                       1, 8,    4),
    (6,  'Umbrella post-mortem: their incumbent has 5-year contract, not winnable now.',       4, 9,    5),
    (7,  'Stark requires SOC2 Type II evidence before signature.',                             1, 10,   6),
    (8,  'Pepper is the real decision driver behind Tony at Stark.',                           1, 11,   6),
    (9,  'Lucius prefers concise written summaries over slide decks.',                         2, 12,   7),
    (10, 'Cyberdyne pilot success criteria: model accuracy >92% on their dataset.',            5, 13,   8),
    (11, 'Imani billing cadence: net-15, monthly invoicing.',                                  3, 14,   9),
    (12, 'Acme renewal at risk if Q2 anvil performance dips below SLA.',                       2, 1,    10),
    (13, 'Globex renewed; expansion conversation queued for July.',                            1, 3,    11),
    (14, 'Initech budget freeze through end of fiscal year.',                                  3, 4,    12),
    (15, 'Sven open to retainer model; needs proof of pipeline first.',                        5, 15,   NULL);

-- ============================================================================
-- Junction tables
-- ============================================================================

-- deal_person : multiple stakeholders per deal with role labels
INSERT INTO deal_person (deal_id, person_id, role) VALUES
    (1,  1,  'Primary Contact'),
    (1,  2,  'Logistics'),
    (2,  3,  'Decision Maker'),
    (3,  4,  'Decision Maker'),
    (3,  5,  'Technical Champion'),
    (3,  6,  'Technical Evaluator'),
    (3,  7,  'Technical Evaluator'),
    (4,  8,  'Decision Maker'),
    (5,  9,  'Decision Maker'),
    (6,  10, 'Executive Sponsor'),
    (6,  11, 'Champion'),
    (7,  12, 'Decision Maker'),
    (8,  13, 'Technical Champion'),
    (9,  14, 'Primary Contact'),
    (10, 1,  'Renewal Contact'),
    (10, 2,  'Operations'),
    (11, 3,  'Renewal Contact'),
    (12, 4,  'Renewal Contact');

-- person_tag
INSERT INTO person_tag (person_id, tag_id) VALUES
    (1, 7), (1, 9),
    (3, 7), (3, 10), (3, 1),
    (4, 7), (4, 10),
    (5, 8), (5, 9),
    (6, 9),
    (7, 9),
    (8, 7),
    (9, 7), (9, 5),
    (10, 7), (10, 10), (10, 1),
    (11, 8), (11, 10),
    (12, 7), (12, 10),
    (13, 8), (13, 9),
    (14, 4),
    (15, 5);

-- company_tag
INSERT INTO company_tag (company_id, tag_id) VALUES
    (1, 3), (1, 4),
    (2, 2), (2, 6), (2, 1),
    (3, 3),
    (4, 2),
    (5, 2), (5, 5),
    (6, 2), (6, 6), (6, 1),
    (7, 2), (7, 6),
    (8, 3), (8, 4);

-- deal_tag
INSERT INTO deal_tag (deal_id, tag_id) VALUES
    (1,  3), (1, 4),
    (2,  2), (2, 6),
    (3,  3),
    (4,  2),
    (5,  5),
    (6,  2), (6, 6), (6, 1),
    (7,  2), (7, 6),
    (8,  3), (8, 4),
    (9,  3),
    (10, 3),
    (11, 2), (11, 6),
    (12, 5);
