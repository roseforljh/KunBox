# Graph Report - .  (2026-05-18)

## Corpus Check
- 206 files · ~213,408 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 3302 nodes · 3098 edges · 206 communities detected
- Extraction: 100% EXTRACTED · 0% INFERRED · 0% AMBIGUOUS
- Token cost: 0 input · 0 output

## God Nodes (most connected - your core abstractions)
1. `ConfigRepository` - 155 edges
2. `ConfigRepositoryTest` - 130 edges
3. `SingBoxService` - 81 edges
4. `SettingsViewModel` - 75 edges
5. `SettingsRepository` - 65 edges
6. `NodeLinkParserTest` - 64 edges
7. `BoxWrapperManager` - 44 edges
8. `VpnStateStore` - 43 edges
9. `SingBoxRemote` - 42 edges
10. `CommandManager` - 35 edges

## Surprising Connections (you probably didn't know these)
- None detected - all connections are within the same source files.

## Communities

### Community 0 - "Community 0"
Cohesion: 0.01
Nodes (1): ConfigRepository

### Community 1 - "Community 1"
Cohesion: 0.02
Nodes (1): ConfigRepositoryTest

### Community 2 - "Community 2"
Cohesion: 0.02
Nodes (22): Block, ConfigGenerationResult, Direct, Failed, FakeIpRanges, FallbackProxy, FetchResult, NodeSwitchResult (+14 more)

### Community 3 - "Community 3"
Cohesion: 0.02
Nodes (9): ForegroundFallbackState, NetworkTypeChangedFallbackAction, NetworkTypeChangedFallbackState, NetworkTypeChangedRecoverySignal, RecoveryDebounceContext, RecoveryProfile, RecoveryReason, RecoveryRequest (+1 more)

### Community 4 - "Community 4"
Cohesion: 0.02
Nodes (12): DefaultRuleSetDownloadState, Error, Exporting, ExportState, Idle, Importing, ImportState, PartialSuccess (+4 more)

### Community 5 - "Community 5"
Cohesion: 0.02
Nodes (1): SingBoxService

### Community 6 - "Community 6"
Cohesion: 0.03
Nodes (1): SettingsRepository

### Community 7 - "Community 7"
Cohesion: 0.03
Nodes (3): Callbacks, PlatformInterfaceImpl, StringIteratorImpl

### Community 8 - "Community 8"
Cohesion: 0.03
Nodes (1): NodeLinkParserTest

### Community 9 - "Community 9"
Cohesion: 0.03
Nodes (5): ActiveConnection, SingBoxCore, StringIteratorImpl, TestCommandServerHandler, TestPlatformInterface

### Community 10 - "Community 10"
Cohesion: 0.03
Nodes (8): Callbacks, Cancelled, Failed, NeedPermission, ParallelInitResult, StartResult, StartupManager, Success

### Community 11 - "Community 11"
Cohesion: 0.04
Nodes (7): AlreadyConnected, DisconnectedStopState, Failed, HotReloadResult, Recovering, RecoveryResult, SingBoxRemote

### Community 12 - "Community 12"
Cohesion: 0.04
Nodes (0): 

### Community 13 - "Community 13"
Cohesion: 0.04
Nodes (5): Callbacks, FirstValidSelectionDecision, RouteGroupSelector, RouteGroupTarget, SelectionContext

### Community 14 - "Community 14"
Cohesion: 0.04
Nodes (4): BoxWrapperManager, RecoveryLevel, RecoveryMode, SmartRecoveryResult

### Community 15 - "Community 15"
Cohesion: 0.04
Nodes (2): CoreMode, VpnStateStore

### Community 16 - "Community 16"
Cohesion: 0.05
Nodes (2): Callbacks, CommandManager

### Community 17 - "Community 17"
Cohesion: 0.05
Nodes (5): NodeLinkParser, TuicCredentials, TuicTlsOptions, TuicTransportOptions, WebSocketPathConfig

### Community 18 - "Community 18"
Cohesion: 0.05
Nodes (2): ProxyOnlyService, StringIteratorImpl

