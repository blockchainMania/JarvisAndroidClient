/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.GlassesDisplay
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.VisualMemoryFrameStore
import com.meta.wearable.dat.externalsampleapps.cameraaccess.phone.PhoneCameraManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc.WebRTCSessionViewModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "StreamViewModel"
    // Raised from 12s for the 0.9 session model: startup is now createSession -> start ->
    // STARTED -> addCamera -> stream.start() -> STREAMING, several device round trips where
    // 0.5.0 had one call. Timing out early doesn't just show a false error, it tears down a
    // session that was about to succeed.
    // Widening gaps: the link usually settles within a couple of seconds, but a cold start after
    // force-stopping the app has been seen to need noticeably longer.
    private val START_RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L)
    private const val MAX_START_RETRIES = 3

    // Covers both waits: the SDK discovering the glasses after a cold start, and the link then
    // reaching CONNECTED. Discovery alone takes a few seconds on a freshly launched app.
    private const val LINK_READY_TIMEOUT_MS = 10_000L

    private const val GLASSES_START_TIMEOUT_MS = 20_000L
    private const val GLASSES_FRAME_INTERVAL_MS = 100L
    private const val GLASSES_FRAME_LOG_INTERVAL_MS = 5_000L
    private const val GLASSES_STABLE_RESET_MS = 30_000L
    private val INITIAL_STATE = StreamUiState()
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var stream: Stream? = null

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  private var videoJob: Job? = null
  private var stateJob: Job? = null
  private var sessionStateJob: Job? = null
  private var sessionErrorJob: Job? = null
  private var streamErrorJob: Job? = null
  private var startTimeoutJob: Job? = null
  private var isStreamingServiceRunning = false
    private var hasReachedGlassesStreaming = false
    private var lastGlassesFrameAt = 0L
    private var lastGlassesFrameLogAt = 0L
    private var userRequestedStop = false
  private var autoRestartJob: Job? = null
  private var autoRestartAttempts = 0

  // The first error of a start attempt is the one that explains it; everything after is fallout.
  // Observed on device: the glasses ended the session (SESSION_ENDED_BY_DEVICE), the retry then
  // found nothing (NO_ELIGIBLE_DEVICE), and the second message overwrote the first -- so the
  // screen sent the user looking for glasses that had just actively refused them.
  private var startAttemptHasError = false

  // Retries of a start that never produced video. Separate from autoRestartAttempts, which
  // covers a stream that was working and dropped -- the two failures need different handling.
  private var startRetryAttempts = 0
  private var startRetryJob: Job? = null

  /**
   * Reports a failed start, retrying quietly first.
   *
   * Bringing the glasses link up is racy: pressing start too soon after the app launches gets the
   * session accepted and then immediately ended by the device, and simply trying again works.
   * On device this took two to five presses -- the user was hand-cranking a retry loop the app
   * should be doing itself, and every one of those presses looked like a failure.
   *
   * So a start that never reached STREAMING is retried on a widening delay before anything is
   * shown. Only once the retries are spent does the error surface, and by then it is real.
   */
  private fun reportStartError(message: String) {
    if (startAttemptHasError) return
    startAttemptHasError = true

    if (!userRequestedStop &&
        !hasReachedGlassesStreaming &&
        startRetryAttempts < MAX_START_RETRIES &&
        _uiState.value.streamingMode == StreamingMode.GLASSES
    ) {
      startRetryAttempts += 1
      val delayMs = START_RETRY_DELAYS_MS[startRetryAttempts - 1]
      Log.w(TAG, "Start failed, retrying in ${delayMs}ms (attempt $startRetryAttempts): $message")
      startRetryJob?.cancel()
      startRetryJob =
          viewModelScope.launch {
            delay(delayMs)
            if (!userRequestedStop && _uiState.value.streamingMode == StreamingMode.GLASSES) {
              startStream()
            }
          }
      return
    }

    Log.e(TAG, "Start failed after $startRetryAttempts retries: $message")
    _uiState.update { it.copy(errorMessage = message) }
  }
  private var stableStreamJob: Job? = null

  // VisionClaw additions
  var geminiViewModel: GeminiSessionViewModel? = null
  var webrtcViewModel: WebRTCSessionViewModel? = null
  private var phoneCameraManager: PhoneCameraManager? = null

  /** Entry point for the user pressing start -- clears the retry budget the automatic path uses. */
  fun startStreamFromUser() {
    startRetryAttempts = 0
    startStream()
  }

  fun startStream() {
    userRequestedStop = false
    startAttemptHasError = false
    startTimeoutJob?.cancel()
    stopActiveStream()
    hasReachedGlassesStreaming = false
    _uiState.update {
      it.copy(
        streamingMode = StreamingMode.GLASSES,
        streamSessionState = StreamState.STARTING,
        errorMessage = null,
      )
    }

    VisualMemoryFrameStore.freshStillProvider = ::captureFreshVisualFrame
    viewModelScope.launch { awaitConnectedLink(); createGlassesSession() }
  }

  /**
   * Waits for the glasses link to actually be up before asking for a camera session.
   *
   * Being paired is not the same as being ready: the selector will hand back a device while its
   * link is still CONNECTING, and a session requested in that window is accepted and then ended
   * by the glasses (SESSION_ENDED_BY_DEVICE). On device this needed two to ten presses of start
   * before one happened to land after the link settled.
   *
   * Gives up waiting rather than blocking forever -- if the link never settles, letting the
   * session attempt proceed produces a real error to show instead of a spinner that never ends.
   */
  private suspend fun awaitConnectedLink() {
    val ready =
        withTimeoutOrNull(LINK_READY_TIMEOUT_MS) {
          // Two waits, and the first one was missing. Right after the app starts the SDK has not
          // discovered anything yet, so activeDevice() is null -- and returning on that treated
          // "nothing found yet" as "nothing to wait for", skipping straight to creating a session.
          // That is precisely the case this exists to cover, and why restarting the app still
          // failed intermittently after the developer-mode fix.
          val deviceId = deviceSelector.activeDeviceFlow().filterNotNull().first()
          Log.d(TAG, "Device available: $deviceId")

          val metadata = Wearables.devicesMetadata[deviceId]
          if (metadata == null) {
            Log.w(TAG, "No metadata for $deviceId; proceeding without a link check")
            return@withTimeoutOrNull true
          }
          if (metadata.value.linkState != LinkState.CONNECTED) {
            Log.d(TAG, "Link is ${metadata.value.linkState}; waiting for CONNECTED")
            metadata.first { it.linkState == LinkState.CONNECTED }
          }
          Log.d(TAG, "Link is CONNECTED")
          true
        }
    if (ready == null) {
      // Deliberately not fatal: let the attempt run so a real error is reported, rather than
      // sitting here silently on a spinner.
      Log.w(TAG, "Gave up waiting for a connected device after ${LINK_READY_TIMEOUT_MS}ms")
    }
  }

  private fun createGlassesSession() {
    Log.d(TAG, "Creating glasses device session")
    // Since SDK 0.7 the one-shot startStreamSession() factory is gone: a DeviceSession is created
    // and started first, and the camera can only be attached once that session reports STARTED.
    // So what used to be a single synchronous call is now a small state machine -- attachCamera()
    // below is what actually begins streaming.
    Wearables.createSession(deviceSelector)
        .onSuccess { created ->
          session = created
          sessionStateJob =
              viewModelScope.launch {
                created.state.collect { sessionState ->
                  Log.d(TAG, "Glasses device session state: $sessionState")
                  if (sessionState == DeviceSessionState.STARTED && camera == null) {
                    attachCamera(created)
                    // Additive: no-ops on frames without a lens, so the spoken flow is unchanged.
                    GlassesDisplay.attach(created, viewModelScope)
                  }
                }
              }
          sessionErrorJob =
              viewModelScope.launch {
                created.errors.collect { error ->
                  Log.e(TAG, "Glasses device session error: $error (${error.description})")
                  reportStartError(error.guidance())
                }
              }
          created.start()
        }
        .onFailure { error, _ ->
          Log.e(TAG, "Failed to create glasses device session: $error (${error.description})")
          _uiState.update { it.copy(streamSessionState = StreamState.STOPPED) }
          reportStartError(error.guidance())
        }
    _uiState.update { it.copy(streamingMode = StreamingMode.GLASSES) }
    startTimeoutJob =
        viewModelScope.launch {
          delay(GLASSES_START_TIMEOUT_MS)
          if (
              _uiState.value.streamingMode == StreamingMode.GLASSES &&
                  _uiState.value.streamSessionState != StreamState.STREAMING
          ) {
            Log.w(TAG, "Glasses stream did not reach STREAMING before timeout")
            stopActiveStream()
            _uiState.update {
              it.copy(
                  streamingMode = StreamingMode.GLASSES,
                  streamSessionState = StreamState.STOPPED,
              )
            }
            startAttemptHasError = false
            reportStartError(
                "글래스 영상이 시작되지 않았어요. 케이스에서 꺼내 착용하고, " +
                    "Meta AI 앱에서 연결됨으로 보이는지 확인한 뒤 다시 시작해 주세요.",
            )
          }
        }
  }

  /** Attaches the camera and starts its stream. Only valid once the session reports STARTED. */
  private fun attachCamera(deviceSession: DeviceSession) {
    Log.d(TAG, "Attaching glasses camera with HIGH/30fps")
    deviceSession
        .addCamera(
            // compressVideo is stated explicitly rather than left to the default: handleVideoFrame
            // below decodes raw I420, so if the SDK ever defaults this to true the frames would
            // arrive encoded and decode into garbage. Pinning it keeps 0.5.0's behaviour.
            StreamConfiguration(
                videoQuality = VideoQuality.HIGH,
                frameRate = 30,
                compressVideo = false,
            )
        )
        .onSuccess { addedCamera ->
          camera = addedCamera
          val addedStream = addedCamera.stream
          stream = addedStream

          // Subscribe before start() so the initial transitions aren't missed.
          videoJob =
              viewModelScope.launch {
                addedStream.videoStream.conflate().collectLatest { handleVideoFrame(it) }
              }
          stateJob = viewModelScope.launch { observeStreamState(addedStream) }
          streamErrorJob =
              viewModelScope.launch {
                addedStream.errorStream.collect { error ->
                  Log.e(TAG, "Glasses stream error: $error")
                }
              }

          // Starting the stream is explicit since SDK 0.7 -- addCamera alone delivers no frames.
          addedStream.start().onFailure { error, _ ->
            Log.e(TAG, "Failed to start glasses stream: ${error.description}")
            reportStartError(error.description)
          }
        }
        .onFailure { error, _ ->
          Log.e(TAG, "Failed to attach glasses camera: ${error.description}")
          _uiState.update { it.copy(streamSessionState = StreamState.STOPPED) }
          reportStartError(error.guidance())
        }
  }

  private suspend fun observeStreamState(activeStream: Stream) {
    activeStream.state.collect { currentState ->
      val prevState = _uiState.value.streamSessionState
      Log.d(TAG, "Glasses stream state: $prevState -> $currentState")
      _uiState.update { it.copy(streamSessionState = currentState) }

      if (currentState == StreamState.STREAMING && !isStreamingServiceRunning) {
        hasReachedGlassesStreaming = true
        // Video is flowing, so the link is up: give the retry budget back for any later attempt.
        startRetryAttempts = 0
        startTimeoutJob?.cancel()
        startTimeoutJob = null
        scheduleStableStreamReset()
        StreamingService.start(getApplication())
        isStreamingServiceRunning = true
      }

      if (
          currentState != prevState &&
              currentState == StreamState.STOPPED &&
              hasReachedGlassesStreaming &&
              !userRequestedStop
      ) {
        _uiState.update {
          it.copy(
            errorMessage =
                "Glasses video stream stopped. Check Bluetooth, Meta AI app connection, and camera permission.",
          )
        }
        if (isStreamingServiceRunning) {
          StreamingService.stop(getApplication())
            isStreamingServiceRunning = false
        }
        scheduleGlassesStreamRestart()
      }
    }
  }

  fun startPhoneCamera(lifecycleOwner: LifecycleOwner) {
    val manager = PhoneCameraManager(getApplication())
    phoneCameraManager = manager
    VisualMemoryFrameStore.freshStillProvider = ::captureFreshVisualFrame

    manager.onFrameCaptured = { bitmap ->
      _uiState.update { it.copy(videoFrame = bitmap, errorMessage = null) }
      // Forward to Gemini (throttled inside the VM)
      geminiViewModel?.sendVideoFrameIfThrottled(bitmap)
      // Forward to WebRTC (every frame)
      webrtcViewModel?.pushVideoFrame(bitmap)
    }
    manager.onError = { message ->
      _uiState.update { it.copy(errorMessage = message) }
      Log.e(TAG, message)
    }

    _uiState.update {
      it.copy(
        streamingMode = StreamingMode.PHONE,
        streamSessionState = StreamState.STREAMING,
        errorMessage = null,
      )
    }
    manager.start(lifecycleOwner)
    Log.d(TAG, "Phone camera mode started")
  }

  fun stopStream() {
    userRequestedStop = true
    autoRestartJob?.cancel()
    autoRestartJob = null
    stableStreamJob?.cancel()
    stableStreamJob = null
    startRetryJob?.cancel()
    startRetryJob = null
    stopActiveStream()
    _uiState.update { INITIAL_STATE }
  }

  private fun stopActiveStream() {
    startTimeoutJob?.cancel()
    startTimeoutJob = null

    if (isStreamingServiceRunning) {
      StreamingService.stop(getApplication())
      isStreamingServiceRunning = false
    }

    videoJob?.cancel()
    videoJob = null
    stateJob?.cancel()
    stateJob = null
    sessionStateJob?.cancel()
    sessionStateJob = null
    sessionErrorJob?.cancel()
    sessionErrorJob = null
    streamErrorJob?.cancel()
    streamErrorJob = null
    stableStreamJob?.cancel()
    stableStreamJob = null
    startRetryJob?.cancel()
    startRetryJob = null
    // Stopping the camera detaches the capability and cascades to its stream child. Without it
    // the next addCamera() is rejected because a camera capability is still active on the session.
    camera?.stop()
    camera = null
    stream = null
    GlassesDisplay.detach(session)
    session?.stop()
    session = null
    phoneCameraManager?.stop()
    phoneCameraManager = null
    VisualMemoryFrameStore.freshStillProvider = null
    lastGlassesFrameAt = 0L
    lastGlassesFrameLogAt = 0L
  }

  private fun scheduleGlassesStreamRestart() {
    if (autoRestartJob?.isActive == true) return
    if (autoRestartAttempts >= 2) {
      _uiState.update {
        it.copy(
            errorMessage =
                "Glasses video stream stopped repeatedly. Reconnect Bluetooth/Meta AI app, then start streaming again.",
        )
      }
      return
    }
    autoRestartAttempts += 1
    autoRestartJob =
        viewModelScope.launch {
          Log.w(TAG, "Auto-restarting glasses stream attempt $autoRestartAttempts")
          delay(1_500L)
          if (!userRequestedStop && _uiState.value.streamingMode == StreamingMode.GLASSES) {
            startStream()
          }
        }
  }

  private fun scheduleStableStreamReset() {
    stableStreamJob?.cancel()
    stableStreamJob =
        viewModelScope.launch {
          delay(GLASSES_STABLE_RESET_MS)
          if (
              !userRequestedStop &&
                  _uiState.value.streamingMode == StreamingMode.GLASSES &&
                  _uiState.value.streamSessionState == StreamState.STREAMING
          ) {
            autoRestartAttempts = 0
            Log.d(TAG, "Glasses stream stable for ${GLASSES_STABLE_RESET_MS}ms; restart counter reset")
          }
        }
  }

  fun capturePhoto() {
    if (uiState.value.isCapturing) {
      Log.d(TAG, "Photo capture already in progress, ignoring request")
      return
    }

    if (uiState.value.streamSessionState == StreamState.STREAMING) {
      // Phone mode: capture current video frame as photo
      if (uiState.value.streamingMode == StreamingMode.PHONE) {
        uiState.value.videoFrame?.let { frame ->
          _uiState.update { it.copy(capturedPhoto = frame, isShareDialogVisible = true) }
        }
        return
      }

      Log.d(TAG, "Starting photo capture")
      _uiState.update { it.copy(isCapturing = true) }

      viewModelScope.launch {
        stream
            ?.capturePhoto()
            ?.onSuccess { photoData ->
              Log.d(TAG, "Photo capture successful")
              handlePhotoData(photoData)
              _uiState.update { it.copy(isCapturing = false) }
            }
            ?.onFailure {
              Log.e(TAG, "Photo capture failed")
              _uiState.update { it.copy(isCapturing = false) }
            }
      }
    } else {
      Log.w(
          TAG,
          "Cannot capture photo: stream not active (state=${uiState.value.streamSessionState})",
      )
    }
  }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.putExtra(Intent.EXTRA_STREAM, uri)
      intent.type = "image/png"
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      val chooser = Intent.createChooser(intent, "Share Image")
      chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e("StreamViewModel", "Failed to share photo", e)
    }
  }

  private suspend fun handleVideoFrame(videoFrame: VideoFrame) {
    val now = SystemClock.elapsedRealtime()
    if (now - lastGlassesFrameAt < GLASSES_FRAME_INTERVAL_MS) {
      return
    }
    lastGlassesFrameAt = now

    if (now - lastGlassesFrameLogAt >= GLASSES_FRAME_LOG_INTERVAL_MS) {
      Log.d(TAG, "Glasses frame ${videoFrame.width}x${videoFrame.height}")
      lastGlassesFrameLogAt = now
    }

    // VideoFrame contains raw I420 video data in a ByteBuffer
    val buffer = videoFrame.buffer
    val dataSize = buffer.remaining()
    val byteArray = ByteArray(dataSize)

    // Save current position
    val originalPosition = buffer.position()
    buffer.get(byteArray)
    // Restore position
    buffer.position(originalPosition)

    val bitmap = withContext(Dispatchers.Default) {
      decodeI420FrameToBitmap(byteArray, videoFrame.width, videoFrame.height)
    } ?: return
    _uiState.update { it.copy(videoFrame = bitmap) }

    // Forward to Gemini (throttled inside the VM)
    geminiViewModel?.sendVideoFrameIfThrottled(bitmap)
    // Forward to WebRTC only after the glasses frame rate is reduced above.
    webrtcViewModel?.pushVideoFrame(bitmap)
  }

  private suspend fun captureFreshVisualFrame(): VisualMemoryFrameStore.VisualFrame? {
    return when (_uiState.value.streamingMode) {
      StreamingMode.PHONE -> {
        _uiState.value.videoFrame?.let {
          _uiState.update { state -> state.copy(capturedPhoto = it) }
          VisualMemoryFrameStore.bitmapToVisualFrame(it, "phone_camera_still")
        }
      }
      StreamingMode.GLASSES -> {
        try {
          // DAT video delivery trails the real scene. Capture only after the spoken request has
          // completed and allow the glasses camera pipeline to catch up before taking the still.
          delay(2000L)
          val photoData = stream?.capturePhoto()?.getOrNull() ?: return null
          val bitmap = withContext(Dispatchers.Default) { photoDataToBitmap(photoData) }
          _uiState.update { state -> state.copy(capturedPhoto = bitmap) }
          VisualMemoryFrameStore.bitmapToVisualFrame(bitmap, "glasses_capture_photo")
        } catch (e: Exception) {
          Log.w(TAG, "Fresh glasses photo capture failed, falling back to latest frame: ${e.message}")
          null
        }
      }
    }
  }

  private fun decodeI420FrameToBitmap(byteArray: ByteArray, width: Int, height: Int): Bitmap? {
    return try {
      // Convert I420 to NV21 format which is supported by Android's YuvImage.
      val nv21 = convertI420toNV21(byteArray, width, height)
      val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)
      val out =
          ByteArrayOutputStream().use { stream ->
            image.compressToJpeg(Rect(0, 0, width, height), 45, stream)
            stream.toByteArray()
          }
      BitmapFactory.decodeByteArray(out, 0, out.size)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to decode glasses video frame: ${e.message}", e)
      null
    }
  }

  // Convert I420 (YYYYYYYY:UUVV) to NV21 (YYYYYYYY:VUVU)
  private fun convertI420toNV21(input: ByteArray, width: Int, height: Int): ByteArray {
    val output = ByteArray(input.size)
    val size = width * height
    val quarter = size / 4

    input.copyInto(output, 0, 0, size) // Y is the same

    for (n in 0 until quarter) {
      output[size + n * 2] = input[size + quarter + n] // V first
      output[size + n * 2 + 1] = input[size + n] // U second
    }
    return output
  }

  private fun handlePhotoData(photo: PhotoData) {
    val capturedPhoto = photoDataToBitmap(photo)
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
  }

  private fun photoDataToBitmap(photo: PhotoData): Bitmap =
      when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> {
          val byteArray = ByteArray(photo.data.remaining())
          photo.data.get(byteArray)

          // Extract EXIF transformation matrix and apply to bitmap.
          val exifInfo = getExifInfo(byteArray)
          val transform = getTransform(exifInfo)
          decodeHeic(byteArray, transform)
        }
      }

  // HEIC Decoding with EXIF transformation
  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
    return applyTransform(bitmap, transform)
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()

    if (exifInfo == null) {
      return matrix // Identity matrix (no transformation)
    }

    when (
        exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_180 -> {
        matrix.postRotate(180f)
      }
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.postScale(1f, -1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> {
        matrix.postRotate(90f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> {
        matrix.postRotate(270f)
      }
      ExifInterface.ORIENTATION_NORMAL,
      ExifInterface.ORIENTATION_UNDEFINED -> {
        // No transformation needed
      }
    }

    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) {
      return bitmap
    }

    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  override fun onCleared() {
    super.onCleared()
    stopStream()
    stateJob?.cancel()
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
            application = application,
            wearablesViewModel = wearablesViewModel,
        )
            as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
