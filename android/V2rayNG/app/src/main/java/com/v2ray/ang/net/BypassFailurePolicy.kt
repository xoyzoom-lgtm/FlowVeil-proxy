package com.v2ray.ang.net

/**
 * What the auto bypass does when the current server stops answering. A server of the user's own bypass list that he is
 * sitting on is no reason to do nothing: a dead one must be replaced, otherwise the whole feature stands still (the log of
 * build 241 shows an hour of "does not answer" with no reaction). Only when the open internet works and the server alone is
 * down is the user's choice left alone. Pure, so it can be tested.
 */
object BypassFailurePolicy {
    /** The server in use is one the user picked from his own list (manual mode), not one FlowVeil switched to. */
    fun userChoseBypassServer(onOurBypass: Boolean, manual: Boolean, inUserList: Boolean): Boolean =
        !onOurBypass && manual && inUserList

    /** The open internet works and only this server is dead: the ordinary failover may move off it, unless the user picked it. */
    fun failoverOnServerDown(userChoseBypass: Boolean): Boolean = !userChoseBypass
}
