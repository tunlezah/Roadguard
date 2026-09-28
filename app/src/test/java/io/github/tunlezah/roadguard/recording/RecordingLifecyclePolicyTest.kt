package io.github.tunlezah.roadguard.recording

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.tunlezah.roadguard.settings.PowerConnectedAction
import org.junit.Test

/**
 * The two rules that decide whether a stopped dashcam stays stopped.
 *
 * [ServiceStandDown] keeps the recording service from outliving its recording -- a lingering
 * `camera` foreground service is a process that still holds the camera grant and can be told to
 * record again, which is how a phone that was stopped keeps recording in the background.
 * [AutoStartPolicy] keeps the two automatic triggers -- opening the app, and vehicle power -- from
 * quietly undoing an explicit Stop.
 *
 * Both are pure, so the behaviour a driver relies on is pinned here rather than discovered on a
 * phone.
 */
class RecordingLifecyclePolicyTest {

    // ── ServiceStandDown ────────────────────────────────────────────────────────────────

    @Test
    fun `an unarmed service never stands down, however idle it looks`() {
        // Before the first segment a fresh service is idle; standing down then would stop it
        // before it ever recorded.
        assertThat(ServiceStandDown.shouldStandDown(sessionActive = false, holdsWakeLock = false, armed = false))
            .isFalse()
    }

    @Test
    fun `an armed service stands down only when idle and holding no wake lock`() {
        assertWithMessage("active session")
            .that(ServiceStandDown.shouldStandDown(sessionActive = true, holdsWakeLock = false, armed = true))
            .isFalse()
        assertWithMessage("still finalising a clip")
            .that(ServiceStandDown.shouldStandDown(sessionActive = false, holdsWakeLock = true, armed = true))
            .isFalse()
        assertWithMessage("idle, nothing pending")
            .that(ServiceStandDown.shouldStandDown(sessionActive = false, holdsWakeLock = false, armed = true))
            .isTrue()
    }

    // ── AutoStartPolicy: opening the app ─────────────────────────────────────────────────

    @Test
    fun `opening the app auto-starts only when set up, enabled and not explicitly stopped`() {
        assertWithMessage("happy path")
            .that(AutoStartPolicy.shouldAutoStartOnOpen(setupComplete = true, autoStartEnabled = true, userStopped = false))
            .isTrue()
        assertWithMessage("setup not finished")
            .that(AutoStartPolicy.shouldAutoStartOnOpen(setupComplete = false, autoStartEnabled = true, userStopped = false))
            .isFalse()
        assertWithMessage("auto-start turned off")
            .that(AutoStartPolicy.shouldAutoStartOnOpen(setupComplete = true, autoStartEnabled = false, userStopped = false))
            .isFalse()
        assertWithMessage("the driver pressed Stop")
            .that(AutoStartPolicy.shouldAutoStartOnOpen(setupComplete = true, autoStartEnabled = true, userStopped = true))
            .isFalse()
    }

    // ── AutoStartPolicy: power connected ─────────────────────────────────────────────────

    @Test
    fun `power connected starts recording only when the setting asks for it`() {
        for (action in PowerConnectedAction.entries) {
            val expected = action == PowerConnectedAction.StartRecording
            assertWithMessage(action.name)
                .that(
                    AutoStartPolicy.shouldStartOnPowerConnected(
                        setupComplete = true,
                        action = action,
                        userStopped = false,
                        sessionActive = false,
                    ),
                )
                .isEqualTo(expected)
        }
    }

    @Test
    fun `power connected stands down after an explicit stop, and while already recording`() {
        assertWithMessage("explicit stop still in force")
            .that(
                AutoStartPolicy.shouldStartOnPowerConnected(
                    setupComplete = true,
                    action = PowerConnectedAction.StartRecording,
                    userStopped = true,
                    sessionActive = false,
                ),
            )
            .isFalse()
        assertWithMessage("already recording")
            .that(
                AutoStartPolicy.shouldStartOnPowerConnected(
                    setupComplete = true,
                    action = PowerConnectedAction.StartRecording,
                    userStopped = false,
                    sessionActive = true,
                ),
            )
            .isFalse()
        assertWithMessage("setup not finished")
            .that(
                AutoStartPolicy.shouldStartOnPowerConnected(
                    setupComplete = false,
                    action = PowerConnectedAction.StartRecording,
                    userStopped = false,
                    sessionActive = false,
                ),
            )
            .isFalse()
    }
}
