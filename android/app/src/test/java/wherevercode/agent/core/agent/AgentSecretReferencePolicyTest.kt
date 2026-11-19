package wherevercode.agent.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSecretReferencePolicyTest {
    @Test
    fun `agent secret is retained while any team member references the same authRef`() {
        val sharedAuthRef = "key:shared-team-model"

        val stillReferenced = AgentSecretReferencePolicy.isReferenced(
            authRef = sharedAuthRef,
            agentAuthRefs = emptyList(),
            brainConfigAuthRefs = emptyList(),
            teamMemberAuthRefs = listOf(sharedAuthRef),
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
