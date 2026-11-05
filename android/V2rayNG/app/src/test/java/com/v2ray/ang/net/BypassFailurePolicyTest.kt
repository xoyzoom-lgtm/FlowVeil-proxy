package com.v2ray.ang.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassFailurePolicyTest {
    @Test
    fun serverFromTheUsersOwnListInManualModeIsTheUsersChoice() {
        assertTrue(BypassFailurePolicy.userChoseBypassServer(onOurBypass = false, manual = true, inUserList = true))
    }

    @Test
    fun otherCasesAreNotTheUsersChoice() {
        assertFalse(BypassFailurePolicy.userChoseBypassServer(onOurBypass = true, manual = true, inUserList = true))
        assertFalse(BypassFailurePolicy.userChoseBypassServer(onOurBypass = false, manual = false, inUserList = true))
        assertFalse(BypassFailurePolicy.userChoseBypassServer(onOurBypass = false, manual = true, inUserList = false))
    }

    @Test
    fun deadServerWithWorkingInternetIsLeftAloneOnlyWhenTheUserPickedIt() {
        assertFalse(BypassFailurePolicy.failoverOnServerDown(userChoseBypass = true))
        assertTrue(BypassFailurePolicy.failoverOnServerDown(userChoseBypass = false))
    }
}
