/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.WakeWordMonitor
import com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting.MeetingVoiceCommand
import com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting.MeetingRecordingViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingMode
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc.WebRTCSessionViewModel

@Composable
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    isPhoneMode: Boolean = false,
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
    geminiViewModel: GeminiSessionViewModel = viewModel(),
    webrtcViewModel: WebRTCSessionViewModel = viewModel(),
    meetingRecordingViewModel: MeetingRecordingViewModel = viewModel(),
) {
    val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
    val geminiUiState by geminiViewModel.uiState.collectAsStateWithLifecycle()
    val webrtcUiState by webrtcViewModel.uiState.collectAsStateWithLifecycle()
    val meetingRecordingUiState by meetingRecordingViewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val currentStartWakeWordSession = rememberUpdatedState<(String) -> Unit>({ rawText ->
        if (!meetingRecordingUiState.isRecording) {
            geminiViewModel.startSession(
                wakeWordInitiated = true,
                initialText = WakeWordMonitor.stripWakeWord(rawText),
            )
        }
    })
    val currentHandleMeetingVoiceCommand = rememberUpdatedState<(MeetingVoiceCommand) -> Unit>({ command ->
        when (command) {
            MeetingVoiceCommand.START -> {
                if (meetingRecordingUiState.isRecording || meetingRecordingUiState.isProcessing) {
                    return@rememberUpdatedState
                }
                if (geminiUiState.isGeminiActive) {
                    geminiViewModel.stopSession()
                }
                meetingRecordingViewModel.startRecording()
                Toast.makeText(context, "회의 시작하겠습니다.", Toast.LENGTH_SHORT).show()
            }
            MeetingVoiceCommand.STOP -> {
                if (!meetingRecordingUiState.isRecording) {
                    return@rememberUpdatedState
                }
                meetingRecordingViewModel.stopAndProcess()
                Toast.makeText(context, "회의 녹음을 종료하고 정리합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    })
    val wakeWordMonitor = remember(context) {
        WakeWordMonitor(
            context = context.applicationContext,
            onWakeWord = { rawText ->
                currentStartWakeWordSession.value(rawText)
            },
            onMeetingCommand = { command, _ ->
                currentHandleMeetingVoiceCommand.value(command)
            },
        )
    }

    fun leaveStream() {
        if (geminiUiState.isGeminiActive) geminiViewModel.stopSession()
        if (webrtcUiState.isActive) webrtcViewModel.stopSession()
        meetingRecordingViewModel.cancelRecording()
        streamViewModel.stopStream()
        wearablesViewModel.navigateToDeviceSelection()
    }

    BackHandler {
        leaveStream()
    }

    // Wire Gemini VM to Stream VM for frame forwarding
    LaunchedEffect(geminiViewModel) {
        streamViewModel.geminiViewModel = geminiViewModel
    }

    // Wire WebRTC VM to Stream VM for frame forwarding
    LaunchedEffect(webrtcViewModel) {
        streamViewModel.webrtcViewModel = webrtcViewModel
    }

    // Start stream or phone camera
    LaunchedEffect(isPhoneMode) {
        if (isPhoneMode) {
            geminiViewModel.streamingMode = StreamingMode.PHONE
            streamViewModel.startPhoneCamera(lifecycleOwner)
        } else {
            geminiViewModel.streamingMode = StreamingMode.GLASSES
            streamViewModel.startStream()
        }
    }

    LaunchedEffect(geminiViewModel) {
        geminiViewModel.voiceCommands.collect { command ->
            currentHandleMeetingVoiceCommand.value(command)
        }
    }

    LaunchedEffect(geminiUiState.isGeminiActive, meetingRecordingUiState.isProcessing) {
        if (!geminiUiState.isGeminiActive && !meetingRecordingUiState.isProcessing) {
            wakeWordMonitor.start()
        } else {
            wakeWordMonitor.stop()
        }
    }

    // Clean up on exit
    DisposableEffect(Unit) {
        onDispose {
            wakeWordMonitor.release()
            if (geminiUiState.isGeminiActive) {
                geminiViewModel.stopSession()
            }
            if (webrtcUiState.isActive) {
                webrtcViewModel.stopSession()
            }
            meetingRecordingViewModel.cancelRecording()
        }
    }

    // Show errors as toasts
    LaunchedEffect(geminiUiState.errorMessage) {
        geminiUiState.errorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            geminiViewModel.clearError()
        }
    }
    LaunchedEffect(webrtcUiState.errorMessage) {
        webrtcUiState.errorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            webrtcViewModel.clearError()
        }
    }
    LaunchedEffect(webrtcUiState.viewerUrl) {
        if (webrtcUiState.viewerUrl.isNotEmpty()) {
            Toast.makeText(context, "Live link ready. Tap Share.", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(streamUiState.errorMessage) {
        streamUiState.errorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(meetingRecordingUiState.errorMessage) {
        meetingRecordingUiState.errorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            meetingRecordingViewModel.clearMessage()
        }
    }
    LaunchedEffect(meetingRecordingUiState.result) {
        meetingRecordingUiState.result?.let { result ->
            Toast.makeText(
                context,
                "회의 저장 완료: ${result.summary.take(120)}",
                Toast.LENGTH_LONG,
            ).show()
            meetingRecordingViewModel.clearMessage()
        }
    }

    val statusTitle = when {
        meetingRecordingUiState.isRecording -> "회의 녹음 중"
        meetingRecordingUiState.isProcessing -> "회의 정리 중"
        geminiUiState.isGeminiActive -> "AI 대화 중"
        streamUiState.streamSessionState == StreamSessionState.STARTING -> "카메라 연결 중"
        else -> "Jarvis Ready"
    }
    val statusBody = when {
        streamUiState.errorMessage != null -> streamUiState.errorMessage!!
        meetingRecordingUiState.isRecording -> "대화를 저장하고 있습니다. 종료하거나 계속 진행할 수 있습니다."
        meetingRecordingUiState.isProcessing -> "녹음 내용을 텍스트와 회의록으로 정리하고 있습니다."
        geminiUiState.isGeminiActive -> "질문하면 현재 시야 기준으로 답변합니다."
        streamUiState.capturedPhoto != null -> "최근 시각 질문에서 캡처한 이미지입니다."
        else -> "자비스라고 부르거나 AI 버튼을 눌러 시작하세요."
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF4F7FB)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .statusBarsPadding()
                .padding(top = 12.dp, bottom = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPanel(
                    title = statusTitle,
                    body = statusBody,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { wearablesViewModel.showMemory() },
                ) {
                    Icon(
                        imageVector = Icons.Default.CollectionsBookmark,
                        contentDescription = "Records",
                        tint = Color(0xFF1B263B),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val capturedPhoto = streamUiState.capturedPhoto
            if (capturedPhoto != null) {
                PreviewPanel(
                    title = "최근 캡처",
                    bitmap = capturedPhoto,
                    modifier = Modifier.weight(1f),
                )
            } else {
                EmptyVisualPanel(
                    isLoading = streamUiState.streamSessionState == StreamSessionState.STARTING,
                    modeLabel = if (streamUiState.streamingMode == StreamingMode.PHONE) "Phone Camera" else "Glasses Camera",
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            ControlsRow(
                onStopStream = {
                    leaveStream()
                },
                onToggleAI = {
                    if (geminiUiState.isGeminiActive) {
                        geminiViewModel.stopSession()
                    } else {
                        geminiViewModel.startSession()
                    }
                },
                isAIActive = geminiUiState.isGeminiActive,
                aiEnabled = !meetingRecordingUiState.isRecording &&
                    !meetingRecordingUiState.isProcessing,
                onToggleRecording = {
                    if (meetingRecordingUiState.isRecording) {
                        meetingRecordingViewModel.stopAndProcess()
                    } else {
                        if (geminiUiState.isGeminiActive) {
                            geminiViewModel.stopSession()
                        }
                        meetingRecordingViewModel.startRecording()
                    }
                },
                isRecording = meetingRecordingUiState.isRecording,
                isRecordingProcessing = meetingRecordingUiState.isProcessing,
                onToggleLive = {
                    if (webrtcUiState.isActive) {
                        webrtcViewModel.stopSession()
                    } else {
                        webrtcViewModel.startSession()
                    }
                },
                isLiveActive = webrtcUiState.isActive,
                onShareLive = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Jarvis live view: ${webrtcUiState.viewerUrl}")
                    }
                    context.startActivity(Intent.createChooser(intent, "Share live view"))
                },
                canShareLive = webrtcUiState.viewerUrl.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            )
        }

        // Overlays + controls
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            // Top overlays (below status bar)
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 88.dp),
            ) {
                // Gemini overlay
                if (geminiUiState.isGeminiActive) {
                    GeminiOverlay(uiState = geminiUiState)
                }

                // WebRTC overlay
                if (webrtcUiState.isActive) {
                    Spacer(modifier = Modifier.height(4.dp))
                    WebRTCOverlay(uiState = webrtcUiState)
                }
            }
        }
    }

    // Share photo dialog
    streamUiState.capturedPhoto?.let { photo ->
        if (streamUiState.isShareDialogVisible) {
            SharePhotoDialog(
                photo = photo,
                onDismiss = { streamViewModel.hideShareDialog() },
                onShare = { bitmap ->
                    streamViewModel.sharePhoto(bitmap)
                    streamViewModel.hideShareDialog()
                },
            )
        }
    }
}

@Composable
private fun StatusPanel(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text = title,
                color = Color(0xFF10233F),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = body,
                color = Color(0xFF52627A),
            )
        }
    }
}

@Composable
private fun EmptyVisualPanel(
    isLoading: Boolean,
    modeLabel: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFFEAF1FB),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            if (isLoading) {
                CircularProgressIndicator(color = AppColor.DeepBlue)
                Spacer(modifier = Modifier.height(16.dp))
            }
            Text(
                text = "실시간 영상을 계속 표시하지 않고, 시각 질문 시 최신 캡처만 보여줍니다.",
                color = Color(0xFF10233F),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFFD4DEEC))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = modeLabel,
                color = Color(0xFF6B7A90),
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PreviewPanel(
    title: String,
    bitmap: android.graphics.Bitmap,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text(
                text = title,
                color = Color(0xFF10233F),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Latest captured visual",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFEAF1FB))
                    .aspectRatio(3f / 4f),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
