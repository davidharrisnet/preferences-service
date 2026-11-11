#!/usr/bin/env bash
# Local Oracle database for preferences-service: Oracle AI Database 26ai Free in the Docker container
# preferences-oracle, published on 127.0.0.1 only, data kept in the volume preferences-oracle-data.
#
#   scripts/oracle-db.sh start     create (first time) or start the container; wait until it is ready
#   scripts/oracle-db.sh stop      stop it (data kept)
#   scripts/oracle-db.sh status    show whether it is running
#   scripts/oracle-db.sh remove    delete the container AND its data volume
#   scripts/oracle-db.sh logs      follow the container's log (Ctrl+C to stop)
#
# Working with the data (in the PREFERENCES database, as the PREFERENCES schema):
#   scripts/oracle-db.sh sql               interactive SQL*Plus (type EXIT to leave; changes are committed on EXIT)
#   scripts/oracle-db.sh query 'SQL;'      run SQL given as an argument, or on standard input; committed at the end
#   scripts/oracle-db.sh tables            the application's tables and their row counts
#   scripts/oracle-db.sh fruits            the fruit list, with active flags
#   scripts/oracle-db.sh users             users with their settings and fruit order
#   scripts/oracle-db.sh retire ID         hide fruit ID from everyone (saved orders are kept)
#   scripts/oracle-db.sh activate ID       bring fruit ID back
#   scripts/oracle-db.sh drop-tables --yes delete the 4 tables and all their data; the next start recreates and reseeds
#
# Passwords: on first use this creates ~/.config/preferences-service/oracle.env (mode 600, outside the repository)
# with random passwords for SYS/SYSTEM (ORACLE_PASSWORD) and the application login (APP_USER_PASSWORD). Docker reads
# them from that file (--env-file), so they never appear on a command line or in the output. The data commands need
# no password at all: they run SQL*Plus inside the container as the operating-system user oracle ("/ as sysdba") and
# then switch to the PREFERENCES database and schema.
set -euo pipefail

CONTAINER=${PREF_DB_CONTAINER:-preferences-oracle}
VOLUME=${CONTAINER}-data
IMAGE=${PREF_DB_IMAGE:-gvenzl/oracle-free:23.26.3-faststart}
PORT=${PREF_DB_PORT:-1525}
# The pluggable database (and service name) the application uses. The image creates it on the first start.
DATABASE=${PREF_DB_SERVICE:-PREFERENCES}
# The application login, which owns the tables (its schema has the same name).
SCHEMA=${PREF_DB_USER:-preferences}
ENV_FILE=${PREF_ENV_FILE:-$HOME/.config/preferences-service/oracle.env}

# The lines every data command starts with: stop at the first error, switch to the application's database and schema.
SESSION_SQL="WHENEVER SQLERROR EXIT FAILURE ROLLBACK
ALTER SESSION SET CONTAINER = $DATABASE;
ALTER SESSION SET CURRENT_SCHEMA = $SCHEMA;"

ensure_env_file() {
    [[ -f $ENV_FILE ]] && return
    mkdir -p "$(dirname "$ENV_FILE")"
    chmod 700 "$(dirname "$ENV_FILE")"
    (
        umask 077
        # Letters and digits only, starting with a letter (valid unquoted Oracle passwords).
        printf 'ORACLE_PASSWORD=S%s\nAPP_USER=preferences\nAPP_USER_PASSWORD=P%s\n' \
            "$(openssl rand -hex 16)" "$(openssl rand -hex 16)" > "$ENV_FILE"
    )
    echo "oracle-db: created password file $ENV_FILE"
}

exists()  { docker container inspect "$CONTAINER" >/dev/null 2>&1; }
running() { [[ $(docker container inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null) == true ]]; }

# Waits for this start's "ready" line. $1 is the time the container was started: older log lines (a previous start's
# "ready") are skipped. grep reads all of its input (no -q): with -q it would exit at the first match, docker logs
# would fail with a broken pipe, and pipefail would turn the match into a failure.
wait_ready() {
    local since=$1
    echo "oracle-db: waiting for the database (the first start takes about a minute)..."
    for _ in $(seq 1 180); do
        if docker logs --since "$since" "$CONTAINER" 2>&1 | grep 'DATABASE IS READY TO USE!' >/dev/null; then
            echo "oracle-db: ready on 127.0.0.1:$PORT, database (service) $DATABASE, login preferences"
            return 0
        fi
        if ! running; then
            echo "oracle-db: the container stopped; see: docker logs $CONTAINER" >&2
            return 1
        fi
        sleep 2
    done
    echo "oracle-db: not ready after 6 minutes; see: docker logs $CONTAINER" >&2
    return 1
}

require_running() {
    running || { echo "oracle-db: $CONTAINER is not running (make db-start)" >&2; exit 1; }
}

# Runs the SQL on standard input in the application's database and schema, then commits. Output formatted for reading.
run_sql() {
    require_running
    # FEEDBACK is off around the set-up lines and the COMMIT, so only the user's statements report ("2 rows selected.").
    { printf '%s\n' "SET FEEDBACK OFF" "SET LINESIZE 200" "SET PAGESIZE 100" "$SESSION_SQL" "SET FEEDBACK ON"
      cat
      printf '%s\n' "SET FEEDBACK OFF" "COMMIT;" "EXIT"; } |
        docker exec -i "$CONTAINER" sqlplus -s -l / as sysdba
}

# A fruit id must be a whole number; it goes into SQL text, so nothing else is accepted.
fruit_id() {
    [[ ${1:-} =~ ^[0-9]+$ ]] || { echo "oracle-db: give a fruit id (a whole number), for example: $0 $2 2" >&2; exit 2; }
    echo "$1"
}

