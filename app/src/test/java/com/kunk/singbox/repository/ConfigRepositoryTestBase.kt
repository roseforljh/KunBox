package com.kunk.singbox.repository

import com.google.gson.Gson
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetConfig
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.model.SubscriptionUpdateStage
import com.kunk.singbox.utils.parser.Base64Parser
import com.kunk.singbox.utils.parser.ClashYamlParser
import com.kunk.singbox.utils.parser.NodeLinkParser
import com.kunk.singbox.utils.parser.SingBoxParser
import com.kunk.singbox.utils.parser.SubscriptionManager
import kotlinx.coroutines.flow.MutableStateFlow

@Suppress("TooManyFunctions")
abstract class ConfigRepositoryTestBase {
    protected val gson = Gson()

    protected val nodeLinkParser = NodeLinkParser(gson)

    protected val subscriptionManager = SubscriptionManager(
        listOf(
            SingBoxParser(gson),
            ClashYamlParser(),
            Base64Parser { nodeLinkParser.parse(it) }
        )
    )

    // Virtual declarations keep split class logic callable across files.
    protected abstract fun invokeAppliedRemoteRuleSetFilter(
        ruleSets: List<RuleSet>,
        validRuleSets: List<RuleSetConfig>
    ): List<RuleSet>

    protected abstract fun createUpdatingProfile(profileId: String): ProfileUi

    protected abstract fun bestvmrDnsOverrideJson(): String

    protected abstract fun bestvmrNodeOutbound(): Outbound

    protected abstract fun applyStageForRun(
        profiles: MutableStateFlow<List<ProfileUi>>,
        activeRuns: Map<String, Long>,
        profileId: String,
        runId: Long,
        stage: SubscriptionUpdateStage?
    )

    abstract fun testStableNodeIdConsistency()

    abstract fun testStableNodeIdDifferentInputs()

    abstract fun testStableNodeIdSpecialCharacters()

    abstract fun testBuildConfigWithOutboundsPreservesExistingProfileSettings()

    abstract fun testStableNodeIdEmptyInputs()

    abstract fun testStableNodeIdUnicodeCharacters()

    abstract fun testStableNodeIdCacheEfficiency()

    abstract fun testExtractSubscriptionUrlFromHtml()

    abstract fun testLooksLikeHtmlSubscriptionPageDoesNotTrustContentTypeAloneForYamlBody()

    abstract fun testLooksLikeHtmlSubscriptionPageDoesNotTrustContentTypeAloneForBase64Body()

    abstract fun testLooksLikeHtmlSubscriptionPageByBodyPrefix()

    abstract fun testLooksLikeHtmlSubscriptionPageByHtmlTagPrefixEvenWhenContentTypeIsTextHtml()

    abstract fun testExtractSubscriptionHost()

    abstract fun testLooksLikeSubscriptionUrlForImportAcceptsSubscriptionApiUrl()

    abstract fun testLooksLikeSubscriptionUrlForImportAcceptsPortedSubscriptionApiUrl()

    abstract fun testLooksLikeSubscriptionUrlForImportRejectsHttpProxyLink()

    abstract fun testPrioritizeUserAgentsWithPreferredValue()

    abstract fun testPrioritizeUserAgentsWithoutPreferredValue()

    abstract fun testFilterCircuitBrokenUserAgents()

    abstract fun testFilterCircuitBrokenUserAgentsFallsBackWhenAllBlocked()

    abstract fun testBuildSubscriptionAttemptUserAgentsKeepsRememberedUserAgentFirst()

    abstract fun testResolveSubscriptionUpdateBudgetSecondsFallsBackToDefaultWhenNonPositive()

    abstract fun testResolveSubscriptionAttemptTimeoutBudgetUsesFullRemainingBudget()

    abstract fun testResolveSubscriptionAttemptTimeoutBudgetRoundsUpRemainingBudget()

    abstract fun testResolveSubscriptionAttemptTimeoutBudgetReturnsNullWhenBudgetExhausted()

    abstract fun testSubscriptionContentLengthLimitAllowsUnknownOrBoundedLength()

    abstract fun testSubscriptionContentLengthLimitRejectsOversizedLength()

    abstract fun testResolveSubscriptionUpdateStageMapsKnownStages()

    abstract fun testLaunchSubscriptionDnsPreResolveReturnsWithoutWaitingForResolution()

    abstract fun testLaunchSubscriptionDnsPreResolveSwallowsBackgroundFailure()

    abstract fun testLaunchSubscriptionDnsPreResolveStaleRunCannotClearNewUpdateStage()

    abstract fun testBatchUpdateResultAggregatesMixedSubscriptionResults()