### Community 19 - "Community 19"
Cohesion: 0.06
Nodes (3): Callbacks, ShutdownManager, ShutdownOptions

### Community 20 - "Community 20"
Cohesion: 0.06
Nodes (6): Cancelled, CoreManager, Failed, StartResult, StopResult, Success

### Community 21 - "Community 21"
Cohesion: 0.06
Nodes (1): RecoveryLogicTest

### Community 22 - "Community 22"
Cohesion: 0.06
Nodes (5): DailyTrafficRecord, NodeTrafficStats, TrafficPeriod, TrafficRepository, TrafficSummary

### Community 23 - "Community 23"
Cohesion: 0.07
Nodes (1): ClashYamlParser

### Community 24 - "Community 24"
Cohesion: 0.07
Nodes (2): DashboardViewModel, Quadruple

### Community 25 - "Community 25"
Cohesion: 0.07
Nodes (1): LibboxCompat

### Community 26 - "Community 26"
Cohesion: 0.07
Nodes (1): NodeDao

### Community 27 - "Community 27"
Cohesion: 0.07
Nodes (1): ProfileDao

### Community 28 - "Community 28"
Cohesion: 0.08
Nodes (3): HotReloadResult, SingBoxIpcHub, StateSnapshot

### Community 29 - "Community 29"
Cohesion: 0.08
Nodes (25): CacheFileConfig, ClashApiConfig, DnsConfig, DnsFakeIpConfig, DnsRule, DnsServer, DomainResolveConfig, EchConfig (+17 more)

### Community 30 - "Community 30"
Cohesion: 0.08
Nodes (20): AppRules, ConnectionSettings, CustomRules, Dashboard, Diagnostics, DnsSettings, DomainRules, Logs (+12 more)

### Community 31 - "Community 31"
Cohesion: 0.08
Nodes (3): BackgroundPowerManager, Callbacks, PowerMode

### Community 32 - "Community 32"
Cohesion: 0.08
Nodes (1): NodeStateManager

### Community 33 - "Community 33"
Cohesion: 0.08
Nodes (1): VpnTileService

### Community 34 - "Community 34"
Cohesion: 0.08
Nodes (4): Callbacks, HealthCheckRecoveryDecision, NetworkSwitchManager, NetworkType

### Community 35 - "Community 35"
Cohesion: 0.09
Nodes (5): Failed, NeedRestart, SelectorManager, Success, SwitchResult

### Community 36 - "Community 36"
Cohesion: 0.09
Nodes (1): LogRepository

### Community 37 - "Community 37"
Cohesion: 0.09
Nodes (2): ConnectManager, NetworkState

### Community 38 - "Community 38"
Cohesion: 0.09
Nodes (1): VpnTunManager

### Community 39 - "Community 39"
Cohesion: 0.09
Nodes (7): AppUpdateChecker, Error, GitHubRelease, ReleaseAsset, UpdateAvailable, UpdateCheckResult, UpToDate

### Community 40 - "Community 40"
Cohesion: 0.09
Nodes (4): LatencyResult, PreciseLatencyTester, Standard, TimingEventListener

### Community 41 - "Community 41"
Cohesion: 0.09
Nodes (1): OutboundFixerTest

### Community 42 - "Community 42"
Cohesion: 0.09
Nodes (5): DiagnosticsNodeLineQueryRunner, IpSbGeoIpResponse, NodeExitPortrait, NodeLineQueryData, NodeLineTarget

### Community 43 - "Community 43"
Cohesion: 0.09
Nodes (2): DiagnosticsViewModel, MatchResult

### Community 44 - "Community 44"
Cohesion: 0.1
Nodes (1): OutboundFixer

### Community 45 - "Community 45"
Cohesion: 0.1
Nodes (10): Callback, DefaultNetworkListener, Get, Lost, NetworkMessage, Put, Reset, Start (+2 more)

### Community 46 - "Community 46"
Cohesion: 0.1
Nodes (8): BatchProbeResult, Error, ProbeManager, ProbeResult, ProbeTarget, QuickProbeResult, Success, Timeout

### Community 47 - "Community 47"
Cohesion: 0.1
Nodes (6): Error, Idle, ImportState, Loading, ProfilesViewModel, Success

