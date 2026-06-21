package com.kunk.singbox.ui.theme

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun liquidGlassOutlinedTextFieldColors(
    focusedTextColor: Color = Color.Unspecified,
    unfocusedTextColor: Color = Color.Unspecified,
    disabledTextColor: Color = Color.Unspecified,
    focusedBorderColor: Color = Color.Unspecified,
    unfocusedBorderColor: Color = Color.Unspecified,
    disabledBorderColor: Color = Color.Unspecified,
    focusedContainerColor: Color = Color.Unspecified,
    unfocusedContainerColor: Color = Color.Unspecified,
    disabledContainerColor: Color = Color.Unspecified,
    cursorColor: Color = Color.Unspecified,
    focusedLabelColor: Color = Color.Unspecified,
    unfocusedLabelColor: Color = Color.Unspecified,
    disabledLabelColor: Color = Color.Unspecified,
    disabledTrailingIconColor: Color = Color.Unspecified
): TextFieldColors {
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = focusedTextColor,
        unfocusedTextColor = unfocusedTextColor,
        disabledTextColor = disabledTextColor,
        focusedBorderColor = liquidGlassTextFieldBorderColor(focusedBorderColor),
        unfocusedBorderColor = liquidGlassTextFieldBorderColor(unfocusedBorderColor),
        disabledBorderColor = liquidGlassTextFieldBorderColor(disabledBorderColor),
        focusedContainerColor = liquidGlassTextFieldContainerColor(focusedContainerColor),
        unfocusedContainerColor = liquidGlassTextFieldContainerColor(unfocusedContainerColor),
        disabledContainerColor = liquidGlassTextFieldContainerColor(disabledContainerColor),
        cursorColor = cursorColor,
        focusedLabelColor = focusedLabelColor,
        unfocusedLabelColor = unfocusedLabelColor,
        disabledLabelColor = disabledLabelColor,
        disabledTrailingIconColor = disabledTrailingIconColor
    )
}
