#!/system/bin/sh

RUNTIME_DIR="/data/adb/kunbox"
OWNER_FILE="$RUNTIME_DIR/netfilter-owner"
STAGING_FILE="$RUNTIME_DIR/netfilter-owner.staging"
CONFLICT_FILE="$RUNTIME_DIR/cleanup_conflict"
ROUTE_TABLE="20231"
ROUTE_PROTOCOL="233"

fail_conflict() {
    printf '%s\n' "$1" > "$CONFLICT_FILE"
    exit 75
}

owner_path() {
    if [ -L "$STAGING_FILE" ] || [ -e "$STAGING_FILE" ] && [ ! -f "$STAGING_FILE" ]; then return 2; fi
    if [ -L "$OWNER_FILE" ] || [ -e "$OWNER_FILE" ] && [ ! -f "$OWNER_FILE" ]; then return 2; fi
    [ -f "$STAGING_FILE" ] && printf '%s\n' "$STAGING_FILE" && return 0
    [ -f "$OWNER_FILE" ] && printf '%s\n' "$OWNER_FILE" && return 0
    return 1
}

chain_live() {
    FAMILY="$1"
    TABLE="$2"
    CHAIN="$3"
    if [ "$FAMILY" = "6" ]; then BIN="ip6tables"; else BIN="iptables"; fi
    "$BIN" -t "$TABLE" -S "$CHAIN" 2>/dev/null
}

# 0=present, 1=absent, 2=query failed or inconsistent.
chain_status() {
    FAMILY="$1"
    TABLE="$2"
    CHAIN="$3"
    if [ "$FAMILY" = "6" ]; then BIN="ip6tables"; else BIN="iptables"; fi
    "$BIN" -t "$TABLE" -S "$CHAIN" >/dev/null 2>&1 && return 0
    ALL_RULES="$("$BIN" -t "$TABLE" -S 2>/dev/null)" || return 2
    printf '%s\n' "$ALL_RULES" | awk -v expected="-N $CHAIN" '$0==expected{found=1} END{exit found?0:1}' && return 2
    return 1
}

sha256_text() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum | awk '{print $1}'
    elif command -v toybox >/dev/null 2>&1; then
        toybox sha256sum | awk '{print $1}'
    else
        return 127
    fi
}

is_sha256() {
    VALUE="$1"
    case "$VALUE" in
        ''|*[!0-9a-f]*) return 1 ;;
    esac
    [ "$(printf '%s' "$VALUE" | awk '{print length}')" -eq 64 ]
}

is_decimal() {
    case "$1" in
        ''|*[!0-9]*) return 1 ;;
    esac
    return 0
}

owned_rule_count() {
    RC_FAMILY="$1"
    RC_MARK="$2"
    RC_MASK="$3"
    RC_PRIORITY="$4"
    RC_TABLE="$5"
    RC_PROTOCOL="$6"
    if [ "$RC_FAMILY" = "6" ]; then
        RC_OUTPUT="$(ip -6 rule show 2>/dev/null)" || return 2
    else
        RC_OUTPUT="$(ip rule show 2>/dev/null)" || return 2
    fi
    printf '%s\n' "$RC_OUTPUT" | awk \
        -v p="$RC_PRIORITY:" -v m="$RC_MARK" -v full="$RC_MARK/$RC_MASK" -v t="$RC_TABLE" -v r="$RC_PROTOCOL" \
        '$1==p && $2=="from" && $3=="all" {\
            mark=lookup=0; protocol=(r=="0");\
            for (i=1; i<=NF; i++) {\
                if ($i=="fwmark" && ($(i+1)==m || $(i+1)==full)) mark=1;\
                if (($i=="lookup" || $i=="table") && $(i+1)==t) lookup=1;\
                if (($i=="protocol" || $i=="proto") && $(i+1)==r) protocol=1;\
            }\
            if (mark && lookup && protocol) c++\
        } END{print c+0}'
}

owned_route_count() {
    RT_FAMILY="$1"
    RT_PREFIX="$2"
    RT_DEVICE="$3"
    RT_TABLE="$4"
    RT_PROTOCOL="$5"
    if [ "$RT_FAMILY" = "6" ]; then
        RT_OUTPUT="$(ip -6 route show table "$RT_TABLE" 2>/dev/null)" || return 2
    else
        RT_OUTPUT="$(ip route show table "$RT_TABLE" 2>/dev/null)" || return 2
    fi
    printf '%s\n' "$RT_OUTPUT" | awk -v p="$RT_PREFIX" -v d="$RT_DEVICE" -v r="$RT_PROTOCOL" \
        '$1=="local" && (($2==p) || (p=="0.0.0.0/0" && $2=="default")) {\
            dev=0; protocol=(r=="0");\
            for (i=1; i<=NF; i++) {\
                if ($i=="dev" && $(i+1)==d) dev=1;\
                if (($i=="proto" || $i=="protocol") && $(i+1)==r) protocol=1;\
            }\
            if (dev && protocol) c++\
        } END{print c+0}'
}

is_owned_chain() {
    case "$1" in
        KBX_OUT4|KBX_PRE4|KBX_IN4|KBX_RED4|KBX_OUT6|KBX_PRE6|KBX_IN6|KBX_RED6|KBX_BLOCK4|KBX_BLOCK6|KBX_QUIC4|KBX_QUIC6|KBX_GUARD4|KBX_GUARD6) return 0 ;;
        *) return 1 ;;
    esac
}