### Community 48 - "Community 48"
Cohesion: 0.11
Nodes (17): BatchUpdateResult, ConnectionState, ConnectionStats, Failed, LogEntryUi, LogLevel, NodeUi, ProfileType (+9 more)

### Community 49 - "Community 49"
Cohesion: 0.11
Nodes (3): DnsResolveStore, ResolvedEntry, Stats

### Community 50 - "Community 50"
Cohesion: 0.11
Nodes (1): DataExportRepository

### Community 51 - "Community 51"
Cohesion: 0.11
Nodes (1): RuleSetRepository

### Community 52 - "Community 52"
Cohesion: 0.11
Nodes (6): BudgetState, NodeAutoFailoverPolicy, ProbeEvaluation, ProbeOutcome, QuarantinedNode, TriggerContext

### Community 53 - "Community 53"
Cohesion: 0.11
Nodes (3): Listener, TrafficMonitor, TrafficSnapshot

### Community 54 - "Community 54"
Cohesion: 0.11
Nodes (4): NetworkCache, SettingsCache, StateCache, VpnStateCache

### Community 55 - "Community 55"
Cohesion: 0.11
Nodes (1): NodesViewModel

### Community 56 - "Community 56"
Cohesion: 0.11
Nodes (2): PermissionCallback, VpnConnectionManager

### Community 57 - "Community 57"
Cohesion: 0.12
Nodes (1): ConfigRepositoryEntryRegressionTest

### Community 58 - "Community 58"
Cohesion: 0.12
Nodes (4): CacheEntry, DnsResolveCache, SubscriptionManager, SubscriptionParser

### Community 59 - "Community 59"
Cohesion: 0.12
Nodes (2): NotificationState, VpnNotificationManager

### Community 60 - "Community 60"
Cohesion: 0.12
Nodes (2): Category, L

### Community 61 - "Community 61"
Cohesion: 0.12
Nodes (2): DnsResolver, DnsResolveResult

### Community 62 - "Community 62"
Cohesion: 0.13
Nodes (14): AppLanguage, AppSettings, AppThemeMode, BackgroundPowerSavingDelay, DefaultRule, DnsStrategy, GhProxyMirror, IpVersionMode (+6 more)

### Community 63 - "Community 63"
Cohesion: 0.13
Nodes (1): NodeLinkExporter

### Community 64 - "Community 64"
Cohesion: 0.13
Nodes (2): Callbacks, ScreenStateManager

### Community 65 - "Community 65"
Cohesion: 0.13
Nodes (2): ConnectionOwnerStatsSnapshot, ServiceStateHolder

### Community 66 - "Community 66"
Cohesion: 0.13
Nodes (2): HttpResult, KernelHttpClient

### Community 67 - "Community 67"
Cohesion: 0.13
Nodes (2): Base64Parser, SingBoxParser

### Community 68 - "Community 68"
Cohesion: 0.13
Nodes (1): SingBoxRemoteStateTest

### Community 69 - "Community 69"
Cohesion: 0.14
Nodes (1): SingBoxIpcService

### Community 70 - "Community 70"
Cohesion: 0.14
Nodes (2): StartCommand, VpnServiceManager

### Community 71 - "Community 71"
Cohesion: 0.14
Nodes (2): LoadResult, ProfilePersistence

### Community 72 - "Community 72"
Cohesion: 0.14
Nodes (2): Listener, NetworkManager

### Community 73 - "Community 73"
Cohesion: 0.14
Nodes (2): NetworkClient, PoolStatus

### Community 74 - "Community 74"
Cohesion: 0.14
Nodes (3): DnsPrewarmer, PrewarmResult, ResolveResult

### Community 75 - "Community 75"
Cohesion: 0.14
Nodes (2): ClashConfigParser, ClashConfigParserTest

### Community 76 - "Community 76"
Cohesion: 0.15
Nodes (1): SettingsStore

### Community 77 - "Community 77"
Cohesion: 0.15
Nodes (2): Callbacks, NodeSwitchManager

