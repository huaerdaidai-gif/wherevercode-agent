package wherevercode.agent.ui.flagship

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wherevercode.agent.brain.*
import wherevercode.agent.brain.siwc.BrainBootstrap
import wherevercode.agent.core.project.Project
import wherevercode.agent.core.security.SecretRedactor
import java.util.UUID

/** Local views use the very same production stores as BrainEngine; all I/O stays off the UI thread. */
@Composable
fun LocalFeatureScreen(route: String, resolve: () -> BrainBootstrap.BrainComponents?,
                       projects: () -> List<Project>, onResult: (LocalSearchResult) -> Unit, onBack: () -> Unit) {
    var attempt by remember { mutableStateOf(0) }
    var components by remember { mutableStateOf<BrainBootstrap.BrainComponents?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(attempt) {
        error = null
        // Initialization is asynchronous in MainActivity. Do not create another engine/store here.
        repeat(40) { if (components == null) { components = resolve(); if (components == null) delay(250) } }
        if (components == null) error = "本地组件尚未就绪，请重试"
    }
    V5Theme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                TextButton(onClick = onBack) { Text("返回") }
                Text(FlagshipRoutes.find(route).title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                if (components == null) {
                    if (error == null) CircularProgressIndicator() else {
                        Text(error!!); TextButton(onClick = { attempt++ }) { Text("重试") }
                    }
                } else when (route) {
                    "search" -> LocalSearchScreen(components!!, projects, onResult)
                    "memory" -> MemoryScreen(components!!.memoryStore)
                    "skills" -> SkillsScreen(components!!.skillStore, projects)
                }
            }
        }
    }
}

