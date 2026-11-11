#!/usr/bin/env bash
# Removes ALL users from the PREFERENCES database. Their settings and fruit orders go with them (ON DELETE CASCADE
# in schema.sql). The fruit list is not touched.
#
#   scripts/remove-users.sh --yes        (or: make db-remove-users CONFIRM=yes)
#
# Runs through scripts/oracle-db.sh, so no password is needed; the database container must be running.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ${1:-} != --yes ]]; then
    echo "remove-users: this deletes EVERY user with their settings and fruit orders. Run again with --yes" >&2
    echo "(make db-remove-users CONFIRM=yes). scripts/seed-users.sh adds the three sample users back." >&2
    exit 2
fi

scripts/oracle-db.sh query <<'SQL'
DELETE FROM app_user;
SQL

scripts/oracle-db.sh tables
