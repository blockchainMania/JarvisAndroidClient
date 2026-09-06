/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// WearablesViewModel - Core DAT SDK Integration
//
// This ViewModel demonstrates the core DAT API patterns for:
// - Device registration and unregistration using the DAT SDK
// - Permission management for wearable devices
// - Device discovery and state management
// - Integration with MockDeviceKit for testing

package com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WearablesViewModel(application: Application) : AndroidViewModel(application) {
  companion object {
    private const val TAG = "WearablesViewModel"
  }

  private val _uiState = MutableStateFlow(WearablesUiState())
  val uiState: StateFlow<WearablesUiState> = _uiState.asStateFlow()

  // AutoDeviceSelector automatically selects the first available wearable device
  val deviceSelector: DeviceSelector = AutoDeviceSelector()
  private var deviceSelectorJob: Job? = null

  private var monitoringStarted = false
  private val deviceMonitoringJobs = mutableMapOf<DeviceIdentifier, Job>()

  fun startMonitoring() {
    if (monitoringStarted) {
      return
    }
    monitoringStarted = true

    // Monitor device selector for active device
    deviceSelectorJob =
        viewModelScope.launch {
          deviceSelector.activeDeviceFlow().collect { device ->
            _uiState.update { it.copy(hasActiveDevice = device != null) }
          }
        }

    // Developer Mode decides whether attestation runs. The manifest carries APPLICATION_ID and
    // CLIENT_TOKEN of "0", which are only valid when it is on -- so with it off the glasses
    // accept a session and then end it, which is exactly the failure being chased.
    _uiState.update { it.copy(isDevMode = Wearables.isDevMode) }

    // Registration reports failures on its own stream, which nothing was reading. A registration
    // that degraded after the app looked registered is invisible without it.
    viewModelScope.launch {
      Wearables.registrationErrorStream.collect { error ->
        Log.e(TAG, "Registration error: $error")
        _uiState.update { it.copy(registrationError = error.toString()) }
      }
    }

    // This allows the app to react to registration changes (registered, unregistered, etc.)
    viewModelScope.launch {
      Wearables.registrationState.collect { value ->
        val previousState = _uiState.value.registrationState
        val showGettingStartedSheet =
            value == RegistrationState.REGISTERED && previousState == RegistrationState.REGISTERING
        _uiState.update {
          it.copy(registrationState = value, isGettingStartedSheetVisible = showGettingStartedSheet)
        }
      }
    }
    // This automatically updates when devices are discovered, connected, or disconnected
    viewModelScope.launch {
      Wearables.devices.collect { value ->
        val hasMockDevices = MockDeviceKit.getInstance(getApplication()).pairedDevices.isNotEmpty()
        _uiState.update {
          it.copy(devices = value.toList().toImmutableList(), hasMockDevices = hasMockDevices)
        }
        // Monitor device metadata for compatibility issues
        monitorDeviceCompatibility(value)
      }
    }
  }

  private fun monitorDeviceCompatibility(devices: Set<DeviceIdentifier>) {
    // Cancel monitoring jobs for devices that are no longer in the list
    val removedDevices = deviceMonitoringJobs.keys - devices
    removedDevices.forEach { deviceId ->
      deviceMonitoringJobs[deviceId]?.cancel()
      deviceMonitoringJobs.remove(deviceId)
    }

    // Start monitoring jobs only for new devices (not already being monitored)
    val newDevices = devices - deviceMonitoringJobs.keys
    newDevices.forEach { deviceId ->
      val job =
          viewModelScope.launch {
            Wearables.devicesMetadata[deviceId]?.collect { metadata ->
              val needsUpdate =
                  metadata.compatibility ==
                      com.meta.wearable.dat.core.types.DeviceCompatibility.DEVICE_UPDATE_REQUIRED
              if (needsUpdate) {
                val deviceName = metadata.name.ifEmpty { deviceId }
                setRecentError("Device '$deviceName' requires an update to work with this app")
              }
              // The model is spelled out rather than reduced to a yes/no: META_RAYBAN_DISPLAY vs
              // RAYBAN_META is the one fact that settles whether a missing lens is a setup
              // problem at all, and seeing the raw type removes any doubt about detection.
              val label = metadata.name.ifEmpty { deviceId.toString() }
              val line = buildString {
                append(label)
                append(" · ")
                append(metadata.deviceType)
                append(" · ")
                append(
                    if (metadata.deviceType.isDisplayCapable) "디스플레이 지원 기기"
                    else "디스플레이 없는 기기"
                )
                append(" · ")
                append(metadata.linkState)
                if (needsUpdate) append(" · 펌웨어 업데이트 필요")
              }
              _uiState.update { current ->
                val others = current.deviceDiagnostics.filterNot { it.startsWith(label) }
                current.copy(
                    deviceDiagnostics = (others + line).toImmutableList()
                )
              }
            }
          }
      deviceMonitoringJobs[deviceId] = job
    }
  }

  /**
   * Opens the Meta AI app's flow for reinstalling the DAT app that runs on the glasses.
   *
   * The glasses-side app is installed and versioned separately from this one, and when it is
   * missing or stale the glasses accept a camera session and then immediately end it -- which
   * looks identical to a connection problem from here. The SDK exposes a direct entry point for
   * it, so the remedy is one tap rather than a walk through the Meta AI app's developer menu.
   */
  fun openGlassesAppUpdate(activity: Activity) {
    Wearables.openDATGlassesAppUpdate(activity).onFailure { error, _ ->
      setRecentError("글래스 앱 업데이트 화면을 열지 못했어요: ${error.description}")
    }
  }

  fun openFirmwareUpdate(activity: Activity) {
    Wearables.openFirmwareUpdate(activity).onFailure { error, _ ->
      setRecentError("펌웨어 업데이트 화면을 열지 못했어요: ${error.description}")
    }
  }

  fun startRegistration(activity: Activity) {
    Wearables.startRegistration(activity)
  }

  fun startUnregistration(activity: Activity) {
    Wearables.startUnregistration(activity)
  }

  fun navigateToStreaming(onRequestWearablesPermission: suspend (Permission) -> PermissionStatus) {
    viewModelScope.launch {
      val permission = Permission.CAMERA // Camera permission is required for streaming
      val result = Wearables.checkPermissionStatus(permission)

      // Handle the result
      result.onFailure { error, _ ->
        setRecentError("Permission check error: ${error.description}")
        return@launch
      }

      val permissionStatus = result.getOrNull()
      if (permissionStatus == PermissionStatus.Granted) {
        _uiState.update { it.copy(isStreaming = true) }
        return@launch
      }

      // Request permission
      val requestedPermissionStatus = onRequestWearablesPermission(permission)
      when (requestedPermissionStatus) {
        PermissionStatus.Denied -> {
          setRecentError("Permission denied")
        }
        PermissionStatus.Granted -> {
          _uiState.update { it.copy(isStreaming = true) }
        }
      }
    }
  }

  fun navigateToPhoneMode() {
    _uiState.update { it.copy(isStreaming = true, isPhoneMode = true) }
  }

  fun navigateToDeviceSelection() {
    _uiState.update { it.copy(isStreaming = false, isPhoneMode = false) }
  }

  fun showSettings() {
    _uiState.update { it.copy(isSettingsVisible = true) }
  }

  fun hideSettings() {
    _uiState.update { it.copy(isSettingsVisible = false) }
  }

  fun showMemory() {
    _uiState.update { it.copy(isMemoryVisible = true) }
  }

  fun hideMemory() {
    _uiState.update { it.copy(isMemoryVisible = false) }
  }

  fun showDebugMenu() {
    _uiState.update { it.copy(isDebugMenuVisible = true) }
  }

  fun hideDebugMenu() {
    _uiState.update { it.copy(isDebugMenuVisible = false) }
  }

  fun clearCameraPermissionError() {
    _uiState.update { it.copy(recentError = null) }
  }

  fun setRecentError(error: String) {
    _uiState.update { it.copy(recentError = error) }
  }

  fun showGettingStartedSheet() {
    _uiState.update { it.copy(isGettingStartedSheetVisible = true) }
  }

  fun hideGettingStartedSheet() {
    _uiState.update { it.copy(isGettingStartedSheetVisible = false) }
  }

  override fun onCleared() {
    super.onCleared()
    // Cancel all device monitoring jobs when ViewModel is cleared
    deviceMonitoringJobs.values.forEach { it.cancel() }
    deviceMonitoringJobs.clear()
    deviceSelectorJob?.cancel()
  }
}
