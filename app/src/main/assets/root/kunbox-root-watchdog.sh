#!/system/bin/sh

# KunBox Root 透明代理独立清理守护。
RUNTIME_DIR="/data/adb/kunbox"
LEASE_FILE="$RUNTIME_DIR/lease"
ACK_FILE="$RUNTIME_DIR/watchdog_ack"
SESSION_FILE="$RUNTIME_DIR/session"

cleanup_chain() {
    BIN="$1"
    TABLE="$2"
    CHAIN="$3"
    "$BIN" -t "$TABLE" -F "$CHAIN" 2>/dev/null
    "$BIN" -t "$TABLE" -X "$CHAIN" 2>/dev/null
}

cleanup_rules() {
    while iptables -t mangle -D OUTPUT -j KBX_OUT4 2>/dev/null; do :; done
    while iptables -t mangle -D PREROUTING -j KBX_PRE4 2>/dev/null; do :; done
    while iptables -t filter -D INPUT -j KBX_IN4 2>/dev/null; do :; done
    while iptables -t nat -D OUTPUT -j KBX_RED4 2>/dev/null; do :; done
    while ip6tables -t mangle -D OUTPUT -j KBX_OUT6 2>/dev/null; do :; done
    while ip6tables -t mangle -D PREROUTING -j KBX_PRE6 2>/dev/null; do :; done
    while ip6tables -t filter -D INPUT -j KBX_IN6 2>/dev/null; do :; done
    while ip6tables -t nat -D OUTPUT -j KBX_RED6 2>/dev/null; do :; done
    while iptables -t filter -D OUTPUT -j KBX_BLOCK4 2>/dev/null; do :; done
    while ip6tables -t filter -D OUTPUT -j KBX_BLOCK6 2>/dev/null; do :; done
    while iptables -t filter -D OUTPUT -j KBX_QUIC4 2>/dev/null; do :; done
    while ip6tables -t filter -D OUTPUT -j KBX_QUIC6 2>/dev/null; do :; done

    ip rule del fwmark 0x2331 table 20231 pref 12031 2>/dev/null
    ip -6 rule del fwmark 0x2332 table 20231 pref 12032 2>/dev/null
    ip route del local 0.0.0.0/0 dev lo table 20231 proto 233 2>/dev/null
    ip -6 route del local ::/0 dev lo table 20231 proto 233 2>/dev/null

    cleanup_chain iptables mangle KBX_OUT4
    cleanup_chain iptables mangle KBX_PRE4
    cleanup_chain iptables filter KBX_IN4
    cleanup_chain iptables nat KBX_RED4
    cleanup_chain ip6tables mangle KBX_OUT6
    cleanup_chain ip6tables mangle KBX_PRE6
    cleanup_chain ip6tables filter KBX_IN6
    cleanup_chain ip6tables nat KBX_RED6
    cleanup_chain iptables filter KBX_BLOCK4
    cleanup_chain ip6tables filter KBX_BLOCK6
    cleanup_chain iptables filter KBX_QUIC4
    cleanup_chain ip6tables filter KBX_QUIC6
}

cleanup_runtime() {
    rm -f "$LEASE_FILE" "$ACK_FILE" "$SESSION_FILE" "$RUNTIME_DIR/watchdog.pid"
    rm -f "$RUNTIME_DIR/watchdog.sh"
    rmdir "$RUNTIME_DIR" 2>/dev/null
}

if [ "$1" = "cleanup" ]; then
    EXPECTED_SESSION="$2"
    CURRENT_SESSION="$(cat "$SESSION_FILE" 2>/dev/null)"
    if [ -n "$EXPECTED_SESSION" ] && [ "$CURRENT_SESSION" != "$EXPECTED_SESSION" ]; then
        exit 0
    fi
    cleanup_rules
    cleanup_runtime
    exit 0
fi

if [ "$1" != "watch" ] || [ "$#" -ne 5 ]; then
    exit 64
fi

APK_PATH="$2"
ROOT_PID="$3"
SESSION_ID="$4"
LEASE_TIMEOUT="$5"

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

    if [ ! -e "$APK_PATH" ] || [ "$CURRENT_SESSION" != "$SESSION_ID" ] || ! kill -0 "$ROOT_PID" 2>/dev/null; then
        STALE=1
    fi

    if [ "$STALE" -ne 0 ]; then
        CURRENT_SESSION="$(cat "$SESSION_FILE" 2>/dev/null)"
        if [ "$CURRENT_SESSION" = "$SESSION_ID" ]; then
            cleanup_rules
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
