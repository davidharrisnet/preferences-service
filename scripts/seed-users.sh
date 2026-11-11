#!/usr/bin/env bash
# Adds three sample users to the PREFERENCES database, each with settings and a fruit order:
#
#   alice   alice@example.com   result_size 20, font_size 12   fruits in id order (the defaults a new user gets)
#   bob     bob@example.com     result_size 35, font_size 16   fruits in reverse id order
#   carol   carol@example.com   result_size 50, font_size 14   fruits in alphabetical order
#
# Every fruit order holds every active fruit exactly once, as the API requires. Safe to run again: a user who
# already exists is left as they are (nothing is added or changed for them).
#
#   scripts/seed-users.sh        (or: make db-seed-users)
#
# Runs through scripts/oracle-db.sh, so no password is needed; the database container must be running.
set -euo pipefail
cd "$(dirname "$0")/.."

scripts/oracle-db.sh query <<'SQL'
-- 1. The users. created_at is set here (the application's @CreationTimestamp is not involved in plain SQL).
INSERT INTO app_user (username, email, created_at)
SELECT s.username, s.email, SYSTIMESTAMP
FROM (SELECT 'alice' AS username, 'alice@example.com' AS email FROM dual UNION ALL
      SELECT 'bob', 'bob@example.com' FROM dual UNION ALL
      SELECT 'carol', 'carol@example.com' FROM dual) s
WHERE NOT EXISTS (SELECT 1 FROM app_user u WHERE u.username = s.username OR u.email = s.email);

-- 2. One settings row per user, for those who have none yet.
INSERT INTO user_settings (user_id, results_size, font_size)
SELECT u.id, s.results_size, s.font_size
FROM app_user u
JOIN (SELECT 'alice' AS username, 20 AS results_size, 12 AS font_size FROM dual UNION ALL
      SELECT 'bob', 35, 16 FROM dual UNION ALL
      SELECT 'carol', 50, 14 FROM dual) s ON s.username = u.username
WHERE NOT EXISTS (SELECT 1 FROM user_settings x WHERE x.user_id = u.id);

-- 3. The fruit orders: every active fruit, position 0, 1, 2, ... Only for settings that have no fruits yet.
INSERT INTO user_settings_fruit (user_settings_id, position, fruit_id)
SELECT s.id,
       ROW_NUMBER() OVER (PARTITION BY s.id ORDER BY
           CASE u.username WHEN 'bob' THEN -f.id ELSE 0 END,    -- bob: reverse id order
           CASE u.username WHEN 'carol' THEN f.name END,        -- carol: alphabetical
           f.id) - 1,                                           -- alice (and ties): id order
       f.id
FROM app_user u
JOIN user_settings s ON s.user_id = u.id
CROSS JOIN fruit f
WHERE u.username IN ('alice', 'bob', 'carol')
  AND f.active
  AND NOT EXISTS (SELECT 1 FROM user_settings_fruit x WHERE x.user_settings_id = s.id);
SQL

scripts/oracle-db.sh users