### Community 78 - "Community 78"
Cohesion: 0.15
Nodes (1): QrScannerActivity

### Community 79 - "Community 79"
Cohesion: 0.15
Nodes (4): PerfTracer, Phases, TraceInfo, TraceStats

### Community 80 - "Community 80"
Cohesion: 0.15
Nodes (1): NodeAutoFailoverPolicyTest

### Community 81 - "Community 81"
Cohesion: 0.17
Nodes (1): NodeLatencyDao

### Community 82 - "Community 82"
Cohesion: 0.17
Nodes (6): Error, Idle, InstalledAppsRepository, Loaded, Loading, LoadingState

### Community 83 - "Community 83"
Cohesion: 0.17
Nodes (1): NetworkHelper

### Community 84 - "Community 84"
Cohesion: 0.17
Nodes (1): ProfileImportType

### Community 85 - "Community 85"
Cohesion: 0.17
Nodes (0): 

### Community 86 - "Community 86"
Cohesion: 0.17
Nodes (1): RuleSetViewModel

### Community 87 - "Community 87"
Cohesion: 0.17
Nodes (1): DnsResolverTest

### Community 88 - "Community 88"
Cohesion: 0.18
Nodes (1): SafeLatencyTester

### Community 89 - "Community 89"
Cohesion: 0.18
Nodes (1): AppDatabase

### Community 90 - "Community 90"
Cohesion: 0.18
Nodes (10): AppGroup, AppInfo, AppRule, CustomRule, InstalledApp, OutboundTag, RuleSet, RuleSetOutboundMode (+2 more)

### Community 91 - "Community 91"
Cohesion: 0.18
Nodes (1): FakeRepository

### Community 92 - "Community 92"
Cohesion: 0.18
Nodes (0): 

### Community 93 - "Community 93"
Cohesion: 0.18
Nodes (1): VpnTunAddressPlanTest

### Community 94 - "Community 94"
Cohesion: 0.2
Nodes (1): ActiveStateDao

### Community 95 - "Community 95"
Cohesion: 0.2
Nodes (1): SettingsDao

### Community 96 - "Community 96"
Cohesion: 0.2
Nodes (0): 

### Community 97 - "Community 97"
Cohesion: 0.22
Nodes (8): ExportData, ExportDataSummary, Failed, ImportOptions, ImportResult, PartialSuccess, ProfileExportData, Success

### Community 98 - "Community 98"
Cohesion: 0.22
Nodes (1): LatencyCache

### Community 99 - "Community 99"
Cohesion: 0.22
Nodes (3): FetchResult, SubscriptionFetcher, SubscriptionUserInfo

### Community 100 - "Community 100"
Cohesion: 0.22
Nodes (2): Callbacks, ForeignVpnMonitor

### Community 101 - "Community 101"
Cohesion: 0.22
Nodes (2): AppLogger, Level

### Community 102 - "Community 102"
Cohesion: 0.22
Nodes (1): PlatformInterfaceImplTest

### Community 103 - "Community 103"
Cohesion: 0.22
Nodes (1): UrlTestTagMatcherTest

### Community 104 - "Community 104"
Cohesion: 0.22
Nodes (1): DiagnosticsViewModelNodeLineTest

### Community 105 - "Community 105"
Cohesion: 0.25
Nodes (1): AppLifecycleObserver

### Community 106 - "Community 106"
Cohesion: 0.25
Nodes (2): ResolveDetail, UrlTestTagMatcher

### Community 107 - "Community 107"
Cohesion: 0.25
Nodes (2): TrafficStatsUiState, TrafficStatsViewModel

### Community 108 - "Community 108"
Cohesion: 0.29
Nodes (1): LatencyTester

### Community 109 - "Community 109"
Cohesion: 0.29
Nodes (1): SubscriptionAutoUpdateWorker

### Community 110 - "Community 110"
Cohesion: 0.29
Nodes (1): VpnKeepaliveWorker

### Community 111 - "Community 111"
Cohesion: 0.29
Nodes (1): SquareViewFinderView

### Community 112 - "Community 112"
Cohesion: 0.29
Nodes (0): 

