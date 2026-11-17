package com.taskai.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.taskai.app.voice.AndroidVoiceController
import com.taskai.app.voice.VoiceController
import com.taskai.app.voice.VoiceUiState
import kotlinx.coroutines.flow.collect

// The production factory always uses Android services. Replacement is scoped to UI tests.
typealias VoiceControllerFactory = (Context, (VoiceUiState) -> Unit, (String) -> Unit) -> VoiceController
val LocalVoiceControllerFactory = staticCompositionLocalOf<VoiceControllerFactory> {
    { context, state, recognized -> AndroidVoiceController(context, state, recognized) }
}

internal data class ChatVoiceHandle(
    val state: VoiceUiState,
    val error: String?,
    val permissionDenied: Boolean,
    val begin: () -> Unit,
    val finish: () -> Unit,
    val cancel: () -> Unit,
    val toggle: (Boolean) -> Unit,
    val stopSpeaking: () -> Unit,
    val openPermissions: () -> Unit
)

@Composable internal fun rememberChatVoice(vm: AppViewModel, onTranscript: (String) -> Unit): ChatVoiceHandle {
    val context = LocalContext.current
    val application = context.applicationContext
    val owner = LocalLifecycleOwner.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val factory = LocalVoiceControllerFactory.current
    val transcript = rememberUpdatedState(onTranscript)
    var state by remember { mutableStateOf(VoiceUiState()) }
    var permissionError by remember { mutableStateOf<String?>(null) }
    var permissionRequested by remember { mutableStateOf(false) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val controller = remember(application, factory) {
        factory(application, { state = it }, { transcript.value(it) })
    }
    val start: () -> Unit = {
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !vm.chatting.value && !vm.pendingConfirmation.value) {
            permissionError = null
            focus.clearFocus()
            keyboard?.hide()
            controller.startListening()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (permissionRequested) {
            permissionRequested = false
            if (granted) start()
            else permissionError = "Permita o microfone nas configurações do Android para falar com a assistente."
        }
    }
    LaunchedEffect(controller, settings.spokenRepliesEnabled) {
        controller.setSpokenRepliesEnabled(settings.spokenRepliesEnabled)
    }
    LaunchedEffect(controller, vm, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.spokenReplies.collect { event ->
                if (vm.settings.value.spokenRepliesEnabled && event.conversationId == vm.conversation.value) {
                    controller.speak(event.text)
                }
            }
        }
    }
    DisposableEffect(controller, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                permissionRequested = false
                controller.cancelListening()
                controller.stopSpeaking()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            permissionRequested = false
            owner.lifecycle.removeObserver(observer)
            controller.close()
        }
    }
    return ChatVoiceHandle(
        state = state,
        error = permissionError ?: state.error,
        permissionDenied = permissionError != null,
        begin = {
            controller.stopSpeaking()
            permissionError = null
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
            else {
                permissionRequested = true
                permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        finish = controller::finishListening,
        cancel = {
            permissionRequested = false
            controller.cancelListening()
            controller.stopSpeaking()
            permissionError = null
        },
        toggle = { enabled ->
            controller.setSpokenRepliesEnabled(enabled)
            vm.setSpokenRepliesEnabled(enabled)
        },
        stopSpeaking = controller::stopSpeaking,
        openPermissions = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            try { context.startActivity(intent) }
            catch (_: Exception) { permissionError = "Abra as configurações do Android e permita o microfone para o Task AI." }
        }
    )
}
