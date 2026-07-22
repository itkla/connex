UPDATE deal_stage_history eligible_history
JOIN (
    SELECT MAX(stage_event.id) AS history_id
    FROM deal open_deal
    JOIN deal_stage_history stage_event
        ON stage_event.workspace_id = open_deal.workspace_id
        AND stage_event.deal_id = open_deal.id
        AND stage_event.stage_id = open_deal.stage_id
    WHERE open_deal.won IS NULL
    GROUP BY open_deal.workspace_id, open_deal.id
) current_open_stage ON current_open_stage.history_id = eligible_history.id
SET eligible_history.conversion_eligible = TRUE;