### Community 113 - "Community 113"
Cohesion: 0.29
Nodes (1): BatteryOptimizationHelper

### Community 114 - "Community 114"
Cohesion: 0.29
Nodes (1): LocaleHelper

### Community 115 - "Community 115"
Cohesion: 0.29
Nodes (1): SecurityUtils

### Community 116 - "Community 116"
Cohesion: 0.29
Nodes (1): LogViewModel

### Community 117 - "Community 117"
Cohesion: 0.29
Nodes (1): SettingsStoreTest

### Community 118 - "Community 118"
Cohesion: 0.29
Nodes (1): DnsResolveStoreTest

### Community 119 - "Community 119"
Cohesion: 0.33
Nodes (1): MainActivity

### Community 120 - "Community 120"
Cohesion: 0.33
Nodes (1): SingBoxApplication

### Community 121 - "Community 121"
Cohesion: 0.33
Nodes (1): Converters

### Community 122 - "Community 122"
Cohesion: 0.33
Nodes (1): NodeEntity

### Community 123 - "Community 123"
Cohesion: 0.33
Nodes (1): NodeExtractor

### Community 124 - "Community 124"
Cohesion: 0.33
Nodes (1): RuleSetAutoUpdateWorker

### Community 125 - "Community 125"
Cohesion: 0.33
Nodes (0): 

### Community 126 - "Community 126"
Cohesion: 0.33
Nodes (1): VersionInfo

### Community 127 - "Community 127"
Cohesion: 0.33
Nodes (1): IpVersionModeRulesTest

### Community 128 - "Community 128"
Cohesion: 0.33
Nodes (1): PingDisplayTextTest

### Community 129 - "Community 129"
Cohesion: 0.33
Nodes (1): InboundBuilderTest

### Community 130 - "Community 130"
Cohesion: 0.33
Nodes (1): BackgroundPowerManagerTest

### Community 131 - "Community 131"
Cohesion: 0.33
Nodes (1): StartupManagerTest

### Community 132 - "Community 132"
Cohesion: 0.4
Nodes (0): 

### Community 133 - "Community 133"
Cohesion: 0.4
Nodes (1): LibboxNativeSupport

### Community 134 - "Community 134"
Cohesion: 0.4
Nodes (1): LocalResolverImpl

### Community 135 - "Community 135"
Cohesion: 0.4
Nodes (3): AddNodeTarget, ExistingProfile, NewProfile

### Community 136 - "Community 136"
Cohesion: 0.4
Nodes (3): ExistingProfile, NewProfile, SelectProfileTarget

### Community 137 - "Community 137"
Cohesion: 0.4
Nodes (0): 

### Community 138 - "Community 138"
Cohesion: 0.4
Nodes (0): 

### Community 139 - "Community 139"
Cohesion: 0.4
Nodes (2): DeepLinkHandler, SubscriptionImportData

### Community 140 - "Community 140"
Cohesion: 0.4
Nodes (1): StringBuilderPool

### Community 141 - "Community 141"
Cohesion: 0.4
Nodes (1): InstalledAppsViewModel

### Community 142 - "Community 142"
Cohesion: 0.4
Nodes (1): BoxWrapperManagerRecoveryPolicyTest

### Community 143 - "Community 143"
Cohesion: 0.4
Nodes (1): SingBoxIpcHubStateTest

### Community 144 - "Community 144"
Cohesion: 0.4
Nodes (1): VpnStateStoreTest

### Community 145 - "Community 145"
Cohesion: 0.4
Nodes (1): ModelSerializationTest

### Community 146 - "Community 146"
Cohesion: 0.4
Nodes (1): LatencyProbePolicyTest

### Community 147 - "Community 147"
Cohesion: 0.4
Nodes (1): LogRepositoryTest

### Community 148 - "Community 148"
Cohesion: 0.4
Nodes (1): NodeLinkExporterTest

### Community 149 - "Community 149"
Cohesion: 0.4
Nodes (1): NetworkSwitchManagerTest

### Community 150 - "Community 150"
Cohesion: 0.4
Nodes (1): RouteGroupSelectorTest

### Community 151 - "Community 151"
Cohesion: 0.5
Nodes (1): ProfileEntity

