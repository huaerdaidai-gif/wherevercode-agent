package wherevercode.agent.update

import org.robolectric.RuntimeEnvironment
import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppUpdateInstallerSafetyTest {
    @Test fun `invalid archive is refused before a FileProvider install intent is created`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "invalid-update.apk").apply { writeText("not an APK") }
        try {
            val result = runCatching { AppUpdateInstaller.installApkIntent(context, file) }
            assertTrue(result.exceptionOrNull() is AppUpdateError.IncompatiblePackage)
        } finally { file.delete() }
    }
}
