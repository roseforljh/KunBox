package com.kunk.singbox.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileCardLayoutTest {

    @Test
    fun profileCardKeepsDisabledAndDnsBadgesVisible() {
        val source = File("src/main/java/com/kunk/singbox/ui/components/ProfileCard.kt")
            .readNormalizedText()

        assertTrue(source.contains("Column(modifier = Modifier.weight(1f))"))
        assertTrue(source.contains("text = formatLastUpdated(lastUpdated)"))
        assertTrue(source.contains("if (!isEnabled)"))
        assertTrue(source.contains("text = stringResource(R.string.common_disabled)"))
        assertTrue(source.contains("softWrap = false"))
        assertTrue(source.contains("maxLines = 1"))
        assertTrue(source.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(source.contains("modifier = Modifier.weight(1f)"))
        assertTrue(
            source.contains(
                "Spacer(modifier = Modifier.width(12.dp))\n\n        Box(modifier = Modifier.wrapContentSize"
            )
        )
    }

    @Test
    fun updateStageDoesNotAddExtraRowHeight() {
        val source = File("src/main/java/com/kunk/singbox/ui/components/ProfileCard.kt")
            .readNormalizedText()

        // 更新阶段只能出现在更新时间同一行，不能另起一行撑高卡片
        assertTrue(source.contains("if (updateStage != null)"))
        assertTrue(source.contains("text = stringResource(updateStage.labelRes)"))
        assertTrue(
            !source.contains(
                "if (updateStage != null) {\n                    Spacer(modifier = Modifier.height(6.dp))"
            )
        )
        assertTrue(source.contains("更新阶段放在同一行，禁止新增行导致卡片高度变化"))
    }

    @Test
    fun profileListCardUsesStableNeutralEmbossedShadow() {
        val source = File("src/main/java/com/kunk/singbox/ui/components/ProfileCard.kt")
            .readNormalizedText()
        val cardModifierBody = source
            .substringAfter("val cardModifier = if (useLiquidGlass)")
            .substringBefore("fun formatDate")
        val themeSource = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassTheme.kt")
            .readNormalizedText()
        val panelBody = themeSource
            .substringAfter("fun Modifier.liquidGlassPanel(")
            .substringBefore("fun Modifier.hollowShadow(")

        // 全局控件使用同色浮雕边缘，配置主卡保留独立轻阴影，并 remember 阴影段
        assertTrue(panelBody.contains("shadowElevation: Dp = 12.dp"))
        assertTrue(panelBody.contains("val edgeShadowColor = if (isDark) Color.White else Color.Black"))
        assertTrue(panelBody.contains("val edgeShadowAlpha = if (isDark) 0.08f else 0.12f"))
        assertTrue(panelBody.contains("offsetY = edgeShadowOffsetY"))
        assertTrue(source.contains("private val ProfileCardShadowBlur = 4.dp"))
        assertTrue(source.contains("private val ProfileCardShadowOffsetY = 1.5.dp"))
        assertTrue(source.contains("private const val PROFILE_CARD_SHADOW_ALPHA_DARK = 0.10f"))
        assertTrue(source.contains("private const val PROFILE_CARD_SHADOW_ALPHA_LIGHT = 0.03f"))
        assertTrue(source.contains("remember(shape, isDark)"))
        assertTrue(source.contains(".liquidGlassMaterial(shape = shape, selected = selected)"))
        assertTrue(source.contains("profileListCardPanel("))
        assertTrue(source.contains("val shape = remember { RoundedCornerShape(16.dp) }"))
        assertTrue(
            source.contains(
                "liquidGlassPanel(shape = shape, selected = true, shadowElevation = 0.dp)"
            )
        )
        assertTrue(source.contains("SelectedPulseIndicator("))
        assertTrue(source.contains("selected = isSelected"))
        assertTrue(source.contains("animationLabel = \"profile_selected\""))
        assertTrue(source.contains("semantics { selected = isSelected }"))
        assertTrue(!source.contains("imageVector = Icons.Rounded.Check,"))
        assertTrue(
            cardModifierBody.indexOf("profileListCardPanel(") <
                cardModifierBody.indexOf("profileCardPressFeedback(")
        )
    }
}

private fun File.readNormalizedText(): String = readText().replace("\r\n", "\n")
