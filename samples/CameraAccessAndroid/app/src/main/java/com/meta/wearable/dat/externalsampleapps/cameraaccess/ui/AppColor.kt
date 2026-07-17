/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.ui.graphics.Color

object AppColor {
  val Green = Color(0xFF61BC63)
  val Red = Color(0xFFFF3B30)
  val Yellow = Color(0xFFFFCC00)
  val DeepBlue = Color(0xFF0064E0)
  val DestructiveBackground = Color(0xFFFFD8DB)
  val DestructiveForeground = Color(0xFFAA071E)

  // Minimal/monotone UI tokens. DeepBlue stays the single accent color;
  // everything else is neutral grayscale so accent/status colors keep meaning.
  val Background = Color(0xFFF7F8FA)
  val Surface = Color.White
  val Border = Color(0xFFE5E7EB)
  val SurfaceMuted = Color(0xFFF1F2F4)
  val TextPrimary = Color(0xFF111827)
  val TextSecondary = Color(0xFF6B7280)

  // Chat bubble tokens (Gemini conversation area)
  val UserBubble = DeepBlue
  val UserBubbleText = Color.White
  val AiBubble = Color.White
  val AiBubbleText = TextPrimary
}
