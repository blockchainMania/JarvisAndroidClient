package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import com.meta.wearable.dat.core.types.DeviceSessionError

/**
 * Turns a DeviceSessionError into something the wearer can act on.
 *
 * The SDK's own text is a bare English label ("No eligible device available") that names the
 * internal condition rather than the fix, and it is shown to someone wearing glasses who has no
 * way to know what "eligible" is testing. Reading the SDK, AutoDeviceSelector's eligibility filter
 * checks exactly two things -- Device.compatibility and Device.linkState -- so NO_ELIGIBLE_DEVICE
 * has only two possible causes, and both have a concrete remedy worth stating.
 */
fun DeviceSessionError.guidance(): String = when (this) {
    DeviceSessionError.NO_ELIGIBLE_DEVICE ->
        "글래스를 찾을 수 없어요. 케이스에서 꺼내 착용하고, Meta AI 앱에서 연결됨으로 " +
            "보이는지 확인해 주세요. 그래도 안 되면 Meta AI 앱에서 연결을 껐다 켜보세요."

    DeviceSessionError.DEVICE_DISCONNECTED ->
        "글래스 연결이 끊어졌어요. Meta AI 앱에서 다시 연결한 뒤 시작해 주세요."

    DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED ->
        "글래스에 설치된 개발자 앱이 오래됐어요. Meta AI 앱의 개발자 모드에서 다시 설치해 주세요."

    DeviceSessionError.CAPABILITY_DENIED ->
        "카메라 권한이 거부됐어요. Meta AI 앱에서 이 앱의 카메라 접근을 허용해 주세요."

    DeviceSessionError.SESSION_ALREADY_EXISTS ->
        "이미 다른 세션이 열려 있어요. 잠시 후 다시 시도해 주세요."

    // The glasses accepted the session and then killed it, which is a different situation from
    // never finding them: they were reachable and refused. In practice that is the glasses not
    // being worn (camera access is gated on wear detection), or something else already holding
    // the camera -- usually the Meta AI app itself.
    DeviceSessionError.SESSION_ENDED_BY_DEVICE ->
        "글래스가 연결을 받자마자 끊었어요. 글래스를 실제로 착용한 상태인지 확인하고, " +
            "Meta AI 앱이 카메라를 쓰고 있지 않은지(앱 완전 종료) 확인한 뒤 다시 시작해 주세요."

    // Thermal and power states resolve themselves; saying so stops the wearer retrying in a loop.
    DeviceSessionError.THERMAL_CRITICAL,
    DeviceSessionError.THERMAL_EMERGENCY ->
        "글래스가 뜨거워져서 잠시 멈췄어요. 식은 뒤에 다시 시작해 주세요."

    DeviceSessionError.BATTERY_CRITICAL,
    DeviceSessionError.PEAK_POWER_SHUTDOWN ->
        "글래스 배터리가 부족해요. 충전한 뒤 다시 시작해 주세요."

    // The rest are internal states a wearer cannot act on, so the SDK's own wording is kept
    // rather than inventing advice that might send them down the wrong path.
    else -> description
}
