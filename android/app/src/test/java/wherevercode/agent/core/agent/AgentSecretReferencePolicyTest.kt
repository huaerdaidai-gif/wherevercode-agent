package wherevercode.agent.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.team.InMemoryTeamMemberStore
import wherevercode.agent.core.team.TeamMember

class AgentSecretReferencePolicyTest {
    @Test
    fun `agent secret is retained while any team member references the same authRef`() {
        val sharedAuthRef = "key:shared-team-model"
        val teamMemberStore = InMemoryTeamMemberStore().apply {
            saveMember(
                TeamMember.cloudChatMember(
                    memberId = "team-member-1",
                    agentId = "cloud-model-1",
                    displayName = "Shared model member",
                    authRef = sharedAuthRef,
                ),
            )
        }

        val stillReferenced = AgentSecretReferencePolicy.isReferenced(
            authRef = sharedAuthRef,
            agentAuthRefs = emptyList(),
            brainConfigAuthRefs = emptyList(),
            teamMemberAuthRefs = teamMemberStore.listMembers().map { it.authRef },
        )

        // removeAgent deletes the key only when this policy reports no owner.
        assertTrue(stillReferenced)
        assertFalse(
            AgentSecretReferencePolicy.isReferenced(
                authRef = sharedAuthRef,
                agentAuthRefs = emptyList(),
                brainConfigAuthRefs = emptyList(),
                teamMemberAuthRefs = emptyList(),
            ),
        )
    }
}