    abstract fun testResolveAppRuleOutboundModeDefaultsToProxy()

    abstract fun testResolveAppRuleOutboundModeKeepsExplicitMode()

    abstract fun testShouldRecordSubscriptionNetworkFailureForConnectException()

    abstract fun testShouldRecordSubscriptionNetworkFailureForTimeoutException()

    abstract fun testShouldRecordSubscriptionNetworkFailureForParseError()

    abstract fun testShouldStopSubscriptionFallbackForHtmlInfoPage()

    abstract fun testShouldStopSubscriptionFallbackForHttp429()

    abstract fun testShouldNotStopSubscriptionFallbackForOrdinaryParseFailureOrOtherHttpErrors()

    abstract fun testBuildBootstrapDnsRulesOnlyTargetsResolverDomains()

    abstract fun testBuildBootstrapDnsRulesSkipsIpAndLocalAddresses()

    abstract fun testBuildBootstrapDnsRulesStripsPortFromBareHostAddress()

    abstract fun testDnsOverrideReplacesServersPrependsRulesAndOverridesTopLevelFields()

    abstract fun testDnsOverrideKeepsBlankTopLevelFieldsAndAllowsFalseBooleans()

    abstract fun testDnsOverrideServerRuleDefaultsToRouteAction()

    abstract fun testDnsOverrideRuleStringFieldsParseAsLists()

    abstract fun testDnsOverrideAcceptsFullConfigDnsWrapper()

    abstract fun testDnsOverrideCompatibilityWarningDetectsLegacyFieldsAndImplicitRouteAction()

    abstract fun testDnsOverrideCompatibilityWarningDetectsMissingServerAndGlobalRule()

    abstract fun testDnsOverrideCompatibilityWarningDetectsInvalidJson()

    abstract fun testDnsOverrideCompatibilityWarningDetectsLegacyAddressResolver()

    abstract fun testDnsOverrideCompatibilityWarningDetectsOtherMigrationRisks()

    abstract fun testDnsOverrideCompatibilityWarningIgnoresLatestFormat()

    abstract fun testLocalDnsWithFullDnsOverrideRoutesNodeDomainToPrivateDns()

    abstract fun testDnsOverrideDomainRuleAppliesToOutboundDomainResolver()

    abstract fun testOutboundDomainResolverDnsRulesProtectNodeDomainFromFakeIp()

    abstract fun testOutboundDomainResolverDnsRulesSkipIpAndFakeIpResolver()

    abstract fun testDefaultDomainResolverUsesLocalDnsForNodeDomains()

    abstract fun testDnsOverrideWinsOverLocalDefaultDomainResolver()

    abstract fun testDefaultOutboundDomainResolverAppliesServerAddressStrategy()

    abstract fun testDnsOverrideDomainResolverKeepsServerAddressStrategyWhenRuleHasNoStrategy()

    abstract fun testDnsOverrideCatchAllRuleWinsOverLocalDefaultDomainResolver()

    abstract fun testDnsOverrideOutboundAnyRuleWinsOverLocalDefaultDomainResolver()

    abstract fun testDnsOverrideSpecificOutboundRuleOnlyAppliesMatchingOutbound()

    abstract fun testDnsOverrideMatchingDomainSkipsProfileDnsPreResolve()

    abstract fun testDnsOverrideCatchAllRuleSkipsProfileDnsPreResolve()

    abstract fun testDnsOverrideOutboundAnyRuleSkipsProfileDnsPreResolve()

    abstract fun testDnsOverrideSpecificOutboundRuleSkipsMatchingProfileDnsPreResolve()

    abstract fun testDnsOverrideSpecificOutboundRuleKeepsNonMatchingProfileDnsPreResolve()

    abstract fun testDnsOverrideNodeDomainResolverSkipsAutomaticProxyDetour()

    abstract fun testDnsOverrideNonMatchingDomainKeepsProfileDnsPreResolve()

    abstract fun testNormalizeLocalDnsReplacesLegacyLocalValue()

    abstract fun testNormalizeLocalDnsReplacesBlankValue()

    abstract fun testNormalizeLocalDnsKeepsNumericAddress()

    abstract fun testNormalizeLocalDnsRejectsBareDomainAddress()

    abstract fun testNormalizeRemoteDnsReplacesBlankValue()

    abstract fun testNormalizeRemoteDnsRewritesCloudflareIpDohToDomain()

    abstract fun testNormalizeRemoteDnsRewritesCloudflareIpv6DohToDomain()

    abstract fun testNormalizeRemoteDnsKeepsNonCloudflareIpDoh()

    abstract fun testBuildDnsResolverForDomainUrlReturnsBootstrapResolver()

    abstract fun testBuildDnsResolverForIpUrlReturnsNull()

