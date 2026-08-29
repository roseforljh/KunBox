# Root netfilter 只读诊断

在 `adb shell` 中执行 `su` 后粘贴。以下命令只读取，不修改规则。

## 本轮启动阻断的最小查询

每条命令后面的 `exit` 必须紧跟该命令，不能通过管道取代：

```sh
echo '=== ip6tables nat hook ==='
ip6tables -w 2 -t nat -S OUTPUT 2>&1
echo exit=$?

echo '=== ip6tables nat chain ==='
ip6tables -w 2 -t nat -S KBX_RED6 2>&1
echo exit=$?

echo '=== ip6tables nat rule check ==='
ip6tables -w 2 -t nat -C OUTPUT -j KBX_RED6 2>&1
echo exit=$?

echo '=== backend ==='
iptables --version 2>&1
echo exit=$?
ip6tables --version 2>&1
echo exit=$?
```

预期语义：`-S OUTPUT` 成功且没有 `KBX_RED6` 时是规则不存在；`-S KBX_RED6` 返回非零且完整表查询成功时是 chain 不存在；只有命令本身无法执行、表无法读取或权限/锁失败才是查询失败。`-C` 返回 `1` 只表示不匹配，不能直接当作查询失败。

```sh
echo '=== backend ==='
iptables -V 2>&1
ip6tables -V 2>&1
nft --version 2>&1

echo '=== iptables-save: KunBox only ==='
iptables-save 2>&1 | grep -F 'KBX_'
ip6tables-save 2>&1 | grep -F 'KBX_'

echo '=== iptables tables: KunBox only ==='
iptables -t mangle -S 2>&1 | grep -F 'KBX_'
iptables -t nat -S 2>&1 | grep -F 'KBX_'
iptables -t filter -S 2>&1 | grep -F 'KBX_'
ip6tables -t mangle -S 2>&1 | grep -F 'KBX_'
ip6tables -t nat -S 2>&1 | grep -F 'KBX_'
ip6tables -t filter -S 2>&1 | grep -F 'KBX_'

echo '=== nftables: KunBox only, with context ==='
nft -a list ruleset 2>&1 | grep -F -B 2 -A 2 'KBX_'

echo '=== IPv4 KunBox policy rules ==='
ip rule show 2>&1 | awk '
index($0, "lookup 20231") || index($0, "table 20231") ||
index($0, "fwmark 0x2331") || index($0, "fwmark 0x24") ||
($1 + 0 == 12031) || ($1 + 0 >= 12100 && $1 + 0 <= 12227)'

echo '=== IPv6 KunBox policy rules ==='
ip -6 rule show 2>&1 | awk '
index($0, "lookup 20231") || index($0, "table 20231") ||
index($0, "fwmark 0x2332") || index($0, "fwmark 0x25") ||
($1 + 0 == 12032) || ($1 + 0 >= 12300 && $1 + 0 <= 12427)'

echo '=== KunBox routing table 20231 ==='
ip route show table 20231 2>&1
ip -6 route show table 20231 2>&1

echo '=== KunBox ipset names ==='
ipset list -n 2>&1 | grep -F 'KBX_'
```

源码实际 ownership：

- chain：`KBX_OUT4`、`KBX_PRE4`、`KBX_IN4`、`KBX_RED4`、`KBX_BLOCK4`、`KBX_QUIC4`、`KBX_GUARD4` 及对应 IPv6 名称
- route table：`20231`
- 通用 mark/priority：IPv4 `0x2331/12031`，IPv6 `0x2332/12032`
- 分流 mark/priority：IPv4 `0x2400..0x247f/12100..12227`，IPv6 `0x2500..0x257f/12300..12427`

