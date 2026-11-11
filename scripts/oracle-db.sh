#!/usr/bin/env bash
# Local Oracle database for preferences-service: Oracle AI Database 26ai Free in the Docker container
# preferences-oracle, published on 127.0.0.1 only, data kept in the volume preferences-oracle-data.
#
#   scripts/oracle-db.sh start     create (first time) or start the container; wait until it is ready
#   scripts/oracle-db.sh stop      stop it (data kept)
#   scripts/oracle-db.sh status    show whether it is running
#   scripts/oracle-db.sh remove    delete the container AND its data volume
#
# Passwords: on first use this creates ~/.config/preferences-service/oracle.env (mode 600, outside the repository)
# with random passwords for SYS/SYSTEM (ORACLE_PASSWORD) and the application login (APP_USER_PASSWORD). Docker reads
# them from that file (--env-file), so they never appear on a command line or in the output.
set -euo pipefail

CONTAINER=${PREF_DB_CONTAINER:-preferences-oracle}
VOLUME=${CONTAINER}-data
IMAGE=${PREF_DB_IMAGE:-gvenzl/oracle-free:23.26.3-faststart}
PORT=${PREF_DB_PORT:-1525}
ENV_FILE=${PREF_ENV_FILE:-$HOME/.config/preferences-service/oracle.env}

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

wait_ready() {
    echo "oracle-db: waiting for the database (the first start takes about a minute)..."
    for _ in $(seq 1 180); do
        if docker logs "$CONTAINER" 2>&1 | grep -q 'DATABASE IS READY TO USE!'; then
            echo "oracle-db: ready on 127.0.0.1:$PORT, service FREEPDB1, login preferences"
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

case ${1:-status} in
start)
    ensure_env_file
    if running; then
        echo "oracle-db: $CONTAINER is already running"
    elif exists; then
        docker start "$CONTAINER" >/dev/null
        wait_ready
    else
        # On the first start the image creates the login APP_USER (and its schema) in FREEPDB1.
        docker run -d --name "$CONTAINER" \
            -p "127.0.0.1:$PORT:1521" \
            --env-file "$ENV_FILE" \
            -v "$VOLUME:/opt/oracle/oradata" \
            "$IMAGE" >/dev/null
        wait_ready
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
*)
    echo "usage: $0 start|stop|status|remove" >&2
    exit 2
    ;;
esac