is_owned_chain_table() {
    FAMILY="$1"
    CHAIN="$2"
    TABLE="$3"
    case "$FAMILY:$CHAIN:$TABLE" in
        4:KBX_OUT4:mangle|4:KBX_PRE4:mangle|4:KBX_IN4:filter|4:KBX_RED4:nat|4:KBX_BLOCK4:filter|4:KBX_QUIC4:filter|4:KBX_GUARD4:filter|6:KBX_OUT6:mangle|6:KBX_PRE6:mangle|6:KBX_IN6:filter|6:KBX_RED6:nat|6:KBX_BLOCK6:filter|6:KBX_QUIC6:filter|6:KBX_GUARD6:filter) return 0 ;;
        *) return 1 ;;
    esac
}

is_owned_chain_hook() {
    FAMILY="$1"
    CHAIN="$2"
    HOOK="$3"
    case "$FAMILY:$CHAIN:$HOOK" in
        4:KBX_OUT4:OUTPUT|4:KBX_PRE4:PREROUTING|4:KBX_IN4:INPUT|4:KBX_RED4:OUTPUT|4:KBX_BLOCK4:OUTPUT|4:KBX_QUIC4:OUTPUT|4:KBX_GUARD4:OUTPUT|6:KBX_OUT6:OUTPUT|6:KBX_PRE6:PREROUTING|6:KBX_IN6:INPUT|6:KBX_RED6:OUTPUT|6:KBX_BLOCK6:OUTPUT|6:KBX_QUIC6:OUTPUT|6:KBX_GUARD6:OUTPUT) return 0 ;;
        *) return 1 ;;
    esac
}

is_owned_rule_tuple() {
    FAMILY="$1"
    MARK="$2"
    PRIORITY="$3"
    if [ "$FAMILY" = "4" ]; then
        [ "$MARK" = "0x2331" ] && [ "$PRIORITY" = "12031" ] && return 0
        SLOT=0
        while [ "$SLOT" -lt 128 ]; do
            [ "$MARK" = "$(printf '0x%x' $((0x2400 + SLOT)))" ] &&
                [ "$PRIORITY" = "$((12100 + SLOT))" ] && return 0
            SLOT=$((SLOT + 1))
        done
    else
        [ "$MARK" = "0x2332" ] && [ "$PRIORITY" = "12032" ] && return 0
        SLOT=0
        while [ "$SLOT" -lt 128 ]; do
            [ "$MARK" = "$(printf '0x%x' $((0x2500 + SLOT)))" ] &&
                [ "$PRIORITY" = "$((12300 + SLOT))" ] && return 0
            SLOT=$((SLOT + 1))
        done
    fi
    return 1
}

