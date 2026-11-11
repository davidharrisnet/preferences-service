#!/usr/bin/env bash
# Runs preferences-service in the background (the built jar), so the terminal stays free.
#
#   scripts/service.sh start      start the database if needed, build the jar, start the service, wait until healthy
#   scripts/service.sh stop       stop the service
#   scripts/service.sh restart    stop, rebuild, start
#   scripts/service.sh status     running or not, and its health
#   scripts/service.sh logs       follow the service's log (Ctrl+C to stop following; the service keeps running)
#   scripts/service.sh swagger    start the service if needed, then open Swagger UI in the browser
#   scripts/service.sh api        start the service if needed, then list the endpoints from the OpenAPI document
#
# The log is app/build/service.log and the process id app/build/service.pid (both under build/, so gitignored).
# The database password is read from ~/.config/preferences-service/oracle.env into the service's environment only:
# it is never printed and never on a command line.
set -euo pipefail

cd "$(dirname "$0")/.."

PORT=${SERVER_PORT:-8080}
BASE=http://localhost:$PORT
JAR=app/build/libs/app.jar
LOG=app/build/service.log
PID_FILE=app/build/service.pid
ENV_FILE=${PREF_ENV_FILE:-$HOME/.config/preferences-service/oracle.env}

pid() { [[ -f $PID_FILE ]] && cat "$PID_FILE"; }
running() { local p; p=$(pid) && [[ -n $p ]] && kill -0 "$p" 2>/dev/null; }
healthy() { curl -sf "$BASE/actuator/health" 2>/dev/null | grep '"status":"UP"' >/dev/null; }

wait_healthy() {
    echo "service: waiting for $BASE/actuator/health ..."
    for _ in $(seq 1 90); do
        if healthy; then
            echo "service: running on $BASE (pid $(pid)), Swagger UI $BASE/swagger-ui.html, log $LOG"
            return 0
        fi
        if ! running; then
            echo "service: stopped during start-up; last lines of $LOG:" >&2
            tail -20 "$LOG" >&2
            rm -f "$PID_FILE"
            return 1
        fi
        sleep 2
    done
    echo "service: not healthy after 3 minutes; see $LOG" >&2
    return 1
}

start() {
    if running; then
        echo "service: already running (pid $(pid))"
        return 0
    fi
    if curl -s -o /dev/null "$BASE/actuator/health" 2>/dev/null; then
        echo "service: something else is already answering on port $PORT; stop it first" >&2
        return 1
    fi
    scripts/oracle-db.sh start
    ./gradlew -q :app:bootJar
    local password
    password=$(sed -n 's/^APP_USER_PASSWORD=//p' "$ENV_FILE")
    [[ -n $password ]] || { echo "service: no APP_USER_PASSWORD in $ENV_FILE" >&2; return 1; }
    mkdir -p "$(dirname "$LOG")"
    # nohup: the service keeps running after this script (and the terminal) ends.
    PREF_DB_PASSWORD=$password nohup java -jar "$JAR" > "$LOG" 2>&1 &
    echo $! > "$PID_FILE"
    wait_healthy
}

stop() {
    if ! running; then
        rm -f "$PID_FILE"
        echo "service: not running"
        return 0
    fi
    local p; p=$(pid)
    kill "$p"
    for _ in $(seq 1 30); do
        kill -0 "$p" 2>/dev/null || break
        sleep 1
    done
    if kill -0 "$p" 2>/dev/null; then
        echo "service: still running after 30 s; forcing it to stop" >&2
        kill -9 "$p"
    fi
    rm -f "$PID_FILE"
    echo "service: stopped"
}

open_url() {
    if command -v xdg-open >/dev/null; then
        xdg-open "$1" >/dev/null 2>&1 &
        echo "service: opened $1"
    else
        echo "service: open $1 in your browser"
    fi
}

case ${1:-status} in
start)   start ;;
stop)    stop ;;
restart) stop; start ;;
status)
    if running; then
        if healthy; then echo "service: running on $BASE (pid $(pid)), healthy"
        else echo "service: running (pid $(pid)) but not healthy yet; see $LOG"; fi
    else
        echo "service: not running (make start)"
    fi
    ;;
logs)
    [[ -f $LOG ]] || { echo "service: no log yet ($LOG)" >&2; exit 1; }
    tail -f "$LOG"
    ;;
swagger)
    start
    open_url "$BASE/swagger-ui.html"
    ;;
api)
    start >/dev/null
    curl -s "$BASE/v3/api-docs" | python3 -c '
import json, sys
doc = json.load(sys.stdin)
for path, ops in sorted(doc["paths"].items()):
    for method, op in ops.items():
        summary = op.get("summary", "")
        print(f"  {method.upper():6} {path:32} {summary}")'
    ;;
*)
    echo "usage: $0 start|stop|restart|status|logs|swagger|api" >&2
    exit 2
    ;;
esac
