package wherevercode.agent.core.team

/** Hides the transport-only app row from the channel member list. */
fun visibleTeamChannelMembers(members: List<TeamChannelMember>): List<TeamChannelMember> =
    members.filterNot { it.agentId == "app" }