validate_owner_records() {
    RECORD_COUNT=0
    exec 3< "$FILE"
    IFS= read -r HEADER1 <&3
    IFS= read -r HEADER2 <&3
    IFS= read -r HEADER3 <&3
    IFS= read -r HEADER4 <&3
    [ "$HEADER1" = "schema=1" ] || fail_conflict "owner_schema"
    case "$HEADER2" in session=*) : ;; *) fail_conflict "owner_session" ;; esac
    case "$HEADER3" in generation=*) : ;; *) fail_conflict "owner_generation" ;; esac
    case "$HEADER4" in resolved_plan_sha256=*) : ;; *) fail_conflict "owner_digest" ;; esac
    [ "$HEADER2" = "session=$SESSION" ] || fail_conflict "owner_session"
    [ "$HEADER3" = "generation=$GENERATION" ] || fail_conflict "owner_generation"
    [ "$HEADER4" = "resolved_plan_sha256=$DIGEST" ] || fail_conflict "owner_digest"
    while IFS='|' read -r TYPE A B C D E F G H; do
        [ -n "$TYPE" ] || fail_conflict "empty_record"
        case "$TYPE" in
            RULE)
                [ -z "$H" ] || fail_conflict "rule_record_fields"
                [ "$A" = "4" ] || [ "$A" = "6" ] || fail_conflict "rule_family"
                case "$B" in
                    0x*) case "${B#0x}" in ''|*[!0-9a-f]*) fail_conflict "rule_mark" ;; esac ;;
                    *) fail_conflict "rule_mark" ;;
                esac
                [ "$C" = "0xffffffff" ] || fail_conflict "rule_mask"
                is_decimal "$D" || fail_conflict "rule_priority"
                [ "$E" = "$ROUTE_TABLE" ] || fail_conflict "rule_table"
                [ "$F" = "0" ] || [ "$F" = "$ROUTE_PROTOCOL" ] || fail_conflict "rule_protocol"
                is_owned_rule_tuple "$A" "$B" "$D" || fail_conflict "rule_tuple"
                is_sha256 "$G" || fail_conflict "rule_command_sha"
                if [ "$A" = "6" ]; then IP_PREFIX="ip -6"; else IP_PREFIX="ip"; fi
                if [ "$F" = "0" ]; then
                    EXPECTED_RULE="$IP_PREFIX rule add fwmark $B/$C table $E pref $D"
                else
                    EXPECTED_RULE="$IP_PREFIX rule add fwmark $B/$C table $E pref $D protocol $F"
                fi
                EXPECTED_SHA="$(printf '%s' "$EXPECTED_RULE" | sha256_text)" ||
                    fail_conflict "sha256_unavailable"
                [ "$EXPECTED_SHA" = "$G" ] || fail_conflict "rule_command_sha"
                RECORD_COUNT=$((RECORD_COUNT + 1))
                ;;
            ROUTE)
                [ -z "$G" ] && [ -z "$H" ] || fail_conflict "route_record_fields"
                [ "$A" = "4" ] || [ "$A" = "6" ] || fail_conflict "route_family"
                if [ "$A" = "4" ]; then [ "$B" = "0.0.0.0/0" ]; else [ "$B" = "::/0" ]; fi ||
                    fail_conflict "route_prefix"
                [ "$C" = "lo" ] || fail_conflict "route_device"
                [ "$D" = "$ROUTE_TABLE" ] || fail_conflict "route_table"
                [ "$E" = "0" ] || [ "$E" = "$ROUTE_PROTOCOL" ] || fail_conflict "route_protocol"
                is_sha256 "$F" || fail_conflict "route_command_sha"
                if [ "$A" = "6" ]; then IP_PREFIX="ip -6"; else IP_PREFIX="ip"; fi
                if [ "$E" = "0" ]; then
                    EXPECTED_ROUTE="$IP_PREFIX route add local $B dev $C table $D"
                else
                    EXPECTED_ROUTE="$IP_PREFIX route add local $B dev $C table $D proto $E"
                fi
                EXPECTED_SHA="$(printf '%s' "$EXPECTED_ROUTE" | sha256_text)" ||
                    fail_conflict "sha256_unavailable"
                [ "$EXPECTED_SHA" = "$F" ] || fail_conflict "route_command_sha"
                RECORD_COUNT=$((RECORD_COUNT + 1))
                ;;
            CHAIN)
                [ -z "$F" ] && [ -z "$G" ] && [ -z "$H" ] || fail_conflict "chain_record_fields"
                [ "$A" = "4" ] || [ "$A" = "6" ] || fail_conflict "chain_family"
                is_owned_chain "$C" || fail_conflict "chain_name"
                case "$B" in mangle|nat|filter) : ;; *) fail_conflict "chain_table" ;; esac
                is_owned_chain_table "$A" "$C" "$B" || fail_conflict "chain_table"
                case "$D" in ''|OUTPUT|PREROUTING|INPUT) : ;; *) fail_conflict "chain_hook" ;; esac
                if [ -n "$D" ]; then
                    is_owned_chain_hook "$A" "$C" "$D" || fail_conflict "chain_hook"
                fi
                is_sha256 "$E" || fail_conflict "chain_fingerprint"
                RECORD_COUNT=$((RECORD_COUNT + 1))
                ;;
            *)
                fail_conflict "unknown_record"
                ;;
        esac
    done <&3
    exec 3<&-
    [ "$RECORD_COUNT" -gt 0 ] || fail_conflict "owner_records_empty"

    DUPLICATE_RECORD="$(awk -F'|' 'NR > 4 {
        if ($1 == "RULE") key=$1 "|" $2 "|" $5 "|" $3;
        else if ($1 == "ROUTE") key=$1 "|" $2 "|" $3 "|" $5;
        else if ($1 == "CHAIN") key=$1 "|" $2 "|" $3 "|" $4;
        else key=$0;
        if (++seen[key] > 1) { print key; exit 0 }
    }' "$FILE")"
    [ -z "$DUPLICATE_RECORD" ] || fail_conflict "duplicate_record"
}

