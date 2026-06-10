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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiSessionViewModel
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

    // Clean up on exit
    DisposableEffect(Unit) {
        onDispose {
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

    Box(modifier = modifier.fillMaxSize()) {
        // Video feed
        streamUiState.videoFrame?.let { videoFrame ->
            Image(
                bitmap = videoFrame.asImageBitmap(),
                contentDescription = stringResource(R.string.live_stream),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        if (streamUiState.videoFrame == null) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (
                    streamUiState.streamSessionState == StreamSessionState.STARTING ||
                    streamUiState.streamingMode == StreamingMode.PHONE
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                }
                Text(
                    text = streamUiState.errorMessage
                        ?: if (streamUiState.streamingMode == StreamingMode.PHONE) {
                            "Starting phone camera..."
                        } else {
                            "Starting glasses camera..."
                        },
                    color = androidx.compose.ui.graphics.Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Overlays + controls
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            // Top overlays (below status bar)
            Column(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 8.dp)) {
                // Gemini overlay
                if (geminiUiState.isGeminiActive) {
                    GeminiOverlay(uiState = geminiUiState)
                }

                // WebRTC overlay
                if (webrtcUiState.isActive) {
                    Spacer(modifier = Modifier.height(4.dp))
                    WebRTCOverlay(uiState = webrtcUiState)
                }
                if (meetingRecordingUiState.isRecording || meetingRecordingUiState.isProcessing) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (meetingRecordingUiState.isRecording) {
                            "Meeting recording..."
                        } else {
                            "Transcribing and summarizing..."
                        },
                        color = androidx.compose.ui.graphics.Color.White,
                    )
                }
            }

            IconButton(
                onClick = { wearablesViewModel.showMemory() },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "Memory",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }

            // Controls at bottom
            ControlsRow(
                onStopStream = {
                    leaveStream()
                },
                onCapturePhoto = { streamViewModel.capturePhoto() },
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
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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
