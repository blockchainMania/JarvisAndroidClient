package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallStatus

/**
 * Gemini conversation panel. Lives in the normal layout flow (below the visual
 * preview), not as a floating overlay, so its content never overlaps the
 * preview panel above it -- it just takes its own scrollable space.
 */
@Composable
fun GeminiChatPanel(
    uiState: GeminiUiState,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(uiState.userTranscript, uiState.aiTranscript) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, AppColor.Border, RoundedCornerShape(20.dp)),
        color = AppColor.Surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
        ) {
            GeminiStatusBar(
                connectionState = uiState.connectionState,
                openClawState = uiState.openClawConnectionState,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (uiState.userTranscript.isNotEmpty()) {
                    ChatBubble(text = uiState.userTranscript, isUser = true)
                }
                if (uiState.aiTranscript.isNotEmpty()) {
                    ChatBubble(text = uiState.aiTranscript, isUser = false)
                }
            }

            val toolStatus = uiState.toolCallStatus
            if (toolStatus !is ToolCallStatus.Idle) {
                Spacer(modifier = Modifier.height(6.dp))
                ToolCallStatusView(status = toolStatus)
            }

            if (uiState.isModelSpeaking) {
                Spacer(modifier = Modifier.height(6.dp))
                SpeakingIndicator()
            }
        }
    }
}

@Composable
private fun ChatBubble(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .let { if (isUser) it else it.border(1.dp, AppColor.Border, RoundedCornerShape(16.dp)) },
            color = if (isUser) AppColor.UserBubble else AppColor.AiBubble,
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = text,
                color = if (isUser) AppColor.UserBubbleText else AppColor.AiBubbleText,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun GeminiStatusBar(
    connectionState: GeminiConnectionState,
    openClawState: OpenClawConnectionState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusPill(
            label = "AI",
            color = when (connectionState) {
                is GeminiConnectionState.Ready -> AppColor.Green
                is GeminiConnectionState.Connecting,
                is GeminiConnectionState.SettingUp -> Color(0xFFE7A400)
                is GeminiConnectionState.Error -> AppColor.Red
                is GeminiConnectionState.Disconnected -> AppColor.TextSecondary
            },
        )

        if (openClawState !is OpenClawConnectionState.NotConfigured) {
            StatusPill(
                label = "Jarvis",
                color = when (openClawState) {
                    is OpenClawConnectionState.Connected -> AppColor.Green
                    is OpenClawConnectionState.Checking -> Color(0xFFE7A400)
                    is OpenClawConnectionState.Unreachable -> AppColor.Red
                    is OpenClawConnectionState.NotConfigured -> AppColor.TextSecondary
                },
            )
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(AppColor.SurfaceMuted, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            color = AppColor.TextPrimary,
            fontSize = 12.sp,
        )
    }
}

@Composable
fun ToolCallStatusView(
    status: ToolCallStatus,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(AppColor.SurfaceMuted, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (status) {
            is ToolCallStatus.Executing -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = AppColor.DeepBlue,
                    strokeWidth = 2.dp,
                )
            }
            is ToolCallStatus.Completed -> {
                Text(text = "OK", color = AppColor.Green, fontSize = 12.sp)
            }
            is ToolCallStatus.Failed -> {
                Text(text = "X", color = AppColor.Red, fontSize = 12.sp)
            }
            is ToolCallStatus.Cancelled -> {
                Text(text = "--", color = Color(0xFFE7A400), fontSize = 12.sp)
            }
            else -> {}
        }
        Text(
            text = status.displayText,
            color = AppColor.TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

@Composable
fun SpeakingIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "speaking")
    Row(
        modifier = modifier
            .background(AppColor.SurfaceMuted, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(4) { index ->
            val height by infiniteTransition.animateFloat(
                initialValue = 4f,
                targetValue = 16f,
                animationSpec = infiniteRepeatable(
                    animation = tween(400, delayMillis = index * 100, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar$index",
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(AppColor.DeepBlue),
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "Speaking", color = AppColor.TextSecondary, fontSize = 11.sp)
    }
}