cleanup_owned() {
    EXPECTED_SESSION="$1"
    FILE="$(owner_path)"
    OWNER_STATUS=$?
    if [ "$OWNER_STATUS" -eq 1 ]; then
        legacy_cleanup
        return $?
    fi
    [ "$OWNER_STATUS" -eq 0 ] || fail_conflict "owner_path"
    if [ "$FILE" = "$STAGING_FILE" ]; then STAGING=1; else STAGING=0; fi
    SCHEMA="$(sed -n '1s/^schema=//p' "$FILE")"
    SESSION="$(sed -n '2s/^session=//p' "$FILE")"
    GENERATION="$(sed -n '3s/^generation=//p' "$FILE")"
    DIGEST="$(sed -n '4s/^resolved_plan_sha256=//p' "$FILE")"
    [ "$SCHEMA" = "1" ] || fail_conflict "owner_schema"
    [ -n "$SESSION" ] || fail_conflict "owner_session"
    case "$SESSION" in *[!A-Za-z0-9._-]*) fail_conflict "owner_session" ;; esac
    [ -z "$EXPECTED_SESSION" ] || [ "$SESSION" = "$EXPECTED_SESSION" ] || fail_conflict "owner_session_mismatch"
    is_decimal "$GENERATION" || fail_conflict "owner_generation"
    [ "$GENERATION" -gt 0 ] || fail_conflict "owner_generation"
    is_sha256 "$DIGEST" || fail_conflict "owner_digest"

    validate_owner_records

    while IFS='|' read -r TYPE FAMILY TABLE CHAIN HOOK RULES_SHA EXTRA; do
        [ "$TYPE" = "CHAIN" ] || continue
        [ -z "$EXTRA" ] || fail_conflict "chain_record_fields"
        chain_status "$FAMILY" "$TABLE" "$CHAIN"
        STATUS=$?
        [ "$STATUS" -eq 1 ] && continue
        [ "$STATUS" -eq 0 ] || fail_conflict "chain_query:$CHAIN"
        if [ "$STAGING" -eq 1 ]; then
            legacy_chain_template_ok "$FAMILY" "$TABLE" "$CHAIN" || fail_conflict "chain_template:$CHAIN"
        else
            LIVE="$(chain_live "$FAMILY" "$TABLE" "$CHAIN")" || fail_conflict "chain_query:$CHAIN"
            LIVE_SHA="$(printf '%s' "$LIVE" | sha256_text)" || fail_conflict "sha256_unavailable"
            [ "$LIVE_SHA" = "$RULES_SHA" ] || fail_conflict "chain_fingerprint:$CHAIN"
        fi
            if [ -n "$HOOK" ]; then
                if [ "$FAMILY" = "6" ]; then BIN="ip6tables"; else BIN="iptables"; fi
            HOOK_OUTPUT="$($BIN -t "$TABLE" -S "$HOOK" 2>/dev/null)" || fail_conflict "hook_query:$CHAIN"
            HOOK_COUNT="$(printf '%s\n' "$HOOK_OUTPUT" | \
                awk -v expected="-A $HOOK -j $CHAIN" '$0==expected{c++} END{print c+0}')"
            is_decimal "$HOOK_COUNT" || fail_conflict "hook_query:$CHAIN"
            [ "$HOOK_COUNT" -le 1 ] || fail_conflict "hook_duplicate:$CHAIN"
        fi
    done < "$FILE"

    while IFS='|' read -r TYPE FAMILY MARK MASK PRIORITY TABLE PROTOCOL COMMAND_SHA EXTRA; do
        [ "$TYPE" = "RULE" ] || continue
        [ -z "$EXTRA" ] || fail_conflict "rule_record_fields"
        [ "$TABLE" = "$ROUTE_TABLE" ] && { [ "$PROTOCOL" = "0" ] || [ "$PROTOCOL" = "$ROUTE_PROTOCOL" ]; } ||
            fail_conflict "rule_scope"
        COUNT="$(owned_rule_count "$FAMILY" "$MARK" "$MASK" "$PRIORITY" "$TABLE" "$PROTOCOL")" || \
            fail_conflict "rule_query:$FAMILY:$PRIORITY"
        is_decimal "$COUNT" || fail_conflict "rule_query:$FAMILY:$PRIORITY"
        [ "$COUNT" -le 1 ] || fail_conflict "rule_fingerprint:$FAMILY:$PRIORITY"
    done < "$FILE"

    while IFS='|' read -r TYPE FAMILY PREFIX DEVICE TABLE PROTOCOL COMMAND_SHA EXTRA; do
        [ "$TYPE" = "ROUTE" ] || continue
        [ -z "$EXTRA" ] || fail_conflict "route_record_fields"
        [ "$TABLE" = "$ROUTE_TABLE" ] && { [ "$PROTOCOL" = "0" ] || [ "$PROTOCOL" = "$ROUTE_PROTOCOL" ]; } ||
            fail_conflict "route_scope"
        COUNT="$(owned_route_count "$FAMILY" "$PREFIX" "$DEVICE" "$TABLE" "$PROTOCOL")" || \
            fail_conflict "route_query:$FAMILY:$PREFIX"
        is_decimal "$COUNT" || fail_conflict "route_query:$FAMILY:$PREFIX"
        [ "$COUNT" -le 1 ] || fail_conflict "route_fingerprint:$FAMILY:$PREFIX"
    done < "$FILE"

    while IFS='|' read -r TYPE FAMILY TABLE CHAIN HOOK RULES_SHA EXTRA; do
        [ "$TYPE" = "CHAIN" ] || continue
        if [ "$FAMILY" = "6" ]; then BIN="ip6tables"; else BIN="iptables"; fi
        if [ -n "$HOOK" ]; then
            while "$BIN" -t "$TABLE" -D "$HOOK" -j "$CHAIN" 2>/dev/null; do :; done
        fi
    done < "$FILE"
    while IFS='|' read -r TYPE FAMILY MARK MASK PRIORITY TABLE PROTOCOL COMMAND_SHA EXTRA; do
        [ "$TYPE" = "RULE" ] || continue
        COUNT="$(owned_rule_count "$FAMILY" "$MARK" "$MASK" "$PRIORITY" "$TABLE" "$PROTOCOL")" || \
            fail_conflict "rule_query:$FAMILY:$PRIORITY"
        is_decimal "$COUNT" || fail_conflict "rule_query:$FAMILY:$PRIORITY"
        [ "$COUNT" -le 1 ] || fail_conflict "rule_fingerprint:$FAMILY:$PRIORITY"
        if [ "$COUNT" -eq 1 ]; then
            if [ "$PROTOCOL" = "0" ]; then
                if [ "$FAMILY" = "6" ]; then IP_PREFIX="ip -6"; else IP_PREFIX="ip"; fi
                $IP_PREFIX rule del fwmark "$MARK/$MASK" table "$TABLE" pref "$PRIORITY" 2>/dev/null ||
                    fail_conflict "rule_delete:$FAMILY:$PRIORITY"
            elif [ "$FAMILY" = "6" ]; then
                ip -6 rule del fwmark "$MARK/$MASK" table "$TABLE" pref "$PRIORITY" protocol "$PROTOCOL" \
                    2>/dev/null || fail_conflict "rule_delete:$FAMILY:$PRIORITY"
            else
                ip rule del fwmark "$MARK/$MASK" table "$TABLE" pref "$PRIORITY" protocol "$PROTOCOL" \
                    2>/dev/null || fail_conflict "rule_delete:$FAMILY:$PRIORITY"
            fi
        fi
    done < "$FILE"
    while IFS='|' read -r TYPE FAMILY PREFIX DEVICE TABLE PROTOCOL COMMAND_SHA EXTRA; do
        [ "$TYPE" = "ROUTE" ] || continue
        COUNT="$(owned_route_count "$FAMILY" "$PREFIX" "$DEVICE" "$TABLE" "$PROTOCOL")" || \
            fail_conflict "route_query:$FAMILY:$PREFIX"
        is_decimal "$COUNT" || fail_conflict "route_query:$FAMILY:$PREFIX"
        [ "$COUNT" -le 1 ] || fail_conflict "route_fingerprint:$FAMILY:$PREFIX"
        if [ "$COUNT" -eq 1 ]; then
            if [ "$PROTOCOL" = "0" ]; then
                if [ "$FAMILY" = "6" ]; then IP_PREFIX="ip -6"; else IP_PREFIX="ip"; fi
                $IP_PREFIX route del local "$PREFIX" dev "$DEVICE" table "$TABLE" 2>/dev/null ||
                    fail_conflict "route_delete:$FAMILY:$PREFIX"
            elif [ "$FAMILY" = "6" ]; then
                ip -6 route del local "$PREFIX" dev "$DEVICE" table "$TABLE" proto "$PROTOCOL" 2>/dev/null || \
                    fail_conflict "route_delete:$FAMILY:$PREFIX"
            else
                ip route del local "$PREFIX" dev "$DEVICE" table "$TABLE" proto "$PROTOCOL" 2>/dev/null || \
                    fail_conflict "route_delete:$FAMILY:$PREFIX"
            fi
        fi
    done < "$FILE"
    while IFS='|' read -r TYPE FAMILY TABLE CHAIN HOOK RULES_SHA EXTRA; do
        [ "$TYPE" = "CHAIN" ] || continue
        if [ "$FAMILY" = "6" ]; then BIN="ip6tables"; else BIN="iptables"; fi
        chain_status "$FAMILY" "$TABLE" "$CHAIN"
        STATUS=$?
        if [ "$STATUS" -eq 0 ]; then
            "$BIN" -t "$TABLE" -F "$CHAIN" 2>/dev/null || fail_conflict "chain_flush:$CHAIN"
            "$BIN" -t "$TABLE" -X "$CHAIN" 2>/dev/null || fail_conflict "chain_delete:$CHAIN"
        elif [ "$STATUS" -ne 1 ]; then
            fail_conflict "chain_query:$CHAIN"
        fi
    done < "$FILE"

    while IFS='|' read -r TYPE FAMILY TABLE CHAIN HOOK RULES_SHA EXTRA; do
        [ "$TYPE" = "CHAIN" ] || continue
        chain_status "$FAMILY" "$TABLE" "$CHAIN"
        STATUS=$?
        [ "$STATUS" -eq 1 ] || fail_conflict "chain_present_after:$CHAIN"
    done < "$FILE"
    while IFS='|' read -r TYPE FAMILY MARK MASK PRIORITY TABLE PROTOCOL COMMAND_SHA EXTRA; do
        [ "$TYPE" = "RULE" ] || continue
        COUNT="$(owned_rule_count "$FAMILY" "$MARK" "$MASK" "$PRIORITY" "$TABLE" "$PROTOCOL")" || \
            fail_conflict "rule_query:$FAMILY:$PRIORITY"
        is_decimal "$COUNT" || fail_conflict "rule_query:$FAMILY:$PRIORITY"
        [ "$COUNT" -eq 0 ] || fail_conflict "rule_present_after:$FAMILY:$PRIORITY"
    done < "$FILE"
    while IFS='|' read -r TYPE FAMILY PREFIX DEVICE TABLE PROTOCOL COMMAND_SHA EXTRA; do
        [ "$TYPE" = "ROUTE" ] || continue
        COUNT="$(owned_route_count "$FAMILY" "$PREFIX" "$DEVICE" "$TABLE" "$PROTOCOL")" || \
            fail_conflict "route_query:$FAMILY:$PREFIX"
        is_decimal "$COUNT" || fail_conflict "route_query:$FAMILY:$PREFIX"
        [ "$COUNT" -eq 0 ] || fail_conflict "route_present_after:$FAMILY:$PREFIX"
    done < "$FILE"

    rm -f "$OWNER_FILE" "$STAGING_FILE" "$CONFLICT_FILE"
}

