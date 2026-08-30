#!/system/bin/sh

# Only fixed KunBox chains and policy-routing state are owned here.
# Whole-table flushes and foreign-chain changes are forbidden.
RUNTIME_DIR="/data/adb/kunbox"
OWNER_FILE="$RUNTIME_DIR/netfilter-owner"
STAGING_FILE="$RUNTIME_DIR/netfilter-owner.staging"
CONFLICT_FILE="$RUNTIME_DIR/cleanup_conflict"
ROUTE_TABLE="20231"
LOCK_DIR="$RUNTIME_DIR/.netfilter-lock"
WAIT_SECONDS=2

TABLE_SPECS="iptables|mangle iptables|nat iptables|filter ip6tables|mangle ip6tables|nat ip6tables|filter"
CHAIN_SPECS="\
iptables|mangle|KBX_OUT4|OUTPUT \
iptables|mangle|KBX_PRE4|PREROUTING \
iptables|filter|KBX_IN4|INPUT \
iptables|nat|KBX_RED4|OUTPUT \
ip6tables|mangle|KBX_OUT6|OUTPUT \
ip6tables|mangle|KBX_PRE6|PREROUTING \
ip6tables|filter|KBX_IN6|INPUT \
ip6tables|nat|KBX_RED6|OUTPUT \
iptables|filter|KBX_BLOCK4|OUTPUT \
ip6tables|filter|KBX_BLOCK6|OUTPUT \
iptables|filter|KBX_QUIC4|OUTPUT \
ip6tables|filter|KBX_QUIC6|OUTPUT \
ip6tables|filter|KBX_PRIV6|OUTPUT \
iptables|filter|KBX_GUARD4|OUTPUT \
ip6tables|filter|KBX_GUARD6|OUTPUT"

now_ms() {
    value="$(date +%s%3N 2>/dev/null)"
    case "$value" in ''|*[!0-9]*) value="$(date +%s 2>/dev/null)000" ;; esac
    printf '%s\n' "${value:-0}"
}

diag() {
    [ -n "$1" ] || { printf '<empty>'; return; }
    printf '%s' "$1" | tr '\r\n' ';;'
}

record_failure() {
    [ -z "$FAILURES" ] || FAILURES="$FAILURES;"
    FAILURES="${FAILURES}$1"
}

acquire_lock() {
    attempt=0
    while ! mkdir "$LOCK_DIR" 2>/dev/null; do
        attempt=$((attempt + 1))
        [ "$attempt" -ge 20 ] && {
            printf 'cleanup_conflict=netfilter_serialization_timeout\n' > "$CONFLICT_FILE"
            return 75
        }
        sleep 0.1
    done
    trap 'rmdir "$LOCK_DIR" 2>/dev/null' EXIT HUP INT TERM
}

xtables_run() {
    binary="$1"
    shift
    out="$RUNTIME_DIR/.cleanup.stdout.$$"
    err="$RUNTIME_DIR/.cleanup.stderr.$$"
    started="$(now_ms)"
    command "$binary" -w "$WAIT_SECONDS" "$@" >"$out" 2>"$err"
    status=$?
    command_text="$binary -w $WAIT_SECONDS"
    for arg in "$@"; do command_text="$command_text $arg"; done
    if [ "$status" -ne 0 ] && grep -E -i -q \
        'unknown option.*(--wait|-w)|unrecognized option.*(--wait|-w)|invalid option.*(--wait|-w)|illegal option.*(--wait|-w)' "$err"; then
        command "$binary" "$@" >"$out" 2>"$err"
        status=$?
        command_text="$binary"
        for arg in "$@"; do command_text="$command_text $arg"; done
    fi
    finished="$(now_ms)"
    CMD_STDOUT="$(cat "$out" 2>/dev/null)"
    CMD_STDERR="$(cat "$err" 2>/dev/null)"
    rm -f "$out" "$err"
    CMD_STATUS="$status"
    CMD_TEXT="$command_text"
    printf 'cleanup_command=xtables command="%s" exitCode=%s stdout="%s" stderr="%s" elapsed_ms=%s\n' \
        "$(diag "$command_text")" "$status" "$(diag "$CMD_STDOUT")" "$(diag "$CMD_STDERR")" \
        "$((finished - started))" >&2
}