case ${1:-status} in
start)
    ensure_env_file
    if running; then
        echo "oracle-db: $CONTAINER is already running"
    elif exists; then
        since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
        docker start "$CONTAINER" >/dev/null
        wait_ready "$since"
    else
        since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
        # On the first start the image creates the pluggable database ORACLE_DATABASE and, inside it, the login
        # APP_USER (and its schema). ORACLE_DATABASE is not a secret, so it can be on the command line.
        docker run -d --name "$CONTAINER" \
            -p "127.0.0.1:$PORT:1521" \
            --env-file "$ENV_FILE" \
            -e "ORACLE_DATABASE=$DATABASE" \
            -v "$VOLUME:/opt/oracle/oradata" \
            "$IMAGE" >/dev/null
        wait_ready "$since"
    fi
    ;;
stop)
    if running; then docker stop "$CONTAINER" >/dev/null; fi
    echo "oracle-db: $CONTAINER stopped"
    ;;
status)
    if running; then echo "oracle-db: $CONTAINER running on 127.0.0.1:$PORT"
    elif exists; then echo "oracle-db: $CONTAINER stopped"
    else echo "oracle-db: $CONTAINER does not exist (scripts/oracle-db.sh start creates it)"; fi
    ;;
remove)
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    docker volume rm "$VOLUME" >/dev/null 2>&1 || true
    echo "oracle-db: $CONTAINER and its volume $VOLUME removed (the password file is kept)"
    ;;
logs)
    docker logs -f --tail 50 "$CONTAINER"
    ;;
sql)
    require_running
    # login.sql runs when SQL*Plus connects and switches to the application's database and schema. On Linux SQL*Plus
    # finds it through ORACLE_PATH (SQLPATH is ignored for login.sql). Errors should not end an interactive session,
    # so it leaves out WHENEVER SQLERROR.
    printf '%s\n' "ALTER SESSION SET CONTAINER = $DATABASE;" "ALTER SESSION SET CURRENT_SCHEMA = $SCHEMA;" \
        "SET LINESIZE 200 PAGESIZE 100" "SET SQLPROMPT '$DATABASE> '" |
        docker exec -i "$CONTAINER" bash -c 'mkdir -p /tmp/prefsql && cat > /tmp/prefsql/login.sql'
    echo "oracle-db: connected to database $DATABASE, schema ${SCHEMA^^}. Type EXIT to leave (changes are committed)."
    # -t only with a terminal, so the command also accepts a piped script.
    tty=-i; [[ -t 0 ]] && tty=-it
    docker exec "$tty" -e ORACLE_PATH=/tmp/prefsql "$CONTAINER" sqlplus -s -l / as sysdba
    ;;
query)
    # SQL as an argument, or (empty or missing argument) on standard input.
    if [[ -n ${2:-} ]]; then printf '%s\n' "$2" | run_sql; else run_sql; fi
    ;;
tables)
    run_sql <<'SQL'
COLUMN table_name FORMAT A25
SELECT 'APP_USER' AS table_name, COUNT(*) AS row_count FROM app_user
UNION ALL SELECT 'FRUIT', COUNT(*) FROM fruit
UNION ALL SELECT 'USER_SETTINGS', COUNT(*) FROM user_settings
UNION ALL SELECT 'USER_SETTINGS_FRUIT', COUNT(*) FROM user_settings_fruit;
SQL
    ;;
fruits)
    run_sql <<'SQL'
COLUMN name FORMAT A20
COLUMN active FORMAT A6
SELECT id, name, CASE WHEN active THEN 'yes' ELSE 'no' END AS active FROM fruit ORDER BY id;
SQL
    ;;
users)
    run_sql <<'SQL'
COLUMN username FORMAT A20
COLUMN email FORMAT A30
COLUMN fruit_order FORMAT A60
SELECT u.id, u.username, u.email, s.results_size, s.font_size,
       (SELECT LISTAGG(f.name, ', ') WITHIN GROUP (ORDER BY usf.position)
        FROM user_settings_fruit usf JOIN fruit f ON f.id = usf.fruit_id
        WHERE usf.user_settings_id = s.id) AS fruit_order
FROM app_user u JOIN user_settings s ON s.user_id = u.id
ORDER BY u.id;
SQL
    ;;
retire)
    id=$(fruit_id "${2:-}" retire)
    printf '%s\n' "UPDATE fruit SET active = FALSE WHERE id = $id;" | run_sql
    ;;
activate)
    id=$(fruit_id "${2:-}" activate)
    printf '%s\n' "UPDATE fruit SET active = TRUE WHERE id = $id;" | run_sql
    ;;
drop-tables)
    if [[ ${2:-} != --yes ]]; then
        echo "oracle-db: this deletes the tables app_user, fruit, user_settings and user_settings_fruit and ALL their" >&2
        echo "data. Run again with --yes (make db-drop-tables CONFIRM=yes). The next start recreates and reseeds them." >&2
        exit 2
    fi
    # Children first, so no foreign key is in the way; IF EXISTS makes a second run harmless.
    run_sql <<'SQL'
DROP TABLE IF EXISTS user_settings_fruit PURGE;
DROP TABLE IF EXISTS user_settings PURGE;
DROP TABLE IF EXISTS fruit PURGE;
DROP TABLE IF EXISTS app_user PURGE;
SQL
    ;;
*)
    echo "usage: $0 start|stop|status|remove|logs|sql|query|tables|fruits|users|retire ID|activate ID|drop-tables --yes" >&2
    exit 2
    ;;
esac
