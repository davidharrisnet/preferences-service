-- The shared fruit list, seeded at every start-up (spring.sql.init.mode=always). Converted from user-config-service's
-- SQLite "INSERT OR IGNORE": the MERGE inserts only the ids that are missing, so it is safe to run again, and it never
-- changes an existing row (a retired fruit stays retired).
-- To add a fruit, add a row with a NEW id. Never reuse or delete an id: saved orders refer to fruits by id.
MERGE INTO fruit f
USING (
    SELECT 1 AS id, 'apple' AS name FROM dual UNION ALL
    SELECT 2, 'pear' FROM dual UNION ALL
    SELECT 3, 'banana' FROM dual UNION ALL
    SELECT 4, 'strawberry' FROM dual UNION ALL
    SELECT 5, 'melon' FROM dual UNION ALL
    SELECT 6, 'orange' FROM dual
) s
ON (f.id = s.id)
WHEN NOT MATCHED THEN INSERT (id, name) VALUES (s.id, s.name);
