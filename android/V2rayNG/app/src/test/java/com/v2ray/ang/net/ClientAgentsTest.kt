package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientAgentsTest {
    @Test
    fun retryOnRefusalOrAppLink() {
        assertTrue(ClientAgents.retryable("Request failed with status code 403"))
        assertTrue(ClientAgents.retryable("Request failed with status code 400"))
        assertTrue(ClientAgents.retryable("Request failed with status code 503"))
        assertTrue(ClientAgents.retryable("redirect to an app link: happ"))
    }

    @Test
    fun noRetryWhenNothingToGain() {
        assertFalse(ClientAgents.retryable(null))
        assertFalse(ClientAgents.retryable(""))
        assertFalse(ClientAgents.retryable("Request failed with status code 404"))
        assertFalse(ClientAgents.retryable("Unable to resolve host"))
        assertFalse(ClientAgents.retryable("redirect to an encrypted Happ link"))
    }

    @Test
    fun neverPretendsToBeHapp() {
        assertFalse(ClientAgents.FALLBACK.any { it.startsWith("Happ", ignoreCase = true) })
    }
}
