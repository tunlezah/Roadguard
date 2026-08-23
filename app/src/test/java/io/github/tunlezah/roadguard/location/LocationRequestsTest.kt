package io.github.tunlezah.roadguard.location

import com.google.common.truth.Truth.assertThat
import io.github.tunlezah.roadguard.location.LocationEngine.Client
import org.junit.Test

/**
 * Who gets to decide how often the GNSS receiver reports.
 *
 * The receiver used to be owned by the recorder alone, so the map and the speed readout only worked
 * while recording. Now several clients can want it at once, and the rule is that the shortest
 * requested interval wins. The case worth protecting is the thermal engine slowing the recorder
 * down to one fix every five seconds: that must not freeze the map the driver is looking at.
 */
class LocationRequestsTest {

    private val requests = LocationRequests()

    @Test
    fun `nobody wanting updates means the receiver stops`() {
        assertThat(requests.effectiveIntervalMs).isNull()
        assertThat(requests.isActive).isFalse()
    }

    @Test
    fun `a single client sets the interval`() {
        assertThat(requests.request(Client.Ui, 1_000)).isEqualTo(1_000)
        assertThat(requests.effectiveIntervalMs).isEqualTo(1_000)
        assertThat(requests.isActive).isTrue()
    }

    @Test
    fun `the shortest requested interval wins`() {
        requests.request(Client.Recorder, 5_000)
        assertThat(requests.request(Client.Ui, 1_000)).isEqualTo(1_000)
        assertThat(requests.effectiveIntervalMs).isEqualTo(1_000)
    }

    @Test
    fun `order of requests does not matter`() {
        requests.request(Client.Ui, 1_000)
        assertThat(requests.request(Client.Recorder, 5_000)).isEqualTo(1_000)
    }

    @Test
    fun `re-requesting replaces that client's interval rather than adding a second claim`() {
        requests.request(Client.Recorder, 1_000)
        requests.request(Client.Recorder, 4_000)

        assertThat(requests.clients).containsExactly(Client.Recorder)
        assertThat(requests.effectiveIntervalMs).isEqualTo(4_000)
    }

    @Test
    fun `releasing one client leaves the others running`() {
        requests.request(Client.Recorder, 5_000)
        requests.request(Client.Ui, 1_000)

        assertThat(requests.release(Client.Ui)).isEqualTo(5_000)
        assertThat(requests.isActive).isTrue()
        assertThat(requests.clients).containsExactly(Client.Recorder)
    }

    @Test
    fun `releasing the last client stops the receiver`() {
        requests.request(Client.Ui, 1_000)

        assertThat(requests.release(Client.Ui)).isNull()
        assertThat(requests.isActive).isFalse()
    }

    @Test
    fun `releasing a client that never asked is harmless`() {
        requests.request(Client.Ui, 1_000)

        assertThat(requests.release(Client.Recorder)).isEqualTo(1_000)
        assertThat(requests.clients).containsExactly(Client.Ui)
    }

    // ── The thermal case, which is the reason this class exists ───────────────────────────

    @Test
    fun `throttling the recorder cannot slow the receiver below what the UI wants`() {
        requests.request(Client.Ui, 1_000)
        requests.request(Client.Recorder, 1_000)

        // Thermal pressure: the recorder asks for one fix every five seconds.
        assertThat(requests.retune(Client.Recorder, 5_000)).isEqualTo(1_000)
        assertThat(requests.effectiveIntervalMs).isEqualTo(1_000)
    }

    @Test
    fun `throttling the recorder does slow the receiver when the UI is not on screen`() {
        requests.request(Client.Recorder, 1_000)

        assertThat(requests.retune(Client.Recorder, 5_000)).isEqualTo(5_000)
    }

    @Test
    fun `retuning a client that holds no claim changes nothing`() {
        requests.request(Client.Ui, 1_000)

        assertThat(requests.retune(Client.Recorder, 200)).isNull()
        assertThat(requests.effectiveIntervalMs).isEqualTo(1_000)
        assertThat(requests.clients).containsExactly(Client.Ui)
    }

    @Test
    fun `backgrounding the app mid-recording keeps the recorder's updates`() {
        // The sequence that matters on a drive: open the app, start recording, press Home.
        requests.request(Client.Ui, 1_000)
        requests.request(Client.Recorder, 1_000)
        requests.release(Client.Ui)

        assertThat(requests.isActive).isTrue()
        assertThat(requests.effectiveIntervalMs).isEqualTo(1_000)
    }

    @Test
    fun `stopping a recording while the app is open keeps the map alive`() {
        // The inverse, and the actual reported complaint: location must not die with the recording.
        requests.request(Client.Ui, 1_000)
        requests.request(Client.Recorder, 1_000)
        requests.release(Client.Recorder)

        assertThat(requests.isActive).isTrue()
        assertThat(requests.effectiveIntervalMs).isEqualTo(1_000)
    }

    // ── Bounds ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a zero or negative interval is floored rather than asking for everything`() {
        assertThat(requests.request(Client.Ui, 0)).isEqualTo(LocationRequests.MIN_INTERVAL_MS)
        assertThat(requests.request(Client.Ui, -1)).isEqualTo(LocationRequests.MIN_INTERVAL_MS)
    }

    @Test
    fun `clear drops every claim`() {
        requests.request(Client.Ui, 1_000)
        requests.request(Client.Recorder, 2_000)
        requests.request(Client.Setup, 3_000)

        requests.clear()

        assertThat(requests.isActive).isFalse()
        assertThat(requests.effectiveIntervalMs).isNull()
    }

    @Test
    fun `all three clients can hold a claim at once`() {
        requests.request(Client.Setup, 1_000)
        requests.request(Client.Ui, 2_000)
        requests.request(Client.Recorder, 3_000)

        assertThat(requests.clients).containsExactly(Client.Setup, Client.Ui, Client.Recorder)
        assertThat(requests.effectiveIntervalMs).isEqualTo(1_000)
    }
}
