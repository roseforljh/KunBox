package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.ui.components.FloatingPageLayout
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassLoadingStatePanel
import com.kunk.singbox.ui.theme.liquidGlassPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.kunk.singbox.ui.theme.liquidGlassTopAppBarContainerColor
import java.util.Locale

@Composable
private fun Modifier.profileEditorPanel(): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp)
    } else {
        background(MaterialTheme.colorScheme.background)
    }
}

internal class ProfileEditorViewModel : ViewModel() {
    var content by mutableStateOf("")
        private set

    var isLoading by mutableStateOf(true)
        private set

    var isSaving by mutableStateOf(false)
        private set

    private var loadedProfileId: String? = null

    fun shouldLoad(profileId: String): Boolean {
        return loadedProfileId != profileId
    }

    fun beginLoading(profileId: String) {
        loadedProfileId = profileId
        isLoading = true
    }

    fun finishLoading(loadedContent: String) {
        content = loadedContent
        isLoading = false
    }

    fun failLoading() {
        loadedProfileId = null
        isLoading = false
    }

    fun updateContent(value: String) {
        content = value
    }

    fun updateSaving(value: Boolean) {
        isSaving = value
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(navController: NavController, profileId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configRepository = remember { ConfigRepository.getInstance(context) }
    val editorViewModel: ProfileEditorViewModel = viewModel(key = profileId)
    val contentTooLargeMessage = stringResource(R.string.profiles_import_content_too_large)

    ProfileEditorLoadEffect(
        profileId = profileId,
        configRepository = configRepository,
        navController = navController,
        editorViewModel = editorViewModel
    )

    FloatingPageLayout(
        title = stringResource(R.string.profile_editor_title),
        onBack = { navController.popBackStack() },
        actions = {
            IconButton(
                enabled = !editorViewModel.isLoading && !editorViewModel.isSaving,
                onClick = {
                    if (isProfileContentTooLarge(editorViewModel.content)) {
                        AppNotificationManager.showMessage(context, contentTooLargeMessage)
                    } else {
                        configRepository.saveProfileContent(
                            scope = scope,
                            profileId = profileId,
                            content = editorViewModel.content,
                            onSavingChanged = editorViewModel::updateSaving,
                            onResult = { result ->
                                handleProfileSaveResult(context, navController, result)
                            }
                        )
                    }
                }
            ) {
                Icon(
                    Icons.Rounded.Save,
                    contentDescription = stringResource(R.string.common_save),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    ) { contentTopPadding ->
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = liquidGlassTopAppBarContainerColor(MaterialTheme.colorScheme.background)
        ) { padding ->
            ProfileEditorContent(
                content = editorViewModel.content,
                isLoading = editorViewModel.isLoading,
                isSaving = editorViewModel.isSaving,
                onContentChange = { editorViewModel.updateContent(it) },
                contentTopPadding = contentTopPadding,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }
}

@Composable
private fun ProfileEditorLoadEffect(
    profileId: String,
    configRepository: ConfigRepository,
    navController: NavController,
    editorViewModel: ProfileEditorViewModel
) {
    val context = LocalContext.current
    val importFailedFormat = stringResource(R.string.profiles_import_failed, "%s")
    LaunchedEffect(profileId) {
        if (!editorViewModel.shouldLoad(profileId)) return@LaunchedEffect

        editorViewModel.beginLoading(profileId)
        val result = configRepository.readProfileConfigContent(profileId)
        result.fold(
            onSuccess = editorViewModel::finishLoading,
            onFailure = { error ->
                editorViewModel.failLoading()
                AppNotificationManager.showMessage(
                    context,
                    String.format(Locale.getDefault(), importFailedFormat, error.message)
                )
                navController.popBackStack()
            }
        )
    }
}

@Composable
private fun ProfileEditorContent(
    content: String,
    isLoading: Boolean,
    isSaving: Boolean,
    onContentChange: (String) -> Unit,
    contentTopPadding: Dp,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        if (isLoading) {
            ProfileEditorLoadingState()
        } else {
            BasicTextField(
                value = content,
                onValueChange = onContentChange,
                enabled = !isSaving,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onBackground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxSize()
                    .profileEditorPanel()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 16.dp,
                        top = contentTopPadding + 16.dp,
                        end = 16.dp,
                        bottom = 16.dp
                    )
            )
        }
    }
}

@Composable
private fun ProfileEditorLoadingState() {
    val loadingText = stringResource(R.string.common_loading)
    if (isLiquidGlassTheme()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.liquidGlassLoadingStatePanel(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = loadingText,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    } else {
        Text(
            text = loadingText,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

private fun ConfigRepository.saveProfileContent(
    scope: CoroutineScope,
    profileId: String,
    content: String,
    onSavingChanged: (Boolean) -> Unit,
    onResult: (Result<Unit>) -> Unit
) {
    onSavingChanged(true)
    scope.launch {
        val result = updateProfileConfigContent(profileId, content).map {}
        onSavingChanged(false)
        onResult(result)
    }
}

private fun isProfileContentTooLarge(content: String): Boolean {
    return content.toByteArray(Charsets.UTF_8).size >
        com.kunk.singbox.viewmodel.ProfilesViewModel.MAX_IMPORT_CONTENT_BYTES
}

private fun handleProfileSaveResult(
    context: android.content.Context,
    navController: NavController,
    result: Result<Unit>
) {
    result.fold(
        onSuccess = {
            AppNotificationManager.showMessage(context, context.getString(R.string.profiles_updated))
            navController.popBackStack()
        },
        onFailure = { error ->
            AppNotificationManager.showMessage(
                context,
                context.getString(R.string.profiles_import_failed, error.message)
            )
        }
    )
}