@Composable private fun LocalSearchScreen(components: BrainBootstrap.BrainComponents,
                                          projects: () -> List<Project>, onResult: (LocalSearchResult) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LocalSearchResult>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    Text("搜索本地会话正文、任务、项目及 70 个页面；离线可用，不搜索云端文件。")
    OutlinedTextField(query, { query = it }, label = { Text("搜索本地内容") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    LaunchedEffect(query, retry) {
        results = emptyList(); error = null; loading = query.isNotBlank()
        if (query.isBlank()) return@LaunchedEffect
        delay(250)
        try { results = withContext(Dispatchers.IO) {
            LocalSearch(components.conversationStore, components.taskStore, projects).search(query)
        } } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = "读取本地数据失败，请重试"
        } finally { loading = false }
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (error != null) { Text(error!!); TextButton(onClick = { retry++ }) { Text("重试") } }
    if (!loading && error == null && query.isNotBlank()) Text(if (results.isEmpty()) "没有匹配内容" else "${results.size} 条结果 · 显示前 100 条")
    Column(Modifier.verticalScroll(rememberScrollState())) {
        results.take(100).forEach { result ->
            Column(Modifier.fillMaxWidth().clickable { onResult(result) }.padding(vertical = 12.dp)) {
                Text("${result.kind} · ${result.title}", style = MaterialTheme.typography.titleMedium)
                Text(result.snippet, style = MaterialTheme.typography.bodySmall)
            }
            Divider()
        }
    }
}

@Composable private fun MemoryScreen(store: MemoryStore) {
    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf<List<MemoryEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var readable by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MemoryEntry?>(null) }
    LaunchedEffect(revision) {
        loading = true; readable = false; error = null
        try { entries = withContext(Dispatchers.IO) { MemoryKind.values().flatMap(store::list) }; readable = true }
        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = "读取记忆失败；原数据未被覆盖" }
        finally { loading = false }
    }
    Text("与大脑共用本机持久记忆。事实须确认后保存；不保存密码或令牌，不同步到云端。设备备注不代表配对授权。")
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    TextButton(onClick = { revision++ }, enabled = !busy) { Text("刷新") }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(key, { key = it }, label = { Text("事实名称") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value, { value = it }, label = { Text("事实内容") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { confirm = true }, enabled = !loading && readable && !busy && key.isNotBlank() && value.isNotBlank()) { Text("确认事实…") }
        if (!loading && entries.isEmpty() && error == null) Text("暂无长期记忆")
        entries.forEach { entry ->
            Divider(Modifier.padding(vertical = 12.dp))
            Text("${entry.kind} · ${entry.key}", style = MaterialTheme.typography.titleMedium)
            Text(entry.value)
            Text("来源：${entry.source} · ${entry.createdAtEpochSeconds}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { deleting = entry }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "删除记忆 ${entry.key}" }) { Text("删除记录…") }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { if (!busy) confirm = false }, title = { Text("确认保存为事实？") },
        text = { Text("$key\n$value\n同名事实会被替换。此内容可用于后续大脑上下文。") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val ok = withContext(Dispatchers.IO) { store.addFact(MemoryEntry(UUID.randomUUID().toString(), MemoryKind.FACT,
                        key.trim(), value.trim(), "user:android", System.currentTimeMillis()/1000, true), true) }
                    if (ok) { key = ""; value = ""; revision++ } else error = "拒绝保存：内容可能含有敏感信息"
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = "保存失败，未报告成功" }
                finally { busy = false; confirm = false }
            }
        }) { Text("确认保存") } }, dismissButton = { TextButton(enabled = !busy, onClick = { confirm = false }) { Text("取消") } })
    deleting?.let { entry -> AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("删除这条记忆？") },
        text = { Text("${entry.key}\n仅删除本地记忆，不撤销设备配对或权限。") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true; scope.launch {
                try { withContext(Dispatchers.IO) { check(store.remove(entry.kind, entry.key)) }; revision++ }
                catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = "删除失败，请刷新核实" }
                finally { busy = false; deleting = null }
            }
        }) { Text("删除") } }, dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable private fun SkillsScreen(store: SkillStore, projects: () -> List<Project>) {
    val scope = rememberCoroutineScope()
    var projectId by remember { mutableStateOf("") }
    var options by remember { mutableStateOf<List<Project>>(emptyList()) }
    var rows by remember { mutableStateOf<List<Skill>>(emptyList()) }
    var revision by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var readable by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Skill?>(null) }
    LaunchedEffect(projectId, revision) {
        loading = true; readable = false; error = null
        try { val data = withContext(Dispatchers.IO) { projects() to store.listForProject(projectId) }; options = data.first; rows = data.second; readable = true }
        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = "读取技能失败，请重试" }
        finally { loading = false }
    }
    Text("复用现有技能库。人工记录没有执行证据，不会作为成功技能自动注入。规则自动化服务尚未接通。")
    if (store !is wherevercode.agent.core.task.RoomSkillStore) Text("当前为内存回退：重启不保留技能记录，请先排查数据库初始化。", color = MaterialTheme.colorScheme.error)
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    TextButton(onClick = { revision++ }, enabled = !busy) { Text("刷新") }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("项目范围：${options.firstOrNull { it.projectId == projectId }?.name ?: "全局"}（包含全局技能）")
        TextButton(onClick = { projectId = "" }, enabled = !busy) { Text("全局") }
        options.forEach { project -> TextButton(onClick = { projectId = project.projectId }, enabled = !busy) { Text(project.name) } }
        OutlinedTextField(name, { name = it }, label = { Text("技能名称") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(summary, { summary = it }, label = { Text("做法摘要") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(trigger, { trigger = it }, label = { Text("触发词（逗号分隔）") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && !loading && readable && name.isNotBlank() && summary.isNotBlank(), onClick = {
            val capturedProject = projectId
            val capturedName = name.trim()
            val capturedSummary = summary.trim()
            val capturedTriggers = trigger.split(',', '，').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
            if (capturedTriggers.size > SkillMemory.MAX_TRIGGERS) { error = "最多支持 ${SkillMemory.MAX_TRIGGERS} 个触发词" }
            else if (SecretRedactor.containsSecret(listOf(name, summary, trigger).joinToString("\n"))) { error = "拒绝保存敏感信息" }
            else {
                busy = true
                scope.launch {
                    try { withContext(Dispatchers.IO) {
                        val now = System.currentTimeMillis()/1000
                        store.upsert(Skill(UUID.randomUUID().toString(), capturedProject, capturedName, capturedTriggers,
                            capturedSummary, emptyList(), emptyList(), emptyList(), emptyList(), 0, 0, 0, now, now))
                    }; name = ""; summary = ""; trigger = ""; revision++ }
                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = "保存失败，未报告成功" }
                    finally { busy = false }
                }
            }
        }) { Text("保存人工技能记录") }
        if (!loading && rows.isEmpty() && error == null) Text("此范围暂无技能")
        rows.forEach { skill ->
            Divider(Modifier.padding(vertical = 12.dp))
            Text(skill.name, style = MaterialTheme.typography.titleMedium)
            Text(skill.summary)
            Text("范围：${skill.projectId.ifBlank { "全局" }} · 成功 ${skill.successCount} / 失败 ${skill.failureCount}")
            Text(if (skill.evidenceTaskIds.isEmpty()) "尚无执行证据" else "证据任务：${skill.evidenceTaskIds.joinToString()}")
            Text("触发词：${skill.triggers.joinToString()}\n步骤：${skill.steps.joinToString("；")}\n工具：${skill.tools.joinToString()}\n注意：${skill.pitfalls.joinToString()}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { deleting = skill }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "删除技能 ${skill.id}" }) { Text("删除技能…") }
        }
    }
    deleting?.let { skill -> AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("删除技能记录？") },
        text = { Text(skill.name) }, confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true; scope.launch {
                try { withContext(Dispatchers.IO) { store.remove(skill.id) }; revision++ }
                catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = "删除失败，请刷新核实" }
                finally { busy = false; deleting = null }
            }
        }) { Text("删除") } }, dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("取消") } }) }
}