### Community 152 - "Community 152"
Cohesion: 0.5
Nodes (3): GithubFile, GithubTreeItem, GithubTreeResponse

### Community 153 - "Community 153"
Cohesion: 0.5
Nodes (0): 

### Community 154 - "Community 154"
Cohesion: 0.5
Nodes (2): VpnTunAddressPlan, VpnTunAddressPlanner

### Community 155 - "Community 155"
Cohesion: 0.5
Nodes (0): 

### Community 156 - "Community 156"
Cohesion: 0.5
Nodes (0): 

### Community 157 - "Community 157"
Cohesion: 0.5
Nodes (0): 

### Community 158 - "Community 158"
Cohesion: 0.5
Nodes (1): NodeDisplaySettings

### Community 159 - "Community 159"
Cohesion: 0.5
Nodes (1): AppShortcutsResourceTest

### Community 160 - "Community 160"
Cohesion: 0.5
Nodes (1): SafeLatencyTesterTest

### Community 161 - "Community 161"
Cohesion: 0.5
Nodes (1): VpnServiceManagerTest

### Community 162 - "Community 162"
Cohesion: 0.67
Nodes (2): FilterMode, NodeFilter

### Community 163 - "Community 163"
Cohesion: 0.67
Nodes (1): PingDisplayText

### Community 164 - "Community 164"
Cohesion: 0.67
Nodes (1): LatencyProbePolicy

### Community 165 - "Community 165"
Cohesion: 0.67
Nodes (1): InboundBuilder

### Community 166 - "Community 166"
Cohesion: 0.67
Nodes (1): ShortcutActivity

### Community 167 - "Community 167"
Cohesion: 0.67
Nodes (0): 

### Community 168 - "Community 168"
Cohesion: 0.67
Nodes (1): AppNotificationManager

### Community 169 - "Community 169"
Cohesion: 0.67
Nodes (0): 

### Community 170 - "Community 170"
Cohesion: 0.67
Nodes (0): 

### Community 171 - "Community 171"
Cohesion: 0.67
Nodes (0): 

### Community 172 - "Community 172"
Cohesion: 0.67
Nodes (0): 

### Community 173 - "Community 173"
Cohesion: 0.67
Nodes (0): 

### Community 174 - "Community 174"
Cohesion: 0.67
Nodes (0): 

### Community 175 - "Community 175"
Cohesion: 0.67
Nodes (0): 

### Community 176 - "Community 176"
Cohesion: 0.67
Nodes (0): 

### Community 177 - "Community 177"
Cohesion: 0.67
Nodes (0): 

### Community 178 - "Community 178"
Cohesion: 0.67
Nodes (1): ProxyAwareOkHttpClient

### Community 179 - "Community 179"
Cohesion: 0.67
Nodes (1): TcpPing

### Community 180 - "Community 180"
Cohesion: 0.67
Nodes (1): RuleSetUpdateWorker

### Community 181 - "Community 181"
Cohesion: 0.67
Nodes (1): VMessClashTest

### Community 182 - "Community 182"
Cohesion: 1.0
Nodes (1): ActiveStateEntity

### Community 183 - "Community 183"
Cohesion: 1.0
Nodes (1): NodeLatencyEntity

### Community 184 - "Community 184"
Cohesion: 1.0
Nodes (1): SettingsEntity

### Community 185 - "Community 185"
Cohesion: 1.0
Nodes (1): HubRuleSet

### Community 186 - "Community 186"
Cohesion: 1.0
Nodes (1): NodeSortType

### Community 187 - "Community 187"
Cohesion: 1.0
Nodes (1): PingResultCode

### Community 188 - "Community 188"
Cohesion: 1.0
Nodes (0): 

### Community 189 - "Community 189"
Cohesion: 1.0
Nodes (0): 

### Community 190 - "Community 190"
Cohesion: 1.0
Nodes (0): 

### Community 191 - "Community 191"
Cohesion: 1.0
Nodes (0): 

### Community 192 - "Community 192"
Cohesion: 1.0
Nodes (0): 

