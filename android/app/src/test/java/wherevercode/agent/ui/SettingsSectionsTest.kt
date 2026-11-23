package wherevercode.agent.ui

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.R

/**
 * 照片批 F：设置分节只切可见性——首页形态下旧表单全隐，分节形态下
 * 只显该节的块（大脑块内容原样），页头恒显、跳转 chips 恒隐。
 * 只充气真实布局，不启动 Activity。
 */
@RunWith(RobolectricTestRunner::class)
class SettingsSectionsTest {

    private fun themed(): ContextThemeWrapper = ContextThemeWrapper(
        RuntimeEnvironment.getApplication(),
        R.style.Theme_WhereverCode,
    )

    private fun rootWithHome(): LinearLayout {
        val scroll = LayoutInflater.from(themed())
            .inflate(R.layout.screen_settings, null) as ScrollView
        val root = scroll.getChildAt(0) as LinearLayout
        val home = LinearLayout(themed()).apply { tag = SettingsSections.HOME_TAG }
        root.addView(home, 0)
        return root
    }

    private fun visibilityOf(root: LinearLayout, id: Int): Int =
        root.findViewById<View>(id).visibility

    @Test
    fun `block ranges cover all nine blocks in layout order`() {
        val root = rootWithHome()
        val ranges = SettingsSections.blockRanges(root)
        assertNotNull(ranges)
        assertEquals(SettingsSections.Block.values().toSet(), ranges!!.keys)
        // 区间首尾相接、顺序与布局一致（主页容器占 0 位、页头与 chips
        // 在 1/2 位，大脑块从 3 开始、连接状态块排在仓库授权前，
        // 开发者诊断收到末尾）。
        val ordered = SettingsSections.Block.values().map { ranges.getValue(it) }
        assertEquals(3, ordered.first().first)
        assertEquals(root.childCount, ordered.last().last + 1)
        ordered.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
    }

    @Test
    fun `home mode hides every legacy block and shows only home`() {
        val root = rootWithHome()
        SettingsSections.applyHome(root)
        assertEquals(View.VISIBLE, root.getChildAt(0).visibility)
        assertEquals(View.GONE, visibilityOf(root, R.id.brainListContainer))
        assertEquals(View.GONE, visibilityOf(root, R.id.buttonCheckUpdate))
        assertEquals(View.GONE, visibilityOf(root, R.id.githubAuthStatus))
    }

    @Test
    fun `brain section shows only the brain block untouched`() {
        val root = rootWithHome()
        SettingsSections.apply(root, SettingsSections.Section.BRAIN)
        assertEquals(View.VISIBLE, visibilityOf(root, R.id.brainListContainer))
        assertEquals(View.VISIBLE, visibilityOf(root, R.id.brainAdvancedContainer))
        assertEquals(View.GONE, visibilityOf(root, R.id.githubAuthStatus))
        assertEquals(View.GONE, visibilityOf(root, R.id.buttonCheckUpdate))
        assertEquals(View.GONE, visibilityOf(root, R.id.platformTokenStatus))
    }

    @Test
    fun `connections section shows repos and token blocks together`() {
        val root = rootWithHome()
        SettingsSections.apply(root, SettingsSections.Section.CONNECTIONS)
        assertEquals(View.VISIBLE, visibilityOf(root, R.id.platformConnectionSummaryAddress))
        assertEquals(View.VISIBLE, visibilityOf(root, R.id.platformConnectionSummaryConnectionState))
        assertEquals(View.VISIBLE, visibilityOf(root, R.id.githubAuthStatus))
        assertEquals(View.VISIBLE, visibilityOf(root, R.id.gitlabAuthStatus))
        assertEquals(View.VISIBLE, visibilityOf(root, R.id.giteeAuthStatus))
        assertEquals(View.VISIBLE, visibilityOf(root, R.id.platformTokenStatus))
        assertEquals(View.GONE, visibilityOf(root, R.id.brainListContainer))
        assertEquals(View.GONE, visibilityOf(root, R.id.handAutoSyncRow))
    }

    @Test
    fun `platform connection section includes address credential and channel controls`() {
        val root = rootWithHome()
        SettingsSections.apply(root, SettingsSections.Section.TOKEN)
        assertEquals(View.VISIBLE, visibilityOf(root, R.id.platformConnectionAddress))
        assertEquals(View.VISIBLE, visibilityOf(root, R.id.platformConnectionCredentialInput))
        assertEquals(View.VISIBLE, visibilityOf(root, R.id.buttonLoadPlatformChannels))
        assertEquals(View.VISIBLE, visibilityOf(root, R.id.platformManualChannelId))
        assertEquals(View.GONE, visibilityOf(root, R.id.platformConnectionSummaryAddress))
        assertEquals(View.GONE, visibilityOf(root, R.id.githubAuthStatus))
    }

    @Test
    fun `about section shows update and drive rows but not diagnostics`() {
        val root = rootWithHome()
        SettingsSections.apply(root, SettingsSections.Section.ABOUT)
        assertEquals(View.VISIBLE, visibilityOf(root, R.id.buttonCheckUpdate))
        assertEquals(View.VISIBLE, visibilityOf(root, R.id.buttonDriveSignIn))
        assertEquals(View.GONE, visibilityOf(root, R.id.settingsLocalDeviceInfo))
    }

    @Test
    fun `null section restores the full legacy form`() {
        val root = rootWithHome()
        SettingsSections.apply(root, SettingsSections.Section.BRAIN)
        SettingsSections.apply(root, null)
        assertEquals(View.VISIBLE, visibilityOf(root, R.id.brainListContainer))
        assertEquals(View.VISIBLE, visibilityOf(root, R.id.githubAuthStatus))
        assertEquals(View.VISIBLE, visibilityOf(root, R.id.buttonCheckUpdate))
    }
}
