#!/system/bin/sh

RUNTIME_DIR="/data/adb/kunbox"
LEASE_FILE="$RUNTIME_DIR/lease"
ACK_FILE="$RUNTIME_DIR/watchdog_ack"
SESSION_FILE="$RUNTIME_DIR/session"
CLEANUP_SCRIPT="$RUNTIME_DIR/cleanup-owned.sh"
IPV6_PRIVACY_STATE="$RUNTIME_DIR/ipv6-privacy-state"

restore_ipv6_privacy() {
    EXPECTED_SESSION="$1"
    [ -e "$IPV6_PRIVACY_STATE" ] || return 0
    [ -f "$IPV6_PRIVACY_STATE" ] && [ ! -L "$IPV6_PRIVACY_STATE" ] || return 75
    STATE_SESSION="$(sed -n 's/^session=//p' "$IPV6_PRIVACY_STATE" | head -n 1)"
    [ -n "$EXPECTED_SESSION" ] && [ "$STATE_SESSION" != "$EXPECTED_SESSION" ] && return 0
    DEFAULT_VALUE="$(sed -n 's/^default=//p' "$IPV6_PRIVACY_STATE" | head -n 1)"
    case "$DEFAULT_VALUE" in 0|1) ;; *) return 75 ;; esac
    RESTORE_FAILED=0
    while IFS='|' read -r KEY VALUE; do
        case "$KEY" in
            iface=*) INTERFACE="${KEY#iface=}" ;;
            *) continue ;;
        esac
        case "$INTERFACE" in ''|*[!A-Za-z0-9_.:-]*) return 75 ;; esac
        case "$VALUE" in 0|1) ;; *) return 75 ;; esac
        CONTROL="/proc/sys/net/ipv6/conf/$INTERFACE/disable_ipv6"
        [ -f "$CONTROL" ] || continue
        printf '%s' "$VALUE" > "$CONTROL" 2>/dev/null || RESTORE_FAILED=1
    done < "$IPV6_PRIVACY_STATE"
    for CONTROL in /proc/sys/net/ipv6/conf/*/disable_ipv6; do
        [ -f "$CONTROL" ] || continue
        INTERFACE="${CONTROL%/disable_ipv6}"
        INTERFACE="${INTERFACE##*/}"
        case "$INTERFACE" in all|default|lo) continue ;; esac
        grep -F -q "iface=$INTERFACE|" "$IPV6_PRIVACY_STATE" ||
            printf '%s' "$DEFAULT_VALUE" > "$CONTROL" 2>/dev/null || RESTORE_FAILED=1
    done
    printf '%s' "$DEFAULT_VALUE" > /proc/sys/net/ipv6/conf/default/disable_ipv6 2>/dev/null || RESTORE_FAILED=1
    [ "$RESTORE_FAILED" -eq 0 ] || return 75
    rm -f "$IPV6_PRIVACY_STATE"
}

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
    restore_ipv6_privacy "$EXPECTED_SESSION" || exit $?
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
            restore_ipv6_privacy "$SESSION_ID"
            PRIVACY_STATUS=$?
            if [ "$PRIVACY_STATUS" -ne 0 ]; then
                printf '%s\n' "watchdog_privacy_restore:$PRIVACY_STATUS" > "$RUNTIME_DIR/cleanup_conflict"
                exit "$PRIVACY_STATUS"
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
