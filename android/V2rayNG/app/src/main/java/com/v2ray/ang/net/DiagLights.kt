package com.v2ray.ang.net

/**
 * The traffic light above the diagnosis: the many steps shown as four lights, so the place of the trouble is clear at a glance
 * (own network, subscription, the provider's restriction, the server). Pure, so it can be tested.
 */
object DiagLights {
    enum class Group { NETWORK, SUBSCRIPTION, PROVIDER, SERVER }

    data class Light(val group: Group, val status: StepStatus)

    private fun groupOf(id: DiagStepId): Group? = when (id) {
        DiagStepId.NETWORK, DiagStepId.TIME, DiagStepId.DNS -> Group.NETWORK
        DiagStepId.SUBSCRIPTION -> Group.SUBSCRIPTION
        DiagStepId.RESTRICTION -> Group.PROVIDER
        DiagStepId.SERVER, DiagStepId.END_TO_END -> Group.SERVER
        DiagStepId.BATTERY -> null
    }

    private fun worse(a: StepStatus, b: StepStatus): StepStatus {
        fun rank(s: StepStatus) = when (s) { StepStatus.FAIL -> 3; StepStatus.WARN -> 2; StepStatus.OK -> 1; StepStatus.SKIPPED -> 0 }
        return if (rank(b) > rank(a)) b else a
    }

    /** One light per group, always in the same order; a group with no finished step is SKIPPED (grey). */
    fun of(steps: List<DiagStep>): List<Light> = Group.entries.map { group ->
        Light(group, steps.filter { groupOf(it.id) == group }.fold(StepStatus.SKIPPED) { acc, s -> worse(acc, s.status) })
    }
}