ip_run() {
    binary="$1"
    shift
    out="$RUNTIME_DIR/.cleanup.stdout.$$"
    err="$RUNTIME_DIR/.cleanup.stderr.$$"
    started="$(now_ms)"
    command "$binary" "$@" >"$out" 2>"$err"
    CMD_STATUS=$?
    finished="$(now_ms)"
    CMD_STDOUT="$(cat "$out" 2>/dev/null)"
    CMD_STDERR="$(cat "$err" 2>/dev/null)"
    rm -f "$out" "$err"
    CMD_TEXT="$binary"
    for arg in "$@"; do CMD_TEXT="$CMD_TEXT $arg"; done
    printf 'cleanup_command=ip command="%s" exitCode=%s stdout="%s" stderr="%s" elapsed_ms=%s\n' \
        "$(diag "$CMD_TEXT")" "$CMD_STATUS" "$(diag "$CMD_STDOUT")" "$(diag "$CMD_STDERR")" \
        "$((finished - started))" >&2
}

backend_label() {
    version="$(command "$1" -V 2>&1)"
    case "$version" in
        *nf_tables*) printf 'iptables-nft' ;;
        *legacy*) printf 'iptables-legacy' ;;
        '') printf 'unknown' ;;
        *) printf 'iptables' ;;
    esac
}

table_file() { printf '%s/.table-%s-%s' "$RUNTIME_DIR" "$1" "$2"; }

query_table() {
    binary="$1"
    table="$2"
    file="$(table_file "$binary" "$table")"
    xtables_run "$binary" -t "$table" -S
    if [ "$CMD_STATUS" -eq 0 ]; then
        printf '%s' "$CMD_STDOUT" > "$file"
        state=AVAILABLE
    elif [ "$CMD_STATUS" -eq 3 ] || printf '%s' "$CMD_STDERR" | grep -E -i -q \
        'table.*(does not exist|not found)|can.t initialize .*table|unknown table'; then
        : > "$file"
        state=ABSENT
    else
        : > "$file"
        state=QUERY_FAILED
        record_failure "query:$binary:$table exitCode=$CMD_STATUS stderr=$(diag "$CMD_STDERR")"
    fi
    printf '%s\n' "$state" > "$file.state"
    printf '[ROOT_NET_QUERY] binary=%s table=%s backend=%s exactCommand="%s" exitCode=%s stdout="%s" stderr="%s" classification=%s\n' \
        "$binary" "$table" "$(backend_label "$binary")" "$(diag "$CMD_TEXT")" "$CMD_STATUS" \
        "$(diag "$CMD_STDOUT")" "$(diag "$CMD_STDERR")" "$state" >&2
}

known_chain() {
    case "$1" in
        KBX_OUT4|KBX_PRE4|KBX_IN4|KBX_RED4|KBX_OUT6|KBX_PRE6|KBX_IN6|KBX_RED6|\
        KBX_BLOCK4|KBX_BLOCK6|KBX_QUIC4|KBX_QUIC6|KBX_PRIV6|KBX_GUARD4|KBX_GUARD6) return 0 ;;
    esac
    return 1
}

split_spec() {
    old_ifs="$IFS"
    IFS='|'
    set -- $1
    IFS="$old_ifs"
    SPEC_BINARY="$1"
    SPEC_TABLE="$2"
    SPEC_CHAIN="$3"
    SPEC_HOOK="$4"
}

delete_known_chains() {
    for spec in $CHAIN_SPECS; do
        split_spec "$spec"
        file="$(table_file "$SPEC_BINARY" "$SPEC_TABLE")"
        state="$(cat "$file.state" 2>/dev/null)"
        [ "$state" = AVAILABLE ] || continue
        grep -F -q -- "-A $SPEC_HOOK -j $SPEC_CHAIN" "$file" || continue
        xtables_run "$SPEC_BINARY" -t "$SPEC_TABLE" -D "$SPEC_HOOK" -j "$SPEC_CHAIN"
    done
    for spec in $CHAIN_SPECS; do
        split_spec "$spec"
        file="$(table_file "$SPEC_BINARY" "$SPEC_TABLE")"
        grep -F -q -- "-N $SPEC_CHAIN" "$file" || continue
        xtables_run "$SPEC_BINARY" -t "$SPEC_TABLE" -F "$SPEC_CHAIN"
        xtables_run "$SPEC_BINARY" -t "$SPEC_TABLE" -X "$SPEC_CHAIN"
    done
}