    abstract fun testBuildDnsResolverForLocalValueReturnsNull()

    abstract fun testSubscriptionManagerPreservesTlsCertificateFromYamlImport()

    abstract fun testSubscriptionManagerDoesNotTreatTlsCertificateAsNodeLink()

    abstract fun testSubscriptionManagerPreservesJsonTlsCertificateFields()

    abstract fun testSubscriptionManagerParsesYamlImportWithMultipleNodes()

    abstract fun testBuildUdpDnsServerFromNumericAddressUsesPort53()

    abstract fun testBuildDnsServerPreservesDomainResolverInJson()

    abstract fun testBuildDynamicDnsServersDeduplicatesSameDetour()

    abstract fun testBuildDynamicDnsServersIncludesDifferentDetours()

    abstract fun testBuildDynamicDnsServerTagIsStableForSameDetour()

    abstract fun testBuildDynamicDnsServerTagDiffersForDifferentDetours()

    abstract fun testBuildDynamicDnsServerUsesGivenDetour()

    abstract fun testBuildDynamicDnsServersUsesRemoteDnsWithDetourForEchRouteTag()

    abstract fun testBuildDynamicRemoteDnsServerForProxyDetourCarriesDetour()

    abstract fun testBuildDynamicRemoteDnsServerKeepsRemoteDnsForEchDetour()

    abstract fun testBuildFakeIpDnsServerForTestIncludesRangesForFakeIpTransport()

    abstract fun testBuildFakeIpDnsServerForTestPreservesCustomIpv4AndIpv6Ranges()

    abstract fun testBuildFakeIpDnsServerForTestRecoversNullRange()

    abstract fun testDnsServerTagForRouteTagUsesDynamicServerWhenFakeDnsDisabled()

    abstract fun testDnsServerTagForRouteTagUsesDynamicServerWhenFakeDnsEnabled()

    abstract fun testDnsServerTagForProxyUsesProxyServerWhenFakeDnsEnabled()

    abstract fun testDnsRouteToProxyUsesProxyDnsForIpQueriesWhenFakeDnsEnabled()

    abstract fun testDnsRouteToProxyReturnsProxyDnsRuleWhenFakeDnsEnabled()

    abstract fun testDnsRouteToNonDirectReturnsSpecificDnsRuleWhenFakeDnsEnabled()

    abstract fun testNonIpDnsFallbackRoutesHttpsAndSvcbToProxyDns()

    abstract fun testDnsRouteToDirectOnlyRoutesIpQueriesToLocalDns()

    abstract fun testNormalizeRuleSetUrlAddsRawPrefixForGithubPathOnlyUrl()

    abstract fun testNormalizeRuleSetUrlAddsRawPrefixForLeadingSlashGithubPathOnlyUrl()

    abstract fun testNormalizeRuleSetForSaveNormalizesRemoteRuleSetUrl()

    abstract fun testRuleSetDnsPriorityKeepsProxySpecificRulesBeforeDirectCountryRules()

    abstract fun testGoogleConnectivityDnsRulesUseProxyBeforeCountryRules()

    abstract fun testGoogleConnectivityRouteRulePrecedesDirectCountryRule()

    abstract fun testDnsServerTagForFallbackProxyUsesProxyServer()

    abstract fun testDnsServerTagForFallbackProxyUsesDynamicServerWhenFakeDnsEnabled()

    abstract fun testDnsServerTagForFakeIpExcludeDomainUsesDynamicServerWhenFakeDnsEnabled()

    abstract fun testResolveRouteModeForRuleSetUsesProxyDefault()

    abstract fun testResolveRouteModeForAppGroupUsesDirectDefault()

    abstract fun testResolveRouteModeForCustomRuleUsesLegacyOutboundDefault()

    abstract fun testResolveOutboundSemanticDirect()

    abstract fun testResolveOutboundSemanticBlock()

    abstract fun testResolveOutboundSemanticProxy()

    abstract fun testResolveOutboundSemanticNodeValid()

    abstract fun testResolveOutboundSemanticNodeInvalid()

    abstract fun testResolveOutboundSemanticProfileValid()

    abstract fun testResolveOutboundSemanticProfileInvalid()

    abstract fun testResolveProfileSelectorDefaultPrefersLowestLatencyOverRememberedNode()

    abstract fun testResolveProfileSelectorDefaultIgnoresRememberedNodeOutsideCurrentProfile()

    abstract fun testResolveProfileSelectorDefaultFallsBackToRememberedNodeWhenLatencyUnavailable()

    abstract fun testResolveProfileSelectorDefaultUsesLowestPositiveLatency()

    abstract fun testResolveProfileSelectorDefaultFallsBackToFirstTag()

