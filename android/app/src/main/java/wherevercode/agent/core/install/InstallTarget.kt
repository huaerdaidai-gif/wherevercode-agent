package wherevercode.agent.core.install

/**
 * 一键安装：目标设备系统与安装方式（纯逻辑，可单测）。
 *
 * 设计（maintainer 2026-11-10 09:18 + V5-UI.md）：
 * 管理 → 添加设备 → 选系统 → 安装/配对 → 授权项目目录与执行能力 → 在线管理。
 * 各系统只显示其真实可用的安装方式，不可用的不显示、不假装可用。
 * 云端 Agent 不装 Hand，走适配器。
 */
enum class DeviceOs(val displayName: String) {
    MAC("Mac"),
    LINUX("Linux"),
    WINDOWS("Windows"),
    VPS("机房服务器 / VPS"),
    ANDROID("Android"),
    IOS("iOS"),
    CLOUD("云端 Agent"),
}

/** 安装方式。 */
enum class InstallMethod(val displayName: String) {
    /** App 经 SSH 连上目标机、上传安装包、跑安装脚本（Mac/Linux/VPS 首选）。 */
    SSH("SSH 一键安装"),
    /** 给出在目标机上跑的本机安装命令（无 SSH 时用；安装包由 App 分享导出）。 */
    LOCAL_COMMAND("本机安装命令"),
    /** Windows PowerShell 安装命令（新写脚本；远程安装前提未评估前只给本机命令）。 */
    POWERSHELL("PowerShell 安装命令"),
    /** 目标机上已经装好 Hand，直接走 hello → 指纹 → 配对。 */
    EXISTING_HAND("已装 Hand，直接配对"),
    /** Android：扫码配对（已装 V5 App 的设备走直接发现或配对）。 */
    QR_PAIR("扫码配对"),
    /** iOS：只给安装指引，不承诺静默安装。 */
    GUIDANCE("查看安装指引"),
    /** 云端 Agent：走 API / Bot / MCP 适配，不装 Hand。 */
    ADAPTER("走 API / Bot / MCP 适配"),
}

/**
 * 添加设备三条路的优先级（maintainer 2026-11-11 10:30 定，主流通用技术优先）。
 * 界面按这个顺序排，前面走不通才往后走，不要一上来就推独有脚本。
 */
enum class InstallRoute(val displayName: String, val note: String) {
    /**
     * 路 1（主流第一优先）：局域网发现（mDNS 优先，手输 IP 兜底）+
     * 双方密码学配对（指纹核对 + 6 位配对码）。这条路不装 Hand，
     * 是发现 + 配对，不是安装方式。
     */
    DISCOVERY(
        "局域网发现 + 配对",
        "同一 Wi-Fi 下的设备自动发现，指纹核对 + 配对码双向确认",
    ),

    /**
     * 路 2：通用的一键运行脚本——用户在目标机自己的终端里跑安装命令，
     * Mac/Linux/Windows 通用，不依赖 App 的 SSH 能力。
     */
    GENERIC_SCRIPT(
        "通用一键运行脚本",
        "把安装包传到目标机，在它自己的终端里跑一条安装命令",
    ),

    /**
     * 路 3：V5 独有的一键运行脚本——App 直连目标机 SSH，上传安装包
     * 并自动安装，全程不用用户碰终端。
     */
    V5_SSH(
        "V5 一键安装（App 直连 SSH）",
        "App 直连目标机 SSH，上传安装包并自动安装",
    ),
}

/** 某系统可用的安装方式（只列真实可用的）。 */
fun installMethodsFor(os: DeviceOs): List<InstallMethod> = when (os) {
    DeviceOs.MAC -> listOf(InstallMethod.SSH, InstallMethod.LOCAL_COMMAND, InstallMethod.EXISTING_HAND)
    DeviceOs.LINUX -> listOf(InstallMethod.SSH, InstallMethod.LOCAL_COMMAND, InstallMethod.EXISTING_HAND)
    DeviceOs.VPS -> listOf(InstallMethod.SSH, InstallMethod.LOCAL_COMMAND, InstallMethod.EXISTING_HAND)
    DeviceOs.WINDOWS -> listOf(InstallMethod.POWERSHELL, InstallMethod.EXISTING_HAND)
    DeviceOs.ANDROID -> listOf(InstallMethod.QR_PAIR, InstallMethod.EXISTING_HAND)
    DeviceOs.IOS -> listOf(InstallMethod.GUIDANCE, InstallMethod.EXISTING_HAND)
    DeviceOs.CLOUD -> listOf(InstallMethod.ADAPTER)
}

/**
 * 某系统在某路线下可用的安装方式（只列真实可用的；路线顺序即界面顺序）。
 * 路 1（DISCOVERY）不装 Hand，返回空列表。
 */
fun installMethodsFor(os: DeviceOs, route: InstallRoute): List<InstallMethod> = when (route) {
    InstallRoute.DISCOVERY -> emptyList()
    InstallRoute.GENERIC_SCRIPT -> when (os) {
        DeviceOs.MAC, DeviceOs.LINUX, DeviceOs.VPS ->
            listOf(InstallMethod.LOCAL_COMMAND, InstallMethod.EXISTING_HAND)
        DeviceOs.WINDOWS -> listOf(InstallMethod.POWERSHELL, InstallMethod.EXISTING_HAND)
        DeviceOs.ANDROID -> listOf(InstallMethod.QR_PAIR, InstallMethod.EXISTING_HAND)
        DeviceOs.IOS -> listOf(InstallMethod.GUIDANCE, InstallMethod.EXISTING_HAND)
        DeviceOs.CLOUD -> listOf(InstallMethod.ADAPTER)
    }
    InstallRoute.V5_SSH -> when (os) {
        DeviceOs.MAC, DeviceOs.LINUX, DeviceOs.VPS ->
            listOf(InstallMethod.SSH, InstallMethod.EXISTING_HAND)
        DeviceOs.WINDOWS -> listOf(InstallMethod.POWERSHELL, InstallMethod.EXISTING_HAND)
        DeviceOs.ANDROID -> listOf(InstallMethod.QR_PAIR, InstallMethod.EXISTING_HAND)
        DeviceOs.IOS -> listOf(InstallMethod.GUIDANCE, InstallMethod.EXISTING_HAND)
        DeviceOs.CLOUD -> listOf(InstallMethod.ADAPTER)
    }
}

/**
 * SSH 目标（只存本次安装用的连接信息）。
 *
 * 安全：密码/私钥只放内存、用完即清，绝不持久化、绝不明文存盘
 * （见 §25.2：不得索取或明文保存 SSH 私钥/长期凭证；每次 SSH
 * 连接须用户在设备管理里显式授权）。
 */
data class SshTarget(
    val host: String,
    val port: Int = 22,
    val username: String,
) {
    /** 校验。返回可读错误列表；空 = 合法。 */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        val h = host.trim()
        if (h.isEmpty()) errors += "SSH 地址不能为空"
        else if (h.contains("://")) errors += "SSH 地址不要带 http:// 或 https://：只填 IP 或主机名"
        else if (h.any { it.isWhitespace() }) errors += "SSH 地址不能含空白字符"
        else if (h.contains("/")) errors += "SSH 地址不能含 /：只填 IP 或主机名"
        if (port !in 1..65535) errors += "SSH 端口必须在 1~65535 之间"
        if (port == InstallCommand.HAND_PORT) errors += "SSH 端口不能是 Hand 端口（18743）"
        if (username.isBlank()) errors += "SSH 用户名不能为空"
        return errors
    }
}
