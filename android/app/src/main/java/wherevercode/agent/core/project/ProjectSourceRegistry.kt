package wherevercode.agent.core.project

/**
 * 项目来源注册表（70 屏补漏，2026-11-15）：项目来源类型可扩展，不写死。
 *
 * 入口仍只有一个「打开文件夹」；来源只是类型登记：新增一种来源只往
 * 注册表加一项，不改打开流程、不写死厂商名单（Git 托管只登记类型，
 * 具体厂商由地址本身决定）。对话绑定项目只认项目 id，不认来源类型。
 *
 * 接线（2026-11-15）：「打开文件夹」单子（[openFolderEntryOptions]）的
 * 三项标题直接取本注册表 local_folder / device_folder / git_host 的
 * title；标题以现行 UX 文案为准（device_folder=「远程设备上的文件夹」、
 * git_host=「git 仓库地址」）。drive_sync / nas_mount 本质是文件夹，
 * 分别归本机/设备文件夹，不单列选项；custom 不可用，不进单子。
 */
data class ProjectSource(
    /** 稳定 id，不改名。 */
    val id: String,
    /** 中文标题。 */
    val title: String,
    /** 是否已可用；不可用时点选只给诚实提示。 */
    val available: Boolean,
    /** 一行说明（不可用时须含「待开发」）。 */
    val note: String,
)

object ProjectSourceRegistry {

    private val builtins: List<ProjectSource> = listOf(
        ProjectSource(
            "local_folder", "本机文件夹", true,
            "本机工作区里的文件夹；挂载、同步下来的也是文件夹。",
        ),
        ProjectSource(
            "device_folder", "远程设备上的文件夹", true,
            "连一台已配对设备，再打开上面的文件夹。",
        ),
        ProjectSource(
            "git_host", "git 仓库地址", true,
            "粘 Git 托管仓库地址登记位置，经 API 读，不拉代码；厂商可扩展，不写死名单。",
        ),
        ProjectSource(
            "drive_sync", "网盘同步文件夹", true,
            "网盘同步到本机的文件夹，同步下来即本机文件夹。",
        ),
        ProjectSource(
            "nas_mount", "NAS 挂载文件夹", true,
            "NAS 挂载在设备上即设备文件夹，按设备文件夹打开。",
        ),
        ProjectSource(
            "custom", "自定义来源", false,
            "自定义来源待开发（新来源类型只加注册项，不改打开流程）。",
        ),
    )

    private val registered: MutableList<ProjectSource> = builtins.toMutableList()

    /** 全部已注册来源（内置在前，运行时注册的在后）。 */
    fun sources(): List<ProjectSource> = registered.toList()

    /** 运行时扩展：注册一个新来源类型；同 id 已存在时不重复添加。 */
    fun register(source: ProjectSource): Boolean {
        if (registered.any { it.id == source.id }) return false
        registered += source
        return true
    }

    fun find(id: String): ProjectSource? = registered.firstOrNull { it.id == id }
}
