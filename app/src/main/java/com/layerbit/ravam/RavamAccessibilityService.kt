package com.layerbit.ravam

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Does nothing, on purpose.
 *
 * This service exists so that Ravam's UID is registered as an accessibility UID. AOSP's
 * audio policy (`AudioPolicyService::updateUidStates_l`) exempts such a UID, recording on
 * `VOICE_RECOGNITION`, from the rule that silences every other app during a call. That
 * exemption is the only route to call audio that does not require root or a shell
 * binding, and it is why this file is here.
 *
 * It reads no screen content, handles no events and declares no event types. Nothing
 * about the user's screen is observed — see `res/xml/accessibility_service_config.xml`,
 * which scopes it to this package alone.
 *
 * Google Play bans the accessibility API for call recording, which is why Ravam is
 * distributed outside Play. Android 17's Advanced Protection can revoke it entirely; the
 * tier ladder exists so that is a degradation rather than an ending.
 */
class RavamAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* nothing, deliberately */ }
    override fun onInterrupt() { /* nothing, deliberately */ }
}