legacy_chain_template_ok() {
    LC_FAMILY="$1"
    LC_TABLE="$2"
    LC_CHAIN="$3"
    if [ "$LC_FAMILY" = "6" ]; then LC_BIN="ip6tables"; else LC_BIN="iptables"; fi
    LC_LIVE="$($LC_BIN -t "$LC_TABLE" -S "$LC_CHAIN" 2>/dev/null)" || return 2
    printf '%s\n' "$LC_LIVE" | awk \
        -v chain="$LC_CHAIN" -v family="$LC_FAMILY" \
        'function decimal(v) { return v ~ /^[0-9]+$/ && v+0 >= 1 && v+0 <= 65535 }
         function uid(v) { return v ~ /^[0-9]+(-[0-9]+)?$/ }
         function mark(v) {
             return v == "0x2331" || v == "0x2332" || v == "0x10000000" ||
                 v ~ /^0x24[0-7][0-9a-f]$/ || v ~ /^0x25[0-7][0-9a-f]$/ ||
                 v ~ /^0x(10000000|2331|2332|240[0-9a-f]|24[1-7][0-9a-f]|250[0-9a-f]|25[1-7][0-9a-f])\/0x[0-9a-f]+$/
         }
         function destination(v) {
             return (family == "4" && (v == "127.0.0.0/8" || v == "224.0.0.251")) ||
                 (family == "6" && (v == "::1/128" || v == "ff02::fb"))
         }
         function targetAllowed(t) {
             if (chain ~ /^KBX_OUT/) return t == "RETURN" || t == "MARK"
             if (chain ~ /^KBX_PRE/) return t == "RETURN" || t == "TPROXY"
             if (chain ~ /^KBX_RED/) return t == "RETURN" || t == "REDIRECT"
             if (chain ~ /^KBX_IN/) return t == "ACCEPT" || t == "REJECT"
             if (chain ~ /^KBX_BLOCK/ || chain ~ /^KBX_QUIC/ || chain ~ /^KBX_GUARD/) {
                 return t == "RETURN" || t == "REJECT"
             }
             return 0
         }
         BEGIN { ok=1 }
         NR == 1 { if ($0 != "-N " chain) ok=0; next }
         {
             if ($1 != "-A" || $2 != chain) { ok=0; next }
             target=""; jumps=0; skip=0
             for (i=3; i<=NF; i++) {
                 if (skip) { skip=0; continue }
                 token=$i
                 if (token == "-m") {
                     if ($(i+1) != "owner" && $(i+1) != "mark") ok=0
                     skip=1
                 } else if (token == "--uid-owner") {
                     if (!uid($(i+1))) ok=0
                     skip=1
                 } else if (token == "-p") {
                     if ($(i+1) != "tcp" && $(i+1) != "udp") ok=0
                     skip=1
                 } else if (token == "--dport" || token == "--to-ports" || token == "--on-port") {
                     if (!decimal($(i+1))) ok=0
                     skip=1
                 } else if (token == "-d") {
                     if (!destination($(i+1))) ok=0
                     skip=1
                 } else if (token == "-i") {
                     if ($(i+1) != "lo") ok=0
                     skip=1
                 } else if (token == "--mark") {
                     if (!mark($(i+1))) ok=0
                     skip=1
                 } else if (token == "--set-mark" || token == "--set-xmark" || token == "--tproxy-mark") {
                     if (!mark($(i+1))) ok=0
                     skip=1
                 } else if (token == "--reject-with") {
                     if ($(i+1) != "icmp-port-unreachable" && $(i+1) != "icmp6-port-unreachable" &&
                         $(i+1) != "tcp-reset") ok=0
                     skip=1
                 } else if (token == "-j") {
                     jumps++; target=$(i+1); skip=1
                 } else {
                     ok=0
                 }
             }
             if (jumps != 1 || !targetAllowed(target)) ok=0
         }
         END { exit(ok == 0 ? 1 : 0) }'
}

