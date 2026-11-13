package wherevercode.agent.core.team

import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleTeamChannelMembersTest {
    @Test
    fun hidesOnlyTheLocalAppTransportIdentity() {
        val members = listOf(
            TeamChannelMember(agentId = "app", displayName = "V5 App", isLocalApp = true),
            TeamChannelMember(agentId = "chatgpt", displayName = "ChatGPT", isPlatformAgent = true),
            TeamChannelMember(agentId = "muse", displayName = "Muse"),
        )

        assertEquals(
            listOf("chatgpt", "muse"),
            visibleTeamChannelMembers(members).map { it.agentId },
        )
    }
}
