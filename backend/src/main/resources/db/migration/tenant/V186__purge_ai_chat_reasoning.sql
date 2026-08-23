-- Provider reasoning is never a product artifact. Remove legacy copies before future backups.
UPDATE ai_chat_message
SET structured_json = JSON_REMOVE(structured_json, '$.reasoning')
WHERE author_kind = 'assistant'
  AND JSON_CONTAINS_PATH(structured_json, 'one', '$.reasoning');