### Community 193 - "Community 193"
Cohesion: 1.0
Nodes (0): 

### Community 194 - "Community 194"
Cohesion: 1.0
Nodes (0): 

### Community 195 - "Community 195"
Cohesion: 1.0
Nodes (0): 

### Community 196 - "Community 196"
Cohesion: 1.0
Nodes (0): 

### Community 197 - "Community 197"
Cohesion: 1.0
Nodes (0): 

### Community 198 - "Community 198"
Cohesion: 1.0
Nodes (0): 

### Community 199 - "Community 199"
Cohesion: 1.0
Nodes (0): 

### Community 200 - "Community 200"
Cohesion: 1.0
Nodes (0): 

### Community 201 - "Community 201"
Cohesion: 1.0
Nodes (0): 

### Community 202 - "Community 202"
Cohesion: 1.0
Nodes (0): 

### Community 203 - "Community 203"
Cohesion: 1.0
Nodes (0): 

### Community 204 - "Community 204"
Cohesion: 1.0
Nodes (0): 

### Community 205 - "Community 205"
Cohesion: 1.0
Nodes (0): 

## Knowledge Gaps
- **264 isolated node(s):** `RecoveryMode`, `RecoveryLevel`, `SmartRecoveryResult`, `ProbeTarget`, `ProbeResult` (+259 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Community 182`** (2 nodes): `ActiveStateEntity.kt`, `ActiveStateEntity`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 183`** (2 nodes): `NodeLatencyEntity.kt`, `NodeLatencyEntity`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 184`** (2 nodes): `SettingsEntity.kt`, `SettingsEntity`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 185`** (2 nodes): `HubRuleSet.kt`, `HubRuleSet`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 186`** (2 nodes): `NodeSortType.kt`, `NodeSortType`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 187`** (2 nodes): `PingResultCode.kt`, `PingResultCode`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 188`** (2 nodes): `AppNavBar.kt`, `AppNavBar()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 189`** (2 nodes): `BigToggle.kt`, `BigToggle()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 190`** (2 nodes): `NodeCard.kt`, `NodeCard()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 191`** (2 nodes): `ProfileCard.kt`, `ProfileCard()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 192`** (2 nodes): `StandardCard.kt`, `StandardCard()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 193`** (2 nodes): `AppGroupsScreen.kt`, `AppGroupsScreen()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 194`** (2 nodes): `ConnectionSettingsScreen.kt`, `ConnectionSettingsScreen()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 195`** (2 nodes): `DiagnosticsScreen.kt`, `DiagnosticsScreen()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 196`** (2 nodes): `DnsSettingsScreen.kt`, `DnsSettingsScreen()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 197`** (2 nodes): `LogsScreen.kt`, `LogsScreen()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 198`** (2 nodes): `ProfileEditorScreen.kt`, `ProfileEditorScreen()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 199`** (2 nodes): `RoutingSettingsScreen.kt`, `RoutingSettingsScreen()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 200`** (2 nodes): `SplashScreen.kt`, `SplashScreen()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 201`** (2 nodes): `TunSettingsScreen.kt`, `TunSettingsScreen()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 202`** (2 nodes): `Theme.kt`, `SingBoxTheme()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 203`** (1 nodes): `settings.gradle.kts`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 204`** (1 nodes): `Color.kt`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 205`** (1 nodes): `Type.kt`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `ConfigRepository` connect `Community 0` to `Community 2`?**
  _High betweenness centrality (0.006) - this node is a cross-community bridge._
- **Why does `SingBoxService` connect `Community 5` to `Community 3`?**
  _High betweenness centrality (0.002) - this node is a cross-community bridge._
- **What connects `RecoveryMode`, `RecoveryLevel`, `SmartRecoveryResult` to the rest of the system?**
  _264 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.01 - nodes in this community are weakly interconnected._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.02 - nodes in this community are weakly interconnected._
- **Should `Community 2` be split into smaller, more focused modules?**
  _Cohesion score 0.02 - nodes in this community are weakly interconnected._
- **Should `Community 3` be split into smaller, more focused modules?**
  _Cohesion score 0.02 - nodes in this community are weakly interconnected._