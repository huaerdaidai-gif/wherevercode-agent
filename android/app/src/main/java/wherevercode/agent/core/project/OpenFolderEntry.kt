package wherevercode.agent.core.project

/**
 * “打开文件夹”唯一入口的位置种类（2026-11-12，maintainer：项目来源收成一个）。
 *
 * 老项目 example.invalid 的“打开 / 导入项目”对话框有 8 个选项（本机/局域网/
 * 远程设备/网盘/GitHub/Hugging Face/GitLab-Gitee/Git URL + 新建项目），
 * 它清晰的原因不是选项少，而是一个入口、一个对话框、一张单子，每项有
 * 名 + 一行说明。V5 乱的原因是三个入口散在项目页底部、不是一个对话框。
 * 所以这三类按种类收，不照搬 8 个并列：
 * - 本机/局域网/远程设备/网盘都是文件夹 → [LOCAL]（本机文件夹）与
 *   [REMOTE]（远程设备上的文件夹）：网盘同步下来的是本机文件夹，
 *   局域网设备就是远程设备。
 * - GitHub/Hugging Face/GitLab-Gitee/Git URL 都是 git 地址登记位置 →
 *   [GIT]（git 仓库地址）。
 * 新建项目不用单列：本机流程里有“新建文件夹…”。
 */
enum class OpenFolderLocationKind {
    /** 本机文件夹：本机工作区里的文件夹（挂载、网盘同步下来的也是文件夹）。 */
    LOCAL,

    /** 远程设备上的文件夹：连一台已配对设备，再打开上面的文件夹。 */
    REMOTE,

    /** git 仓库地址：粘地址登记一个位置，经 API 读，不拉代码。 */
    GIT,
}

/** 唯一入口单子里的一项：名 + 一行说明。 */
data class OpenFolderEntryOption(
    val kind: OpenFolderLocationKind,
    val name: String,
    val description: String,
    /**
     * 对应的项目来源注册 id（[ProjectSourceRegistry]）：LOCAL→local_folder、
     * REMOTE→device_folder、GIT→git_host。标题（name）直接取注册表条目的
     * title，两处不再各写一份；默认空串只为兼容旧构造点，正式单子必带 id。
     */
    val sourceId: String = "",
)

/**
 * “打开文件夹”唯一入口的单子：一个对话框里按位置种类列三项，每项有
 * 名 + 一行说明。顺序固定：本机、远程、git。
 *
 * 来源接线（2026-11-15）：三项分别对应 [ProjectSourceRegistry] 的
 * local_folder / device_folder / git_host，标题取注册表条目的 title，
 * 注册表是来源标题与可用性的唯一事实来源。网盘同步（drive_sync）与
 * NAS 挂载（nas_mount）本质是文件夹，分别归本机/远程两项，不单列；
 * 自定义来源（custom）尚不可用，不进单子。
 */
fun openFolderEntryOptions(): List<OpenFolderEntryOption> = listOf(
    OpenFolderEntryOption(
        OpenFolderLocationKind.LOCAL,
        ProjectSourceRegistry.find("local_folder")!!.title,
        "本机工作区里的文件夹；挂载、网盘同步下来的也是文件夹。",
        "local_folder",
    ),
    OpenFolderEntryOption(
        OpenFolderLocationKind.REMOTE,
        ProjectSourceRegistry.find("device_folder")!!.title,
        "连一台已配对设备（局域网、远程都一样），再打开上面的文件夹。",
        "device_folder",
    ),
    OpenFolderEntryOption(
        OpenFolderLocationKind.GIT,
        ProjectSourceRegistry.find("git_host")!!.title,
        "粘 GitHub / GitLab / Gitee / Hugging Face / Git URL 登记位置，经 API 读，不拉代码。",
        "git_host",
    ),
)