legacy_hook_count() {
    LH_BIN="$1"
    LH_TABLE="$2"
    LH_HOOK="$3"
    LH_CHAIN="$4"
    LH_OUTPUT="$($LH_BIN -t "$LH_TABLE" -S "$LH_HOOK" 2>/dev/null)" || return 2
    printf '%s\n' "$LH_OUTPUT" | awk -v expected="-A $LH_HOOK -j $LH_CHAIN" \
        '$0==expected{c++} END{print c+0}'
}

legacy_delete_hook() {
    LD_BIN="$1"
    LD_TABLE="$2"
    LD_HOOK="$3"
    LD_CHAIN="$4"
    LD_COUNT="$(legacy_hook_count "$LD_BIN" "$LD_TABLE" "$LD_HOOK" "$LD_CHAIN")" || return 2
    is_decimal "$LD_COUNT" || return 2
    [ "$LD_COUNT" -le 1 ] || return 3
    if [ "$LD_COUNT" -eq 1 ]; then
        "$LD_BIN" -t "$LD_TABLE" -D "$LD_HOOK" -j "$LD_CHAIN" 2>/dev/null || return 4
        LD_COUNT="$(legacy_hook_count "$LD_BIN" "$LD_TABLE" "$LD_HOOK" "$LD_CHAIN")" || return 2
        [ "$LD_COUNT" -eq 0 ] || return 5
    fi
    return 0
}

