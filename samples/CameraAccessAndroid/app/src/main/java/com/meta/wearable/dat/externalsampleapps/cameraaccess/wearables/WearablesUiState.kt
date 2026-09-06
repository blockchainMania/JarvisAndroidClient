/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// WearablesUiState - DAT API State Management
//
// This data class aggregates DAT API state for the UI layer

package com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables

import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class WearablesUiState(
    val registrationState: RegistrationState = RegistrationState.UNAVAILABLE,
    val devices: ImmutableList<DeviceIdentifier> = persistentListOf(),
    val recentError: String? = null,
    val isStreaming: Boolean = false,
    val hasMockDevices: Boolean = false,
    val isDebugMenuVisible: Boolean = false,
    val isGettingStartedSheetVisible: Boolean = false,
    val hasActiveDevice: Boolean = false,
    val isPhoneMode: Boolean = false,
    val isSettingsVisible: Boolean = false,
    val isMemoryVisible: Boolean = false,
    // One line per connected device: model, whether it has a lens, link state. This is the
    // single fact that separates "these glasses have no display" from "the display is set up
    // wrong", and it is not knowable from the phone otherwise.
    val deviceDiagnostics: ImmutableList<String> = persistentListOf(),
    // Facts the SDK already knows and we were guessing at instead: whether Developer Mode is on
    // (our manifest uses the "0" placeholders, which are only valid in that mode) and whether
    // registration actually succeeded, including the errors it reports on its own stream.
    val isDevMode: Boolean? = null,
    val registrationError: String? = null,
) {
  val isRegistered: Boolean = registrationState == RegistrationState.REGISTERED || hasMockDevices
}
