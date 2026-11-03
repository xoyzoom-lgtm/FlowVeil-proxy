package com.v2ray.ang.net

import com.v2ray.ang.net.DiagLights.Group
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagLightsTest {
    private fun step(id: DiagStepId, status: StepStatus) = DiagStep(id, status)

    @Test
    fun alwaysFourLightsInFixedOrder() {
        assertEquals(listOf(Group.NETWORK, Group.SUBSCRIPTION, Group.PROVIDER, Group.SERVER), DiagLights.of(emptyList()).map { it.group })
        assertEquals(true, DiagLights.of(emptyList()).all { it.status == StepStatus.SKIPPED })
    }

    @Test
    fun theWorstStepOfAGroupDecidesItsLight() {
        val lights = DiagLights.of(
            listOf(
                step(DiagStepId.NETWORK, StepStatus.OK),
                step(DiagStepId.TIME, StepStatus.WARN),
                step(DiagStepId.DNS, StepStatus.OK),
                step(DiagStepId.SERVER, StepStatus.OK),
                step(DiagStepId.END_TO_END, StepStatus.FAIL),
            )
        )
        assertEquals(StepStatus.WARN, lights.first { it.group == Group.NETWORK }.status)
        assertEquals(StepStatus.FAIL, lights.first { it.group == Group.SERVER }.status)
        assertEquals(StepStatus.SKIPPED, lights.first { it.group == Group.SUBSCRIPTION }.status)
    }

    @Test
    fun skippedStepsDoNotHideAFailureAndBatteryHasNoLight() {
        val lights = DiagLights.of(
            listOf(
                step(DiagStepId.SUBSCRIPTION, StepStatus.SKIPPED),
                step(DiagStepId.SUBSCRIPTION, StepStatus.FAIL),
                step(DiagStepId.BATTERY, StepStatus.FAIL),
            )
        )
        assertEquals(StepStatus.FAIL, lights.first { it.group == Group.SUBSCRIPTION }.status)
        assertEquals(true, lights.filter { it.group != Group.SUBSCRIPTION }.all { it.status == StepStatus.SKIPPED })
    }

    @Test
    fun restrictionIsTheProviderLight() {
        val lights = DiagLights.of(listOf(step(DiagStepId.RESTRICTION, StepStatus.FAIL)))
        assertEquals(StepStatus.FAIL, lights.first { it.group == Group.PROVIDER }.status)
    }
}
