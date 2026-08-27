#!/system/bin/sh

RUNTIME_DIR="/data/adb/kunbox"
LEASE_FILE="$RUNTIME_DIR/lease"
ACK_FILE="$RUNTIME_DIR/watchdog_ack"
SESSION_FILE="$RUNTIME_DIR/session"
CLEANUP_SCRIPT="$RUNTIME_DIR/cleanup-owned.sh"

cleanup_runtime() {
    rm -f "$LEASE_FILE" "$ACK_FILE" "$SESSION_FILE" "$RUNTIME_DIR/watchdog.pid"
    rm -f "$RUNTIME_DIR/watchdog.sh"
    rmdir "$RUNTIME_DIR" 2>/dev/null
}

cleanup_owned() {
    EXPECTED_SESSION="$1"
    [ -x "$CLEANUP_SCRIPT" ] || return 75
    "$CLEANUP_SCRIPT" cleanup "$EXPECTED_SESSION"
}

if [ "$1" = "cleanup" ]; then
    EXPECTED_SESSION="$2"
    CURRENT_SESSION="$(cat "$SESSION_FILE" 2>/dev/null)"
    if [ -n "$EXPECTED_SESSION" ] && [ "$CURRENT_SESSION" != "$EXPECTED_SESSION" ]; then
        exit 0
    fi
    cleanup_owned "$EXPECTED_SESSION" || exit $?
    cleanup_runtime
    exit 0
fi

if [ "$1" != "watch" ] || [ "$#" -ne 6 ]; then
    exit 64
fi

APK_PATH="$2"
ROOT_PID="$3"
SESSION_ID="$4"
LEASE_TIMEOUT="$5"
ROOT_START_TIME="$6"

case "$ROOT_START_TIME" in ''|*[!0-9]*) exit 64 ;; esac

printf '%s\n' "$$" > "$RUNTIME_DIR/watchdog.pid"

while :; do
    NOW="$(date +%s)"
    LEASE="$(cat "$LEASE_FILE" 2>/dev/null)"
    CURRENT_SESSION="$(cat "$SESSION_FILE" 2>/dev/null)"
    STALE=0

    case "$LEASE" in
        ''|*[!0-9]*) STALE=1 ;;
        *) [ $((NOW - LEASE)) -gt "$LEASE_TIMEOUT" ] && STALE=1 ;;
    esac

    CURRENT_ROOT_START_TIME="$(sed 's/.*) //' "/proc/$ROOT_PID/stat" 2>/dev/null | awk '{print $20}')"
    if [ ! -e "$APK_PATH" ] || [ "$CURRENT_SESSION" != "$SESSION_ID" ] || \
        ! kill -0 "$ROOT_PID" 2>/dev/null || [ "$CURRENT_ROOT_START_TIME" != "$ROOT_START_TIME" ]; then
        STALE=1
    fi

    if [ "$STALE" -ne 0 ]; then
        CURRENT_SESSION="$(cat "$SESSION_FILE" 2>/dev/null)"
        if [ "$CURRENT_SESSION" = "$SESSION_ID" ]; then
            cleanup_owned "$SESSION_ID"
            CLEANUP_STATUS=$?
            if [ "$CLEANUP_STATUS" -ne 0 ]; then
                printf '%s\n' "watchdog_cleanup:$CLEANUP_STATUS" > "$RUNTIME_DIR/cleanup_conflict"
                exit "$CLEANUP_STATUS"
            fi
            kill "$ROOT_PID" 2>/dev/null
            cleanup_runtime
        fi
        exit 0
    fi

    TMP_ACK="$ACK_FILE.tmp.$$"
    printf '%s:%s\n' "$SESSION_ID" "$NOW" > "$TMP_ACK"
    mv -f "$TMP_ACK" "$ACK_FILE"
    sleep 1
done