legacy_cleanup() {
    # Preflight every owned object before deleting any of them.
    for SPEC in \
        "iptables mangle KBX_OUT4" "iptables mangle KBX_PRE4" "iptables filter KBX_IN4" \
        "iptables nat KBX_RED4" "ip6tables mangle KBX_OUT6" "ip6tables mangle KBX_PRE6" \
        "ip6tables filter KBX_IN6" "ip6tables nat KBX_RED6" "iptables filter KBX_BLOCK4" \
        "ip6tables filter KBX_BLOCK6" "iptables filter KBX_QUIC4" "ip6tables filter KBX_QUIC6" \
        "iptables filter KBX_GUARD4" "ip6tables filter KBX_GUARD6"; do
        set -- $SPEC
        PREFLIGHT_FAMILY="$([ "$1" = "ip6tables" ] && printf 6 || printf 4)"
        chain_status "$PREFLIGHT_FAMILY" "$2" "$3"
        PREFLIGHT_STATUS=$?
        [ "$PREFLIGHT_STATUS" -eq 1 ] && continue
        [ "$PREFLIGHT_STATUS" -eq 0 ] || fail_conflict "legacy_chain_query:$3"
        legacy_chain_template_ok "$PREFLIGHT_FAMILY" "$2" "$3" || fail_conflict "legacy_chain_template:$3"
    done
    for SPEC in \
        "iptables mangle OUTPUT KBX_OUT4" "iptables mangle PREROUTING KBX_PRE4" \
        "iptables filter INPUT KBX_IN4" "iptables nat OUTPUT KBX_RED4" \
        "ip6tables mangle OUTPUT KBX_OUT6" "ip6tables mangle PREROUTING KBX_PRE6" \
        "ip6tables filter INPUT KBX_IN6" "ip6tables nat OUTPUT KBX_RED6" \
        "iptables filter OUTPUT KBX_BLOCK4" "ip6tables filter OUTPUT KBX_BLOCK6" \
        "iptables filter OUTPUT KBX_QUIC4" "ip6tables filter OUTPUT KBX_QUIC6" \
        "iptables filter OUTPUT KBX_GUARD4" "ip6tables filter OUTPUT KBX_GUARD6"; do
        set -- $SPEC
        PREFLIGHT_COUNT="$(legacy_hook_count "$1" "$2" "$3" "$4")" || fail_conflict "legacy_hook_query:$4"
        is_decimal "$PREFLIGHT_COUNT" || fail_conflict "legacy_hook_query:$4"
        [ "$PREFLIGHT_COUNT" -le 1 ] || fail_conflict "legacy_hook_duplicate:$4"
    done
    for SPEC in "4 0x2331 12031" "6 0x2332 12032"; do
        set -- $SPEC
        PREFLIGHT_COUNT="$(owned_rule_count "$1" "$2" "0xffffffff" "$3" "$ROUTE_TABLE" "0")" ||
            fail_conflict "legacy_rule_query:$1:$3"
        is_decimal "$PREFLIGHT_COUNT" || fail_conflict "legacy_rule_query:$1:$3"
        [ "$PREFLIGHT_COUNT" -le 1 ] || fail_conflict "legacy_rule_duplicate:$1:$3"
    done
    SLOT=0
    while [ "$SLOT" -lt 128 ]; do
        for SPEC in \
            "4 $(printf '0x%x' $((0x2400 + SLOT))) $((12100 + SLOT))" \
            "6 $(printf '0x%x' $((0x2500 + SLOT))) $((12300 + SLOT))"; do
            set -- $SPEC
            PREFLIGHT_COUNT="$(owned_rule_count "$1" "$2" "0xffffffff" "$3" "$ROUTE_TABLE" "0")" ||
                fail_conflict "legacy_rule_query:$1:$3"
            is_decimal "$PREFLIGHT_COUNT" || fail_conflict "legacy_rule_query:$1:$3"
            [ "$PREFLIGHT_COUNT" -le 1 ] || fail_conflict "legacy_rule_duplicate:$1:$3"
        done
        SLOT=$((SLOT + 1))
    done
    for SPEC in "4 0.0.0.0/0" "6 ::/0"; do
        set -- $SPEC
        PREFLIGHT_COUNT="$(owned_route_count "$1" "$2" lo "$ROUTE_TABLE" "0")" ||
            fail_conflict "legacy_route_query:$1"
        is_decimal "$PREFLIGHT_COUNT" || fail_conflict "legacy_route_query:$1"
        [ "$PREFLIGHT_COUNT" -le 1 ] || fail_conflict "legacy_route_duplicate:$1"
    done

    for SPEC in \
        "iptables mangle OUTPUT KBX_OUT4" "iptables mangle PREROUTING KBX_PRE4" \
        "iptables filter INPUT KBX_IN4" "iptables nat OUTPUT KBX_RED4" \
        "ip6tables mangle OUTPUT KBX_OUT6" "ip6tables mangle PREROUTING KBX_PRE6" \
        "ip6tables filter INPUT KBX_IN6" "ip6tables nat OUTPUT KBX_RED6" \
        "iptables filter OUTPUT KBX_BLOCK4" "ip6tables filter OUTPUT KBX_BLOCK6" \
        "iptables filter OUTPUT KBX_QUIC4" "ip6tables filter OUTPUT KBX_QUIC6" \
        "iptables filter OUTPUT KBX_GUARD4" "ip6tables filter OUTPUT KBX_GUARD6"; do
        set -- $SPEC
        legacy_delete_hook "$1" "$2" "$3" "$4" || fail_conflict "legacy_hook:$4"
    done
    for SPEC in \
        "iptables mangle KBX_OUT4" "iptables mangle KBX_PRE4" "iptables filter KBX_IN4" \
        "iptables nat KBX_RED4" "ip6tables mangle KBX_OUT6" "ip6tables mangle KBX_PRE6" \
        "ip6tables filter KBX_IN6" "ip6tables nat KBX_RED6" "iptables filter KBX_BLOCK4" \
        "ip6tables filter KBX_BLOCK6" "iptables filter KBX_QUIC4" "ip6tables filter KBX_QUIC6" \
        "iptables filter KBX_GUARD4" "ip6tables filter KBX_GUARD6"; do
        set -- $SPEC
        chain_status "$([ "$1" = "ip6tables" ] && printf 6 || printf 4)" "$2" "$3"
        LEGACY_CHAIN_STATUS=$?
        [ "$LEGACY_CHAIN_STATUS" -eq 1 ] && continue
        [ "$LEGACY_CHAIN_STATUS" -eq 0 ] || fail_conflict "legacy_chain_query:$3"
        legacy_chain_template_ok "$([ "$1" = "ip6tables" ] && printf 6 || printf 4)" "$2" "$3" || \
            fail_conflict "legacy_chain_template:$3"
        "$1" -t "$2" -F "$3" 2>/dev/null || fail_conflict "legacy_chain_flush:$3"
        "$1" -t "$2" -X "$3" 2>/dev/null || fail_conflict "legacy_chain_delete:$3"
        chain_status "$([ "$1" = "ip6tables" ] && printf 6 || printf 4)" "$2" "$3"
        [ "$?" -eq 1 ] || fail_conflict "legacy_chain_present:$3"
    done

    for SPEC in \
        "4 0x2331 12031" "6 0x2332 12032"; do
        set -- $SPEC
        COUNT="$(owned_rule_count "$1" "$2" "0xffffffff" "$3" "$ROUTE_TABLE" "0")" || \
            fail_conflict "legacy_rule_query:$1:$3"
        is_decimal "$COUNT" || fail_conflict "legacy_rule_query:$1:$3"
        [ "$COUNT" -le 1 ] || fail_conflict "legacy_rule_duplicate:$1:$3"
        if [ "$COUNT" -eq 1 ]; then
            if [ "$1" = "6" ]; then
                ip -6 rule del fwmark "$2/0xffffffff" table "$ROUTE_TABLE" pref "$3" 2>/dev/null ||
                    fail_conflict "legacy_rule_delete:$1:$3"
            else
                ip rule del fwmark "$2/0xffffffff" table "$ROUTE_TABLE" pref "$3" 2>/dev/null ||
                    fail_conflict "legacy_rule_delete:$1:$3"
            fi
        fi
        COUNT="$(owned_rule_count "$1" "$2" "0xffffffff" "$3" "$ROUTE_TABLE" "0")" || \
            fail_conflict "legacy_rule_query:$1:$3"
        [ "$COUNT" -eq 0 ] || fail_conflict "legacy_rule_present:$1:$3"
    done

    SLOT=0
    while [ "$SLOT" -lt 128 ]; do
        V4="$(printf '0x%x' $((0x2400 + SLOT)))"
        V6="$(printf '0x%x' $((0x2500 + SLOT)))"
        for SPEC in "4 $V4 $((12100 + SLOT))" "6 $V6 $((12300 + SLOT))"; do
            set -- $SPEC
            COUNT="$(owned_rule_count "$1" "$2" "0xffffffff" "$3" "$ROUTE_TABLE" "0")" || \
                fail_conflict "legacy_rule_query:$1:$3"
            is_decimal "$COUNT" || fail_conflict "legacy_rule_query:$1:$3"
            [ "$COUNT" -le 1 ] || fail_conflict "legacy_rule_duplicate:$1:$3"
            if [ "$COUNT" -eq 1 ]; then
                if [ "$1" = "6" ]; then
                    ip -6 rule del fwmark "$2/0xffffffff" table "$ROUTE_TABLE" pref "$3" 2>/dev/null ||
                        fail_conflict "legacy_rule_delete:$1:$3"
                else
                    ip rule del fwmark "$2/0xffffffff" table "$ROUTE_TABLE" pref "$3" 2>/dev/null ||
                        fail_conflict "legacy_rule_delete:$1:$3"
                fi
            fi
            COUNT="$(owned_rule_count "$1" "$2" "0xffffffff" "$3" "$ROUTE_TABLE" "0")" || \
                fail_conflict "legacy_rule_query:$1:$3"
            [ "$COUNT" -eq 0 ] || fail_conflict "legacy_rule_present:$1:$3"
        done
        SLOT=$((SLOT + 1))
    done

    for SPEC in "4 0.0.0.0/0" "6 ::/0"; do
        set -- $SPEC
        COUNT="$(owned_route_count "$1" "$2" lo "$ROUTE_TABLE" "0")" || \
            fail_conflict "legacy_route_query:$1"
        is_decimal "$COUNT" || fail_conflict "legacy_route_query:$1"
        [ "$COUNT" -le 1 ] || fail_conflict "legacy_route_duplicate:$1"
        if [ "$COUNT" -eq 1 ]; then
            if [ "$1" = "6" ]; then
                ip -6 route del local "$2" dev lo table "$ROUTE_TABLE" 2>/dev/null ||
                    fail_conflict "legacy_route_delete:$1"
            else
                ip route del local "$2" dev lo table "$ROUTE_TABLE" 2>/dev/null ||
                    fail_conflict "legacy_route_delete:$1"
            fi
        fi
        COUNT="$(owned_route_count "$1" "$2" lo "$ROUTE_TABLE" "0")" || \
            fail_conflict "legacy_route_query:$1"
        [ "$COUNT" -eq 0 ] || fail_conflict "legacy_route_present:$1"
    done
}

case "$1" in
    cleanup) cleanup_owned "$2" ;;
    legacy-cleanup) legacy_cleanup ;;
    *) exit 64 ;;
esac