owned_policy_line() {
    line="$1"
    family="$2"
    priority="${line%%:*}"
    case "$priority" in ''|*[!0-9]*) return 1 ;; esac
    if [ "$priority" -ge 12450 ] && [ "$priority" -le 12705 ]; then
        [ "$family" = 6 ] && printf '%s\n' "$line" | grep -q ' uidrange ' || return 1
    elif [ "$priority" -eq 12031 ] || [ "$priority" -eq 12032 ] || \
        { [ "$priority" -ge 12100 ] && [ "$priority" -le 12227 ]; } || \
        { [ "$priority" -ge 12300 ] && [ "$priority" -le 12427 ]; }; then
        :
    else
        return 1
    fi
    case "$line" in *"lookup $ROUTE_TABLE"*|*"table $ROUTE_TABLE"*) return 0 ;; esac
    return 1
}

delete_policy_family() {
    family="$1"
    if [ "$family" = 6 ]; then prefix="-6"; route="::/0"; else prefix=""; route="0.0.0.0/0"; fi
    ip_run ip $prefix rule show
    rules="$CMD_STDOUT"
    printf '%s\n' "$rules" | while IFS= read -r line; do
        owned_policy_line "$line" "$family" || continue
        priority="${line%%:*}"
        mark="$(printf '%s\n' "$line" | sed -n 's/.*fwmark \([^ /]*\).*/\1/p')"
        uidrange="$(printf '%s\n' "$line" | sed -n 's/.*uidrange \([^ ]*\).*/\1/p')"
        if [ -n "$mark" ]; then
            ip_run ip $prefix rule del fwmark "$mark/0xffffffff" table "$ROUTE_TABLE" pref "$priority"
        elif [ -n "$uidrange" ]; then
            ip_run ip $prefix rule del uidrange "$uidrange" table "$ROUTE_TABLE" pref "$priority"
        fi
    done
    ip_run ip $prefix route show table "$ROUTE_TABLE"
    [ -z "$CMD_STDOUT" ] || ip_run ip $prefix route del local "$route" dev lo table "$ROUTE_TABLE"
}

scan_table() {
    binary="$1"
    table="$2"
    file="$(table_file "$binary" "$table")"
    [ "$(cat "$file.state" 2>/dev/null)" = AVAILABLE ] || return 0
    while IFS= read -r line; do
        set -- $line
        [ "$1" = "-A" ] || {
            case "$line" in -N\ KBX_*) REMAINING_CHAINS="${REMAINING_CHAINS}${REMAINING_CHAINS:+;}[$binary,$table]$line" ;; esac
            continue
        }
        parent="$2"
        target=""
        previous=""
        for token in "$@"; do
            [ "$previous" = "-j" ] && target="$token"
            previous="$token"
        done
        if known_chain "$target" && ! known_chain "$parent"; then
            REMAINING_RULES="${REMAINING_RULES}${REMAINING_RULES:+;}[$binary,$table]$line"
        elif known_chain "$parent"; then
            case "$target" in
                DROP|REJECT|REDIRECT|TPROXY|MARK|KBX_*)
                    REMAINING_RULES="${REMAINING_RULES}${REMAINING_RULES:+;}[$binary,$table]$line" ;;
            esac
        fi
    done < "$file"
}

