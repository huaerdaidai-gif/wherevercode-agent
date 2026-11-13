package wherevercode.agent.core.task

import wherevercode.agent.AgentDiscovery
import wherevercode.agent.core.control.InMemoryControlStore

/** Test-only scheduler factory: production code must always provide a freeze port. */
fun testTaskScheduler(taskStore: TaskStore, discovery: AgentDiscovery): TaskScheduler =
    TaskScheduler(taskStore, discovery, InMemoryControlStore())
