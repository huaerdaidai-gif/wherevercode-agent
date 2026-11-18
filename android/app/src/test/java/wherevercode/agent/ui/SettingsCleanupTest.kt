package wherevercode.agent.ui

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import wherevercode.agent.R
import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainKind

/**
 * V5 设置清理（2026-11-11）：设置页不再含垃圾项。
 *
 * 回归：用户报“设置里是一堆垃圾”——删“显示与权限”三行“未实现”、
 * “外观”指路牌、“多项目切换不在本轮”、过期通用表单文案与 8B fp8
 * 例子；CLI 整节在未发现配置时隐藏；通用大脑行说实话。
 * 设置清理 2（2026-11-11，Claude 极简标准）：技术表单全收到默认
 * 折叠的“高级配置”里；切换器只留名 + 一句话 + 打勾；沙箱文案、
 * “接入中”与实现细节文案彻底出清。
 * 全程 fake：只充气布局 + 纯逻辑断言，零真实调用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class SettingsCleanupTest {

    private fun themed(): ContextThemeWrapper = ContextThemeWrapper(
        RuntimeEnvironment.getApplication(),
        R.style.Theme_WhereverCode,
    )

    /** 收集布局里全部可见文案（含 EditText 的 hint，提示语也要干净）。 */
    private fun allTexts(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) {
                out += v.text.toString()
                if (v is EditText) out += v.hint?.toString().orEmpty()
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    @Test
    fun `settings layout contains no placeholder or stale copy`() {
        val root = LayoutInflater.from(themed()).inflate(R.layout.screen_settings, null)
        val texts = allTexts(root)
        val forbidden = listOf(
            "未实现",
            "多项目切换不在本轮",
            "当前还不能用它聊天",
            "fp8-fast",
            "显示与权限",
            // 设置清理 2：沙箱文案、“接入中”与实现细节文案彻底出清。
            "沙箱内未发现",
            "仍在接入中",
            "全局大脑",
            "只查 /models",
            "非逐行实时",
            "120 秒",
            "600 秒",
        )
        forbidden.forEach { bad ->
            assertTrue("设置页不应再含“$bad”", texts.none { it.contains(bad) })
        }
        // “外观”指路牌已删：没有任何一行再叫用户去抽屉改主题。
        assertTrue(texts.none { it.contains("打开抽屉") })
        // 通用表单说实话：提到去 Agent 页加同名 Agent 的路径。
        assertTrue(texts.any { it.contains("model-api-generic") })
    }

    @Test
    fun `advanced brain config is collapsed by default`() {
        val root = LayoutInflater.from(themed()).inflate(R.layout.screen_settings, null)
        val toggle = root.findViewById<View>(R.id.buttonBrainAdvanced)
        assertNotNull(toggle)
        val container = root.findViewById<View>(R.id.brainAdvancedContainer)
        assertNotNull(container)
        // 默认折叠：技术表单（Cloudflare / DeepSeek / 通用 / CLI）不占首屏。
        assertEquals(View.GONE, container!!.visibility)
        // 旧 id 全部保留在折叠容器里（findViewById 能跨 GONE 找到）。
        listOf(
            R.id.cloudflareStatus,
            R.id.cfAccountId,
            R.id.cfApiToken,
            R.id.cfModel,
            R.id.buttonSaveCloudflare,
            R.id.buttonClearCloudflare,
            R.id.deepseekStatus,
            R.id.dsBaseUrl,
            R.id.dsModel,
            R.id.dsApiKey,
            R.id.buttonRefreshDeepSeekModels,
            R.id.buttonSaveDeepSeek,
            R.id.buttonClearDeepSeek,
            R.id.genericBrainLabel,
            R.id.genericBaseUrl,
            R.id.genericModel,
            R.id.genericApiKey,
            R.id.buttonTestGenericModels,
            R.id.buttonSaveGenericBrain,
            R.id.cliBrainSection,
            R.id.cliBrainStatus,
            R.id.buttonVerifyCli,
        ).forEach { assertNotNull(container.findViewById<View>(it)) }
    }

    @Test
    fun `cli section container exists and keeps its child ids`() {
        val root = LayoutInflater.from(themed()).inflate(R.layout.screen_settings, null)
        val section = root.findViewById<View>(R.id.cliBrainSection)
        assertNotNull(section)
        listOf(
            R.id.cliBrainStatus,
            R.id.cliBrainBinary,
            R.id.cliBrainNote,
            R.id.buttonVerifyCli,
        ).forEach { assertNotNull(section!!.findViewById<View>(it)) }
    }

    @Test
    fun `generic brain row is honest about agent routing and global switch`() {
        val generic = BrainConfig(
            brainId = "model-api-openrouter",
            kind = BrainKind.MODEL_API,
            adapterId = BrainLoginRows.GENERIC_ADAPTER_ID,
            label = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "x-ai/grok-4",
            authRef = "model-api:model-api-openrouter",
        )
        val rows = BrainLoginRows.buildRows(
            listOf(generic),
            currentBrainId = null,
            isConfigured = { true },
            siwcSignedIn = false,
        )
        assertEquals(1, rows.size)
        val row = rows[0]
        // 通用大脑放开切换（maintainer 2026-11-11）：已配置即可设为全局大脑，
        // 与其它脑同口径；旧的“仅支持 Agent 对话”提示已删。工具调用
        // 的能力（第二阶段起支持，尚未在真机上验证）由切换提示与状态
        // 行如实说明，不再藏在行里。
        assertEquals("已配置", row.statusText)
        assertFalse(row.statusText.contains("未接入"))
        assertFalse(row.statusText.contains("接入中"))
        assertFalse(row.statusText.contains("全局大脑"))
        assertTrue(row.canSwitch)
        assertEquals("设为当前大脑", row.switchHint)
        assertFalse(row.switchHint.contains("Agent 对话"))
        assertFalse(row.switchHint.contains("全局大脑"))
        assertFalse(row.switchHint.contains("接入中"))
    }

    @Test
    fun `manage actions offer edit and delete for all model-api brains`() {
        val generic = BrainConfig(
            brainId = "model-api-zhipu",
            kind = BrainKind.MODEL_API,
            adapterId = BrainLoginRows.GENERIC_ADAPTER_ID,
            label = "zhipu",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            model = "glm-5.3",
            authRef = "model-api:model-api-zhipu",
        )
        assertEquals(listOf("编辑…", "删除…"), BrainLoginRows.manageActions(generic))
        // 一线大脑（2026-11-12 大脑登陆补全）：全部类型都能删，不再只开放
        // 通用大脑——删后墓碑记 deleted_brain_ids_json，播种跳过已删的，
        // 不会再“复活”，列表末尾有“＋ 重新添加”可恢复。故一线大脑
        // 同样给编辑与删除入口。
        val firstLine = BrainConfig(
            brainId = "deepseek",
            kind = BrainKind.MODEL_API,
            adapterId = "deepseek",
            label = "DeepSeek API",
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-flash",
            authRef = "deepseek:api-key",
        )
        assertEquals(listOf("编辑…", "删除…"), BrainLoginRows.manageActions(firstLine))
        assertFalse(BrainLoginRows.isGenericBrain(firstLine))
        assertTrue(BrainLoginRows.isGenericBrain(generic))
    }

    @Test
    fun `delete warning states config and key removal current clearing and affected agents`() {
        val generic = BrainConfig(
            brainId = "model-api-custom",
            kind = BrainKind.MODEL_API,
            adapterId = BrainLoginRows.GENERIC_ADAPTER_ID,
            label = "智谱",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            model = "glm5.3",
            authRef = "model-api:model-api-custom",
        )
        val warning = BrainLoginRows.deleteWarning(generic, isCurrent = true, agentNames = listOf("智谱助手"))
        assertTrue(warning.contains("配置与它存在本机 Keystore 里的 Key 会一起删除"))
        assertTrue(warning.contains("当前大脑清空，不会自动切到别的脑"))
        assertTrue(warning.contains("智谱助手"))
        assertTrue(warning.contains("Agent 记录本身不动"))
        val plain = BrainLoginRows.deleteWarning(generic, isCurrent = false, agentNames = emptyList())
        assertFalse(plain.contains("当前大脑清空"))
    }

    @Test
    fun `cli section is hidden when no cli config is discovered`() {
        assertFalse(BrainLoginRows.shouldShowCliSection(null))
        val config = BrainConfig(
            brainId = "cli-membership",
            kind = BrainKind.AGENT_RUNTIME,
            adapterId = "cli-membership",
            label = "CLI 会员额度",
            baseUrl = null,
            model = "codex",
            authRef = null,
            cliBinary = "codex",
        )
        assertTrue(BrainLoginRows.shouldShowCliSection(config))
        // 设置清理 2：只剩配置壳、二进制为空时整节同样隐藏，不写“未发现”。
        val stub = config.copy(cliBinary = null, model = "")
        assertFalse(BrainLoginRows.shouldShowCliSection(stub))
        assertEquals("未选择 CLI", BrainLoginRows.cliStatusText(stub))
    }
}
