package wherevercode.agent.core.task

import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TaskDatabaseProviderTest {
    @Test
    fun `brain and dispatch obtain the same app database instance`() {
        val context = RuntimeEnvironment.getApplication()

        val brainDatabase = TaskDatabaseProvider.get(context)
        val dispatchDatabase = TaskDatabaseProvider.get(context.applicationContext)

        assertSame(brainDatabase, dispatchDatabase)
    }
}