verify_policy_family() {
    family="$1"
    [ "$family" = 6 ] && prefix="-6" || prefix=""
    ip_run ip $prefix rule show
    [ "$CMD_STATUS" -eq 0 ] || record_failure "verify_rule_query:$family exitCode=$CMD_STATUS stderr=$(diag "$CMD_STDERR")"
    while IFS= read -r line; do
        owned_policy_line "$line" "$family" || continue
        REMAINING_POLICY="${REMAINING_POLICY}${REMAINING_POLICY:+;}family=$family rule=$line"
    done <<EOF
$CMD_STDOUT
EOF
    ip_run ip $prefix route show table "$ROUTE_TABLE"
    [ "$CMD_STATUS" -eq 0 ] || record_failure "verify_route_query:$family exitCode=$CMD_STATUS stderr=$(diag "$CMD_STDERR")"
    [ -z "$CMD_STDOUT" ] || REMAINING_POLICY="${REMAINING_POLICY}${REMAINING_POLICY:+;}family=$family route=$(diag "$CMD_STDOUT")"
}

verify_nft() {
    command -v nft >/dev/null 2>&1 || return 0
    nft_error_file="$RUNTIME_DIR/.nft-error.$$"
    nft_output="$(nft -a list ruleset 2>"$nft_error_file")"
    nft_status=$?
    nft_error="$(cat "$nft_error_file" 2>/dev/null)"
    rm -f "$nft_error_file"
    printf '[ROOT_NET_QUERY] backend=nftables exactCommand="nft -a list ruleset" exitCode=%s stdout="%s" stderr="%s"\n' \
        "$nft_status" "$(diag "$nft_output")" "$(diag "$nft_error")" >&2
    [ "$nft_status" -eq 0 ] || { record_failure "nft_query exitCode=$nft_status stderr=$(diag "$nft_error")"; return; }
    REMAINING_NFT="$(printf '%s\n' "$nft_output" | grep -E 'jump KBX_|goto KBX_' 2>/dev/null)"
}

verify_state() {
    REMAINING_RULES=""
    REMAINING_CHAINS=""
    REMAINING_POLICY=""
    REMAINING_NFT=""
    for spec in $TABLE_SPECS; do
        split_spec "$spec"
        query_table "$SPEC_BINARY" "$SPEC_TABLE"
        scan_table "$SPEC_BINARY" "$SPEC_TABLE"
    done
    verify_policy_family 4
    verify_policy_family 6
    verify_nft
    printf '[ROOT_NET_CLEANUP] remaining_owned_rules="%s" remaining_owned_chains="%s" policy="%s" nft="%s" failures="%s"\n' \
        "$(diag "$REMAINING_RULES")" "$(diag "$REMAINING_CHAINS")" "$(diag "$REMAINING_POLICY")" \
        "$(diag "$REMAINING_NFT")" "$(diag "$FAILURES")" >&2
    [ -z "$REMAINING_RULES$REMAINING_POLICY$REMAINING_NFT" ] || record_failure harmful_state_remaining
    [ -z "$FAILURES" ]
}

validate_session() {
    expected="$1"
    [ -n "$expected" ] || return 0
    owner="$STAGING_FILE"
    [ -f "$owner" ] || owner="$OWNER_FILE"
    actual="$(sed -n 's/^session=//p' "$owner" 2>/dev/null | head -n 1)"
    [ "$actual" = "$expected" ] || exit 0
}

cleanup() {
    validate_session "$1"
    acquire_lock || exit $?
    FAILURES=""
    for spec in $TABLE_SPECS; do
        split_spec "$spec"
        query_table "$SPEC_BINARY" "$SPEC_TABLE"
    done
    delete_known_chains
    delete_policy_family 4
    delete_policy_family 6
    if verify_state; then
        rm -f "$OWNER_FILE" "$STAGING_FILE" "$CONFLICT_FILE"
        exit 0
    fi
    reason="NETFILTER_VERIFICATION_FAILED:rules=$(diag "$REMAINING_RULES") chains=$(diag "$REMAINING_CHAINS") policy=$(diag "$REMAINING_POLICY") nft=$(diag "$REMAINING_NFT") failures=$(diag "$FAILURES")"
    printf '%s\n' "$reason" > "$CONFLICT_FILE"
    printf '%s\n' "$reason" >&2
    exit 75
}

case "$1" in
    cleanup) cleanup "$2" ;;
    legacy-cleanup) cleanup "" ;;
    *) exit 64 ;;
esac
