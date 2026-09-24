package io.github.tunlezah.roadguard.map

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.tunlezah.roadguard.power.PowerPolicy
import io.github.tunlezah.roadguard.thermal.MapRenderBudget
import io.github.tunlezah.roadguard.thermal.ThermalLevel
import io.github.tunlezah.roadguard.thermal.ThermalPolicy.Companion.planFor
import org.junit.Test

/**
 * [MapWorkBudget.forRenderBudget] turns the thermal and battery-safe map budget into what the map
 * pane actually does.
 *
 * The thermal plans always set a map budget, but nothing used to read it: the map animated at full
 * rate however hot the phone was. Following the vehicle with a camera animation re-renders the
 * whole map continuously while moving, which on a small GPU is one of the largest draws in the
 * app, so these tests pin that a tighter budget really does less work.
 */
class MapWorkBudgetTest {

    @Test
    fun `a full budget is the default map`() {
        assertThat(MapWorkBudget.forRenderBudget(MapRenderBudget.Full)).isEqualTo(MapWorkBudget())
    }

    @Test
    fun `a reduced budget keeps the map but stops animating it`() {
        val budget = MapWorkBudget.forRenderBudget(MapRenderBudget.Reduced)
        assertThat(budget.renderEnabled).isTrue()
        assertThat(budget.allowAnimation).isFalse()
        assertThat(budget.positionUpdateIntervalMs).isGreaterThan(MapWorkBudget().positionUpdateIntervalMs)
    }

    @Test
    fun `frozen and disabled budgets take the map off screen`() {
        for (level in listOf(MapRenderBudget.Frozen, MapRenderBudget.Disabled)) {
            val budget = MapWorkBudget.forRenderBudget(level)
            assertWithMessage(level.name).that(budget.renderEnabled).isFalse()
            assertWithMessage(level.name).that(budget.allowAnimation).isFalse()
        }
    }

    @Test
    fun `a tighter budget never does more work`() {
        val budgets = MapRenderBudget.entries.map { MapWorkBudget.forRenderBudget(it) }
        for ((looser, tighter) in budgets.zipWithNext()) {
            assertThat(!tighter.renderEnabled || looser.renderEnabled).isTrue()
            assertThat(!tighter.allowAnimation || looser.allowAnimation).isTrue()
        }
    }

    @Test
    fun `the map never animates in battery-safe mode, at any temperature`() {
        for (level in ThermalLevel.entries) {
            val budget = MapWorkBudget.forRenderBudget(PowerPolicy.restrain(planFor(level), batterySafe = true).mapRenderBudget)
            assertWithMessage(level.name).that(budget.allowAnimation).isFalse()
        }
    }
}
