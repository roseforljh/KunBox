#!/usr/bin/env sh

BINARY="${0##*/}"
printf '%s %s\n' "$BINARY" "$*" >> "$MOCK_COMMAND_LOG"

case "$BINARY" in
    ip)
        exit 0
        ;;
    iptables-save|ip6tables-save)
        if [ "$MOCK_MODE" = "syntax_failure" ] && [ "$BINARY" = "ip6tables-save" ]; then
            printf '%s\n' 'mock backend failure' >&2
            exit 2
        fi
        if [ "$MOCK_MODE" = "ipv6_nat_absent_wait_unsupported" ] && \
            [ "$BINARY" = "ip6tables-save" ] && [ "$1" = "-w" ]; then
            printf '%s\n' 'ip6tables-save: invalid option -- w' >&2
            exit 1
        fi
        if [ "$MOCK_MODE" = "ipv6_nat_absent" ] && [ "$BINARY" = "ip6tables-save" ]; then
            printf '%s\n' '*mangle' ':OUTPUT ACCEPT [0:0]' 'COMMIT'
            printf '%s\n' '*filter' ':INPUT ACCEPT [0:0]' ':OUTPUT ACCEPT [0:0]' 'COMMIT'
            exit 0
        fi
        if [ "$MOCK_MODE" = "ipv6_nat_absent_wait_unsupported" ] && [ "$BINARY" = "ip6tables-save" ]; then
            printf '%s\n' '*mangle' ':OUTPUT ACCEPT [0:0]' 'COMMIT'
            printf '%s\n' '*filter' ':INPUT ACCEPT [0:0]' ':OUTPUT ACCEPT [0:0]' 'COMMIT'
            exit 0
        fi
        printf '%s\n' '*nat' ':OUTPUT ACCEPT [0:0]' 'COMMIT'
        exit 0
        ;;
esac

if [ "$1" = "-V" ]; then
    printf '%s v1.8.9 (nf_tables)\n' "$BINARY"
    exit 0
fi

TABLE="filter"
OPERATION=""
OPERATION_ARGUMENT=""
TARGET=""
EXPECT_OPERATION_ARGUMENT=0
PREVIOUS=""
for ARGUMENT in "$@"; do
    [ "$PREVIOUS" = "-t" ] && TABLE="$ARGUMENT"
    if [ "$EXPECT_OPERATION_ARGUMENT" -eq 1 ]; then
        OPERATION_ARGUMENT="$ARGUMENT"
        EXPECT_OPERATION_ARGUMENT=0
    fi
    [ "$PREVIOUS" = "-j" ] && TARGET="$ARGUMENT"
    case "$ARGUMENT" in
        -S|-C|-D|-F|-X)
            OPERATION="$ARGUMENT"
            EXPECT_OPERATION_ARGUMENT=1
            ;;
    esac
    PREVIOUS="$ARGUMENT"
done

if [ "$BINARY" = "ip6tables" ] && [ "$TABLE" = "nat" ] && [ "$OPERATION" = "-S" ]; then
    case "$MOCK_MODE" in
        ipv6_nat_absent|ipv6_nat_absent_wait_unsupported)
            exit 3
            ;;
        syntax_failure)
            printf '%s\n' 'mock syntax or backend failure' >&2
            exit 2
            ;;
    esac
fi

RULES_FILE="$MOCK_STATE_DIR/$BINARY-$TABLE.rules"
case "$OPERATION" in
    -S)
        [ -f "$RULES_FILE" ] && sed -n '1,200p' "$RULES_FILE"
        exit 0
        ;;
    -C)
        exit 1
        ;;
    -D)
        if [ -f "$RULES_FILE" ]; then
            awk -v hook="$OPERATION_ARGUMENT" -v target="$TARGET" '
                {
                    remove=0
                    if ($1 == "-A" && $2 == hook) {
                        for (i=3; i<NF; i++) if ($i == "-j" && $(i+1) == target) remove=1
                    }
                    if (!remove) print
                }
            ' "$RULES_FILE" > "$RULES_FILE.tmp" && mv "$RULES_FILE.tmp" "$RULES_FILE"
        fi
        exit 0
        ;;
    -F)
        if [ -f "$RULES_FILE" ]; then
            awk -v chain="$OPERATION_ARGUMENT" '!($1 == "-A" && $2 == chain)' \
                "$RULES_FILE" > "$RULES_FILE.tmp" && mv "$RULES_FILE.tmp" "$RULES_FILE"
        fi
        exit 0
        ;;
    -X)
        if [ -f "$RULES_FILE" ]; then
            awk -v chain="$OPERATION_ARGUMENT" \
                '!($0 == "-N " chain || ($1 == "-A" && $2 == chain))' \
                "$RULES_FILE" > "$RULES_FILE.tmp" && mv "$RULES_FILE.tmp" "$RULES_FILE"
        fi
        exit 0
        ;;
esac

exit 0
