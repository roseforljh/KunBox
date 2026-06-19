package com.kunk.singbox.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiquidGlassMaterialCallCoverageTest {

    @Test
    fun filterChipsUseLiquidGlassWrapper() {
        val liquidControls = liquidControlSources()
        val screenFiles = listOf(
            "LogsScreen.kt",
            "TrafficStatsScreen.kt"
        )

        assertTrue(liquidControls.contains("fun LiquidGlassFilterChip("))
        assertTrue(liquidControls.contains("FilterChip("))
        screenFiles.assertScreenSourcesContain("LiquidGlassFilterChip(")
    }

    @Test
    fun directMenusAndFilterChipsStayBehindLiquidGlassWrappers() {
        val uiDir = File("src/main/java/com/kunk/singbox/ui")
        val failures = directMaterialCallsOutsideAllowedFiles(
            uiDir = uiDir,
            callName = "DropdownMenu",
            allowedFiles = setOf("theme/LiquidGlassMenuControls.kt")
        ) + directMaterialCallsOutsideAllowedFiles(
            uiDir = uiDir,
            callName = "FilterChip",
            allowedFiles = setOf("theme/LiquidGlassChipControls.kt")
        )

        assertTrue("Direct menu and chip calls should use liquid glass wrappers: $failures", failures.isEmpty())
    }

    @Test
    fun directMaterialSurfaceEntrypointsStayInAllowList() {
        val mainDir = File("src/main/java/com/kunk/singbox")
        val surfaceCalls = listDirectMaterialCalls(
            sourceDir = mainDir,
            callNames = listOf(
                "Card",
                "Surface",
                "NavigationBar",
                "NavigationBarItem",
                "DropdownMenu",
                "FilterChip"
            )
        )

        assertEquals(directMaterialSurfaceAllowList, surfaceCalls)
    }

    @Test
    fun materialControlCallsKeepLiquidGlassParameters() {
        val uiDir = File("src/main/java/com/kunk/singbox/ui")
        val failures = materialCallRules().flatMap { rule ->
            materialCallParameterFailures(uiDir = uiDir, rule = rule)
        }

        assertTrue("Material control calls should keep liquid glass parameters: $failures", failures.isEmpty())
    }

    @Test
    fun liquidGlassChipPreservesDefaultThemeBranch() {
        val chipControls = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassChipControls.kt").readText()

        assertTrue(chipControls.contains("else {\n        FilterChip("))
    }

    @Test
    fun liquidGlassChipUsesPressedFeedbackWithoutMaterialRipple() {
        val chipControls = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassChipControls.kt").readText()

        assertTrue(chipControls.contains("MutableInteractionSource"))
        assertTrue(chipControls.contains("collectIsPressedAsState"))
        assertTrue(chipControls.contains("animateFloatAsState"))
        assertTrue(chipControls.contains("graphicsLayer"))
        assertTrue(chipControls.contains("indication = null"))
    }

    @Test
    fun standardCardLiquidBranchUsesPressedFeedbackWithoutMaterialRipple() {
        val standardCard = File("src/main/java/com/kunk/singbox/ui/components/StandardCard.kt").readText()

        assertTrue(standardCard.contains("if (isLiquidGlassTheme())"))
        assertTrue(standardCard.contains("MutableInteractionSource"))
        assertTrue(standardCard.contains("collectIsPressedAsState"))
        assertTrue(standardCard.contains("animateFloatAsState"))
        assertTrue(standardCard.contains("graphicsLayer"))
        assertTrue(standardCard.contains("indication = null"))
    }

    @Test
    fun nodeCardsLiquidBranchesUsePressedFeedbackWithoutMaterialRipple() {
        val nodeCard = File("src/main/java/com/kunk/singbox/ui/components/NodeCard.kt").readText()

        assertTrue(nodeCard.contains("val listInteractionSource = remember { MutableInteractionSource() }"))
        assertTrue(nodeCard.contains("val gridInteractionSource = remember { MutableInteractionSource() }"))
        assertTrue(nodeCard.contains("collectIsPressedAsState"))
        assertTrue(nodeCard.contains("animateFloatAsState"))
        assertTrue(nodeCard.contains("graphicsLayer"))
        assertTrue(nodeCard.contains("indication = null"))
        assertTrue(nodeCard.contains("liquidGlassNodeCardScale"))
    }

    @Test
    fun statusChipLiquidBranchUsesPressedFeedbackWithoutMaterialRipple() {
        val statusChip = File("src/main/java/com/kunk/singbox/ui/components/StatusChip.kt").readText()

        assertTrue(statusChip.contains("MutableInteractionSource"))
        assertTrue(statusChip.contains("collectIsPressedAsState"))
        assertTrue(statusChip.contains("animateFloatAsState"))
        assertTrue(statusChip.contains("graphicsLayer"))
        assertTrue(statusChip.contains("indication = null"))
    }

    @Test
    fun clickableDropdownFieldLiquidBranchUsesPressedFeedbackWithoutMaterialRipple() {
        val dropdownField = File("src/main/java/com/kunk/singbox/ui/components/ClickableDropdownField.kt").readText()

        assertTrue(dropdownField.contains("MutableInteractionSource"))
        assertTrue(dropdownField.contains("collectIsPressedAsState"))
        assertTrue(dropdownField.contains("animateFloatAsState"))
        assertTrue(dropdownField.contains("graphicsLayer"))
        assertTrue(dropdownField.contains("indication = null"))
    }

    @Test
    fun nodeSelectorItemUsesPressedFeedbackWithoutMaterialRipple() {
        val nodeSelectionDialogs = File(
            "src/main/java/com/kunk/singbox/ui/components/NodeSelectionDialogs.kt"
        ).readText()

        assertTrue(nodeSelectionDialogs.contains("nodeSelectorItemPressFeedback"))
        assertTrue(nodeSelectionDialogs.contains("MutableInteractionSource"))
        assertTrue(nodeSelectionDialogs.contains("collectIsPressedAsState"))
        assertTrue(nodeSelectionDialogs.contains("animateFloatAsState"))
        assertTrue(nodeSelectionDialogs.contains("graphicsLayer"))
        assertTrue(nodeSelectionDialogs.contains("indication = null"))
    }

    @Test
    fun appRoutingItemsUsePressedFeedbackWithoutMaterialRipple() {
        val appRoutingComponents = File(
            "src/main/java/com/kunk/singbox/ui/screens/AppRoutingComponents.kt"
        ).readText()

        assertTrue(appRoutingComponents.contains("routingItemPressFeedback"))
        assertTrue(appRoutingComponents.contains("MutableInteractionSource"))
        assertTrue(appRoutingComponents.contains("collectIsPressedAsState"))
        assertTrue(appRoutingComponents.contains("animateFloatAsState"))
        assertTrue(appRoutingComponents.contains("graphicsLayer"))
        assertTrue(appRoutingComponents.contains("indication = null"))
        assertTrue(appRoutingComponents.countOccurrences(".routingItemPressFeedback(") >= 2)
        assertTrue(appRoutingComponents.countOccurrences("StandardCard(onClick = onClick)") >= 2)
    }

    @Test
    fun ruleItemsUsePressedFeedbackWithoutMaterialRipple() {
        val ruleFiles = mapOf(
            "src/main/java/com/kunk/singbox/ui/screens/CustomRulesScreen.kt" to "customRuleItemPressFeedback",
            "src/main/java/com/kunk/singbox/ui/screens/DomainRulesScreen.kt" to "domainRuleItemPressFeedback",
            "src/main/java/com/kunk/singbox/ui/screens/RuleSetsDialogs.kt" to "ruleSetItemPressFeedback"
        )

        ruleFiles.forEach { (filePath, marker) ->
            val source = File(filePath).readText()
            assertTrue(source.contains(marker))
            assertTrue(source.contains("MutableInteractionSource"))
            assertTrue(source.contains("collectIsPressedAsState"))
            assertTrue(source.contains("animateFloatAsState"))
            assertTrue(source.contains("graphicsLayer"))
            assertTrue(source.contains("indication = null"))
        }
    }

    @Test
    fun settingItemUsesPressedFeedbackWithoutMaterialRipple() {
        val settingItem = File("src/main/java/com/kunk/singbox/ui/components/SettingItem.kt").readText()

        assertTrue(settingItem.contains("settingItemPressFeedback"))
        assertTrue(settingItem.contains("MutableInteractionSource"))
        assertTrue(settingItem.contains("collectIsPressedAsState"))
        assertTrue(settingItem.contains("animateFloatAsState"))
        assertTrue(settingItem.contains("graphicsLayer"))
        assertTrue(settingItem.contains("indication = null"))
    }

    @Test
    fun profileCardUsesPressedFeedbackWithoutMaterialRipple() {
        val profileCard = File("src/main/java/com/kunk/singbox/ui/components/ProfileCard.kt").readText()

        assertTrue(profileCard.contains("profileCardPressFeedback"))
        assertTrue(profileCard.contains("MutableInteractionSource"))
        assertTrue(profileCard.contains("collectIsPressedAsState"))
        assertTrue(profileCard.contains("animateFloatAsState"))
        assertTrue(profileCard.contains("graphicsLayer"))
        assertTrue(profileCard.contains("indication = null"))
    }

    @Test
    fun infoCardUsesPressedFeedbackWithoutMaterialRipple() {
        val infoCard = File("src/main/java/com/kunk/singbox/ui/components/InfoCard.kt").readText()

        assertTrue(infoCard.contains("infoCardPingPressFeedback"))
        assertTrue(infoCard.contains("MutableInteractionSource"))
        assertTrue(infoCard.contains("collectIsPressedAsState"))
        assertTrue(infoCard.contains("animateFloatAsState"))
        assertTrue(infoCard.contains("graphicsLayer"))
        assertTrue(infoCard.contains("indication = null"))
    }

    @Test
    fun uiFilesWithoutLiquidGlassMarkersStayInPassThroughAllowList() {
        val uiDir = File("src/main/java/com/kunk/singbox/ui")
        val filesWithoutLiquidGlassMarkers = uiDir.walkTopDown()
            .filter { file -> file.extension == "kt" }
            .filterNot { file ->
                val source = file.readText()
                liquidGlassMarkerPatterns.any(source::contains)
            }
            .map { file -> file.relativeTo(uiDir).invariantSeparatorsPath }
            .sorted()
            .toList()

        assertEquals(passThroughFilesWithoutLiquidGlassMarkers, filesWithoutLiquidGlassMarkers)
    }

    @Test
    fun nativeXmlBackgroundEntrypointsStayInAllowList() {
        val resDir = File("src/main/res")
        val backgroundLines = resDir.walkTopDown()
            .filter { file -> file.extension == "xml" }
            .flatMap { file ->
                file.readLines()
                    .asSequence()
                    .mapIndexedNotNull { index, line ->
                        val trimmed = line.trim()
                        if (nativeBackgroundPatterns.none(trimmed::contains)) {
                            null
                        } else {
                            "${file.relativeTo(resDir).invariantSeparatorsPath}:${index + 1}:$trimmed"
                        }
                    }
            }
            .sorted()
            .toList()

        assertEquals(nativeXmlBackgroundAllowList, backgroundLines)
    }

    private fun liquidControlSources(): String {
        val chips = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassChipControls.kt").readText()
        val menus = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassMenuControls.kt").readText()
        return chips + menus
    }

    private fun directMaterialCallsOutsideAllowedFiles(
        uiDir: File,
        callName: String,
        allowedFiles: Set<String>
    ): List<String> {
        val callPattern = Regex("(?<![A-Za-z0-9_.])${Regex.escape(callName)}\\s*\\(")
        return uiDir.walkTopDown()
            .filter { file -> file.extension == "kt" }
            .flatMap { file ->
                val relativePath = file.relativeTo(uiDir).invariantSeparatorsPath
                file.readLines()
                    .asSequence()
                    .mapIndexedNotNull { index, line ->
                        if (!callPattern.containsMatchIn(line) || relativePath in allowedFiles) {
                            null
                        } else {
                            "$relativePath:${index + 1}:${line.trim()}"
                        }
                    }
            }
            .toList()
    }

    private fun listDirectMaterialCalls(
        sourceDir: File,
        callNames: List<String>
    ): List<String> {
        val callPatterns = callNames.map { callName ->
            callName to Regex("(?<![A-Za-z0-9_.])${Regex.escape(callName)}\\s*\\(")
        }
        return sourceDir.walkTopDown()
            .filter { file -> file.extension == "kt" }
            .flatMap { file ->
                val relativePath = file.relativeTo(sourceDir).invariantSeparatorsPath
                file.readLines()
                    .asSequence()
                    .mapIndexedNotNull { index, line ->
                        val callName = callPatterns.firstOrNull { (_, pattern) ->
                            pattern.containsMatchIn(line)
                        }?.first ?: return@mapIndexedNotNull null
                        "$relativePath:${index + 1}:$callName:${line.trim()}"
                    }
            }
            .sorted()
            .toList()
    }

    private fun materialCallRules(): List<MaterialCallRule> {
        return listOf(
            materialCallRule(
                "AlertDialog",
                listOf("liquidGlassDialogPanel"),
                listOf("liquidGlassDialogContainerColor")
            ),
            materialCallRule("Button", listOf("liquidGlassButtonPanel")),
            materialCallRule(
                "OutlinedButton",
                listOf("liquidGlassButtonPanel"),
                listOf("liquidGlassOutlinedButtonBorder")
            ),
            materialCallRule("TextButton", listOf("liquidGlassTextButtonPanel")),
            materialCallRule("IconButton", listOf("liquidGlassIconButtonPanel", "connectionCloseButtonPanel")),
            materialCallRule(
                "FloatingActionButton",
                listOf("liquidGlassFloatingActionPanel"),
                listOf("liquidGlassFloatingActionContainerColor", "fabContainerColor")
            ),
            materialCallRule(
                "SmallFloatingActionButton",
                listOf("liquidGlassFloatingActionPanel"),
                listOf("liquidGlassFloatingActionContainerColor", "fabContainerColor")
            ),
            materialCallRule("Switch", listOf("liquidGlassSwitchColors")),
            materialCallRule("Checkbox", listOf("liquidGlassCheckboxColors")),
            materialCallRule("RadioButton", listOf("liquidGlassRadioButtonColors")),
            materialCallRule(
                "OutlinedTextField",
                listOf("liquidGlassTextFieldPanel"),
                listOf("liquidGlassTextFieldContainerColor"),
                listOf("liquidGlassTextFieldBorderColor")
            ),
            materialCallRule("DropdownMenuItem", listOf("liquidGlassDropdownMenuItemColors")),
            materialCallRule("LinearProgressIndicator", listOf("liquidGlassProgressColor")),
            materialCallRule("CircularProgressIndicator", listOf("liquidGlassProgressColor")),
            materialCallRule("HorizontalDivider", listOf("liquidGlassDividerColor")),
            materialCallRule("Divider", listOf("liquidGlassDividerColor")),
            materialCallRule(
                "TabRow",
                listOf("liquidGlassTabRowPanel"),
                listOf("liquidGlassTabIndicatorColor")
            ),
            materialCallRule(
                "Scaffold",
                listOf("liquidGlassTopAppBarContainerColor", "liquidGlassScreenContainerColor")
            ),
            materialCallRule("TopAppBar", listOf("liquidGlassTopAppBarContainerColor"))
        )
    }

    private fun materialCallParameterFailures(
        uiDir: File,
        rule: MaterialCallRule
    ): List<String> {
        val callPattern = Regex(
            "(?<![A-Za-z0-9_])(?:androidx\\.compose\\.material3\\.)?${rule.callName}\\s*\\("
        )
        return uiDir.walkTopDown()
            .filter { file -> file.extension == "kt" }
            .filterNot { file -> file.relativeTo(uiDir).invariantSeparatorsPath in rule.allowedFiles }
            .flatMap { file ->
                val source = file.readText()
                val relativePath = file.relativeTo(uiDir).invariantSeparatorsPath
                callPattern.findAll(source)
                    .mapNotNull { match ->
                        materialCallFailure(
                            rule = rule,
                            source = source,
                            relativePath = relativePath,
                            startIndex = match.range.first
                        )
                    }
            }
            .toList()
    }

    private fun materialCallFailure(
        rule: MaterialCallRule,
        source: String,
        relativePath: String,
        startIndex: Int
    ): String? {
        val functionName = enclosingFunctionName(source = source, offset = startIndex)
        if (functionName in rule.allowedFunctions) return null
        val callBlock = source.extractCallBlock(startIndex)
        val missing = rule.requiredMarkerGroups.filterNot { group ->
            group.any(callBlock::contains)
        }
        if (missing.isEmpty()) return null

        val missingText = missing.joinToString { group -> group.joinToString("|") }
        return "$relativePath:${source.lineNumberAt(startIndex)}:${rule.callName}:$missingText"
    }

    private fun String.extractCallBlock(startIndex: Int): String {
        val openParenIndex = indexOf('(', startIndex)
        if (openParenIndex < 0) return substring(startIndex)
        var depth = 0
        for (index in openParenIndex until length) {
            when (this[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return substring(startIndex, index + 1)
                }
            }
        }
        return substring(startIndex)
    }

    private fun enclosingFunctionName(source: String, offset: Int): String {
        return Regex("""fun\s+([A-Za-z0-9_]+)""")
            .findAll(source.substring(0, offset))
            .lastOrNull()
            ?.groupValues
            ?.get(1)
            .orEmpty()
    }

    private fun String.lineNumberAt(offset: Int): Int {
        return substring(0, offset).count { it == '\n' } + 1
    }

    private fun String.countOccurrences(pattern: String): Int {
        return split(pattern).size - 1
    }

    private fun List<String>.assertScreenSourcesContain(pattern: String) {
        forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/screens/$fileName").readText()
            assertTrue("$fileName should contain $pattern", source.contains(pattern))
        }
    }

    private fun materialCallRule(
        callName: String,
        vararg requiredMarkerGroups: List<String>
    ): MaterialCallRule {
        return MaterialCallRule(
            callName = callName,
            requiredMarkerGroups = requiredMarkerGroups.toList()
        )
    }

    private data class MaterialCallRule(
        val callName: String,
        val requiredMarkerGroups: List<List<String>>,
        val allowedFunctions: Set<String> = emptySet(),
        val allowedFiles: Set<String> = emptySet()
    )

    private companion object {
        val liquidGlassMarkerPatterns = listOf(
            "liquidGlass",
            "LiquidGlass",
            "isLiquidGlassTheme",
            "LocalAppThemeStyle",
            "AppThemeStyle"
        )

        val passThroughFilesWithoutLiquidGlassMarkers = listOf(
            "ShortcutActivity.kt",
            "components/EditableSettingItem.kt",
            "navigation/AppNavigation.kt",
            "screens/NodeProtocolFields.kt",
            "theme/Color.kt",
            "theme/Type.kt"
        )

        val nativeBackgroundPatterns = listOf(
            "android:background=",
            "android:windowBackground"
        )

        val nativeXmlBackgroundAllowList = listOf(
            "layout/activity_qr_scanner.xml:23:android:background=\"@android:color/transparent\">",
            "layout/activity_qr_scanner.xml:29:android:background=\"?android:attr/selectableItemBackgroundBorderless\"",
            "layout/activity_qr_scanner.xml:48:android:background=\"?android:attr/selectableItemBackgroundBorderless\"",
            "layout/activity_qr_scanner.xml:57:android:background=\"?android:attr/selectableItemBackgroundBorderless\"",
            "layout/activity_qr_scanner.xml:6:android:background=\"@android:color/black\">",
            "layout/custom_barcode_scanner.xml:24:android:background=\"@android:color/transparent\"",
            "values/themes.xml:16:<item name=\"android:windowBackground\">@android:color/transparent</item>",
            "values/themes.xml:8:<item name=\"android:windowBackground\">@color/black</item>"
        )

        val directMaterialSurfaceAllowList = listOf(
            "MainActivity.kt:350:Surface:Surface(",
            "ui/components/AppNavBar.kt:111:NavigationBar:NavigationBar(",
            "ui/components/AppNavBar.kt:164:NavigationBarItem:NavigationBarItem(",
            "ui/components/ExportImportDialogs.kt:54:Card:Card(",
            "ui/components/StandardCard.kt:64:Card:Card(",
            "ui/components/StandardCard.kt:75:Card:Card(",
            "ui/screens/ConnectionInfoScreen.kt:569:Card:Card(",
            "ui/screens/ConnectionInfoScreen.kt:708:Card:Card(",
            "ui/screens/RuleSetHubScreen.kt:319:Card:Card(",
            "ui/theme/LiquidGlassChipControls.kt:75:FilterChip:FilterChip(",
            "ui/theme/LiquidGlassMenuControls.kt:21:DropdownMenu:DropdownMenu(",
            "ui/theme/LiquidGlassMenuControls.kt:31:DropdownMenu:DropdownMenu("
        )
    }
}
