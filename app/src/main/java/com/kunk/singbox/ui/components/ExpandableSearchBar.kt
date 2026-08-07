package com.kunk.singbox.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kunk.singbox.R
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassIconButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassMutedContentColor
import com.kunk.singbox.ui.theme.liquidGlassPanel

@Composable
private fun Modifier.expandableSearchFieldPanel(): Modifier {
    val shape = RoundedCornerShape(percent = 50)
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 8.dp)
    } else {
        background(MaterialTheme.colorScheme.surface, shape)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = shape
            )
    }
}

@Composable
@Suppress("LongMethod", "LongParameterList", "CognitiveComplexMethod")
fun ExpandableSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    collapsedContent: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        IconButton(
            onClick = onToggle,
            modifier = Modifier
                .size(40.dp)
                .liquidGlassIconButtonPanel(selected = false)
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Rounded.Close else Icons.Rounded.Search,
                contentDescription = stringResource(
                    if (isExpanded) R.string.common_close else R.string.common_search
                ),
                tint = if (isExpanded) {
                    MaterialTheme.colorScheme.primary
                } else {
                    liquidGlassMutedContentColor(Neutral500)
                },
                modifier = Modifier.size(24.dp)
            )
        }

        AnimatedVisibility(
            visible = !isExpanded,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .padding(start = 48.dp, end = 8.dp)
                .fillMaxWidth()
        ) {
            collapsedContent()
        }

        val searchAlpha by animateFloatAsState(
            targetValue = if (isExpanded) 1f else 0f,
            animationSpec = tween(durationMillis = 300),
            label = "expandable_search_alpha"
        )

        if (searchAlpha > 0f) {
            var isFocused by remember { mutableStateOf(false) }
            val focusRequester = remember { FocusRequester() }

            LaunchedEffect(isExpanded) {
                if (isExpanded) {
                    focusRequester.requestFocus()
                }
            }

            Box(
                modifier = Modifier
                    .padding(start = 52.dp)
                    .fillMaxWidth()
                    .height(40.dp)
                    .graphicsLayer {
                        alpha = searchAlpha
                        translationX = (1f - searchAlpha) * (-15.dp.toPx())
                        scaleX = 0.96f + 0.04f * searchAlpha
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .expandableSearchFieldPanel(),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 16.dp,
                            top = 10.dp,
                            end = if (query.isEmpty()) 16.dp else 44.dp,
                            bottom = 10.dp
                        )
                        .focusRequester(focusRequester)
                        .onFocusChanged { isFocused = it.isFocused },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (query.isEmpty() && !isFocused) {
                                Text(
                                    text = placeholder,
                                    color = liquidGlassMutedContentColor(Neutral500),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 6.dp)
                            .size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.common_clear),
                            tint = liquidGlassMutedContentColor(Neutral500),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