    abstract fun testBuildProfileRouteGroupOutboundsCreatesNestedAutoStructure()

    abstract fun testApplySelectorSafeOutboundsKeepsUrlTestDefaultNull()

    abstract fun testBuildAppRoutingRulesUsesSemanticRejectForBlockRule()

    abstract fun testResolveDnsStrategyClampsIpv4OnlyMode()

    abstract fun testResolveDnsStrategyClampsIpv6OnlyMode()

    abstract fun testResolveDnsStrategyPrefersIpv6InPreferMode()

    abstract fun testBuildQuicBlockRuleReturnsEmptyWhenBlockQuicDisabled()

    abstract fun testBuildQuicBlockRuleOnlyRejectsSniffedQuicWhenBlockQuicEnabled()

    abstract fun testBuildTunFakeIpDnsRuleReturnsEmptyWhenFakeDnsDisabled()

    abstract fun testBuildTunFakeIpDnsRuleRoutesTunAaaaAndAWhenFakeDnsEnabled()

    abstract fun testBuildEchDnsRulesRoutesHttpsQueryServerNameToGivenDnsServer()

    abstract fun testBuildEchAwareHttpsSvcbRulesRoutesEchBeforeRejectWhenBlockQuicEnabled()

    abstract fun testResolveActiveEchDnsServerRequiresActiveNode()

    abstract fun testResolveActiveEchDnsServerFallsBackToUniqueEchResolver()

    abstract fun testNeedsLegacyEchDnsRepairWhenResolverMetadataMissing()

    abstract fun testResolveDefaultRouteDomainResolverUsesBootstrapAlways()

    abstract fun testResolveRunDnsFinalServerUsesStableRemoteWhenGlobalProxyAndFakeDnsEnabled()

    abstract fun testResolveRunDnsFinalServerUsesProxyDetourWhenRuleProxyAndFakeDnsEnabled()

    abstract fun testResolveProxyDnsDetourTagUsesSelectorDefaultConcreteNode()

    abstract fun testResolveProxyDnsDetourTagUnwrapsUrlTestDefault()

    abstract fun testBypassLanRulesUseIpIsPrivate()

    abstract fun testHijackDnsRulesCatchTunDnsPortBeforeProtocolSniffing()

    abstract fun testRoutingModeGlobalProxyStillBuildsProfileRuleSetRouteRules()

    abstract fun testGlobalProxyDnsFinalUsesRemoteServerWhenFakeDnsEnabled()

    abstract fun testBypassLanRulesDisabledWhenSettingOff()

    abstract fun testMulticastRejectRulesCoverIpv4AndIpv6WhenDualStack()

    abstract fun testMulticastRejectRulesFollowIpVersionMode()

    abstract fun testAppliedRemoteRuleSetFilterIncludesEnabledRemoteRuleSet()

    abstract fun testAppliedRemoteRuleSetFilterExcludesDisabledRemoteRuleSet()

    abstract fun testAppliedRemoteRuleSetFilterExcludesRemoteRuleSetOutsideValidTags()

    abstract fun testAppliedRemoteRuleSetFilterExcludesLocalRuleSet()

    abstract fun testAtomicTextWriteReplacesExistingFileAndCleansTempFiles()

    abstract fun testAtomicTextWriteDoesNotUseSharedFixedTempPath()

    abstract fun testDetectRuleSetRuleTypeIpRules()

    abstract fun testDetectRuleSetRuleTypeDomainRules()

    abstract fun testDetectRuleSetRuleTypeMixedRules()

    abstract fun testDetectRuleSetRuleTypeWithIpCidrPrefix()

    abstract fun testDetectRuleSetRuleTypeWithDomainPrefix()

    abstract fun testDetectRuleSetRuleTypeIpv6Rules()

    abstract fun testDetectRuleSetRuleTypeEmptyFile()

    abstract fun testDetectRuleSetRuleTypeOnlyComments()

    abstract fun testDetectRuleSetRuleTypeUsesGeositeTagForBinaryRuleSet()

    abstract fun testDetectRuleSetRuleTypeKeepsUnknownBinaryAsUnknownWithoutTagHint()

    protected abstract fun createTempRuleSetBytes(content: ByteArray): java.io.File

    protected abstract fun createTempRuleSetFile(content: String): java.io.File

    abstract fun testSanitizeInjectedDnsServerForcesDetourOnUdpWithoutDetour()

    abstract fun testSanitizeInjectedDnsServerPreservesExistingDetour()

    abstract fun testSanitizeInjectedDnsServerSkipsFakeip()

    abstract fun testSanitizeInjectedDnsServerSkipsInGlobalDirectMode()
}
