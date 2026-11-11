package com.aogg.favorites.rules

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.Locale

/**
 * SSH 主机条目（规则编辑对话框别名下拉与 SSH 执行器共用）
 *
 * @param alias 主机别名（ssh config 的 Host 名 / IDE SSH 配置名）
 * @param hostName 实际主机地址
 * @param port 端口（默认 22）
 * @param user 登录用户名（可能为空串）
 */
data class SshHostEntry(
    val alias: String,
    val hostName: String,
    val port: Int,
    val user: String
)

/**
 * 现有 SSH 配置读取器（1.4.0 SSH 规则新增），双通道读取尽力而为：
 * - 通道一（首选）：IDE SSH 配置（Settings→Tools→SSH Configurations）。平台类
 *   `com.intellij.ssh.config.unified.SshConfigManager` 跨版本可能挪包，故统一纯反射访问
 *   （候选类名逐个尝试），任何失败静默降级通道二，不影响其它功能；
 * - 通道二（兜底）：解析 `~/.ssh/config`（解析逻辑抽成纯函数 [parse]，不碰平台 API，可独立单测；
 *   读文件失败返回空列表）。1.8.1 加固：
 *   - 家目录定位在 `user.home` 之外增加 `USERPROFILE`/`HOME` 环境变量兜底
 *     （Windows 下 IDEA 进程的 user.home 可能被改写）；
 *   - 读取层支持 `Include` 指令（相对 ~/.ssh/ 与绝对路径、文件名段 * ? 简单通配展开、
 *     防环深度限制），多主机用户拆分文件也能全部列出；[parse] 保持纯函数（Include 行照旧忽略）。
 *
 * [listHosts] 通道一结果在前，通道二按别名去重补充（同名以通道一为准）。
 */
object SshConfigReader {

    private val log = Logger.getInstance(SshConfigReader::class.java)

    /** ssh config 默认端口 */
    const val DEFAULT_PORT = 22

    /** Include 防环最大递归深度（ssh config 多文件互引时不至于栈溢出/死循环） */
    private const val MAX_INCLUDE_DEPTH = 5

    /** 通道一候选类名（233 已确认 com.intellij.ssh.config.unified.SshConfigManager；后续版本挪包时逐个兜底） */
    private val SSH_MANAGER_CLASS_NAMES = listOf(
        "com.intellij.ssh.config.unified.SshConfigManager",
        "com.intellij.ssh.SshConfigManager",
        // 1.8.1 补充跨版本候选：unified 包出现前的旧版管理器类名
        "com.intellij.ssh.config.SshConfigManager"
    )

    /**
     * 列出全部可用 SSH 主机（IDE 配置在前，~/.ssh/config 去重补充）
     *
     * @param project 项目
     * @return 主机条目列表（读不到任何配置时为空列表，不抛异常）
     */
    @JvmStatic
    fun listHosts(project: Project): List<SshHostEntry> {
        // ////////////////  通道一：IDE SSH 配置（失败静默降级）  start  //////////////////
        val ideHosts = readIdeSshConfigs(project)
        // ////////////////  通道一  end  //////////////////////////////////////////////

        // ////////////////  通道二：~/.ssh/config 解析  start  //////////////////////////
        val fileHosts = readUserSshConfigFile()
        // ////////////////  通道二  end  //////////////////////////////////////////////

        // ////////////////  合并：通道一优先，通道二按别名去重补充  start  //////////////////
        val merged = LinkedHashMap<String, SshHostEntry>()
        ideHosts.forEach { entry -> merged[entry.alias] = entry }
        fileHosts.forEach { entry -> merged.putIfAbsent(entry.alias, entry) }
        return merged.values.toList()
        // ////////////////  合并  end  ///////////////////////////////////////////////
    }

    /**
     * 解析 ssh config 文本为条目列表（纯函数，不碰平台 API，便于单元测试）
     *
     * 解析约定：
     * - 支持 `Host`（一行多个别名）、`HostName`、`Port`（默认 [DEFAULT_PORT]、非法值忽略）、`User`；
     * - key 大小写不敏感；`#` 起视为注释；`Include` 及其它未知 key 忽略
     *   （Include 的文件展开由读取层 [readConfigWithIncludes] 在解析前完成，本函数不碰文件系统）；
     * - 含 `*`/`?` 通配的 Host 条目整块跳过；
     * - 同一别名后值覆盖前值（LinkedHashMap 语义）；
     * - 未写 HostName 的条目以别名充当主机地址。
     *
     * @param content ssh config 文件全文
     * @return 条目列表（内容为空/无有效块时为空列表）
     */
    @JvmStatic
    fun parse(content: String): List<SshHostEntry> {
        // ////////////////  逐行解析：Host 起块，key/value 覆盖  start  //////////////////
        val result = LinkedHashMap<String, SshHostEntry>()
        var pendingAliases = mutableListOf<String>()
        var hostName: String? = null
        var port = DEFAULT_PORT
        var user = ""

        fun flushBlock() {
            if (pendingAliases.isNotEmpty()) {
                for (alias in pendingAliases) {
                    // 通配条目（*/?）仅用于匹配模式，不作为可执行主机，整块跳过
                    if (alias.contains('*') || alias.contains('?')) continue
                    result[alias] = SshHostEntry(
                        alias = alias,
                        hostName = hostName?.takeIf { it.isNotBlank() } ?: alias,
                        port = port,
                        user = user
                    )
                }
            }
            pendingAliases = mutableListOf()
            hostName = null
            port = DEFAULT_PORT
            user = ""
        }

        content.lineSequence().forEach { rawLine ->
            // # 起视为注释（含行内注释）；空行跳过
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val tokens = line.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return@forEach

            val key = tokens[0].lowercase(Locale.ROOT)
            when (key) {
                "host" -> {
                    flushBlock()
                    pendingAliases = tokens.drop(1).filter { it.isNotBlank() }.toMutableList()
                }
                "hostname" -> hostName = tokens.getOrNull(1)?.trim('"')
                "port" -> port = tokens.getOrNull(1)?.trim('"')?.toIntOrNull() ?: port
                "user" -> user = tokens.getOrNull(1)?.trim('"').orEmpty()
                // Include/IdentityFile 等其余 key 一律忽略
            }
        }
        flushBlock()
        // ////////////////  逐行解析  end  ////////////////////////////////////////////

        // ////////////////  返回：LinkedHashMap 保序去重后的条目  start  ///////////////////
        return result.values.toList()
        // ////////////////  返回  end  ///////////////////////////////////////////////
    }

    /** 空白分隔（key/value 之间任意空白） */
    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * 通道一：纯反射读取 IDE SSH 配置（类名/方法名跨版本可能变化，任何一步失败返回空列表）
     */
    private fun readIdeSshConfigs(project: Project): List<SshHostEntry> {
        return runCatching {
            // ////////////////  校验参数：候选类名定位管理器  start  /////////////////////////
            val managerClass = SSH_MANAGER_CLASS_NAMES.firstNotNullOfOrNull { name ->
                runCatching { Class.forName(name) }.getOrNull()
            } ?: return@runCatching emptyList()
            // ////////////////  校验参数  end  /////////////////////////////////////////

            // ////////////////  核心逻辑：getConfigs() → SshHostEntry  start  //////////////
            val manager = managerClass.getMethod("getInstance", Project::class.java).invoke(null, project)
            val configs = managerClass.getMethod("getConfigs").invoke(manager) as? List<*>
                ?: return@runCatching emptyList()
            configs.mapNotNull { config ->
                runCatching {
                    val name = config?.javaClass?.getMethod("getName")?.invoke(config) as? String
                    if (name.isNullOrBlank()) {
                        return@mapNotNull null
                    }
                    val host = config.javaClass.getMethod("getHost").invoke(config) as? String ?: ""
                    val port = config.javaClass.getMethod("getPort").invoke(config) as? Int ?: DEFAULT_PORT
                    val user = config.javaClass.getMethod("getUsername").invoke(config) as? String ?: ""
                    SshHostEntry(name, host.ifBlank { name }, port, user)
                }.getOrNull()
            }
            // ////////////////  核心逻辑  end  /////////////////////////////////////////
        }.onFailure { log.warn("读取 IDE SSH 配置失败，降级解析 ~/.ssh/config", it) }
            .getOrDefault(emptyList())
    }

    /**
     * 通道二：读取并解析 ~/.ssh/config（文件不存在/读取失败返回空列表）；
     * 读取前先按 Include 指令展开引用文件（1.8.1），展开后整体交给 [parse]
     */
    private fun readUserSshConfigFile(): List<SshHostEntry> {
        // ////////////////  核心逻辑：定位配置文件失败静默返回空  start  ///////////////////
        val configFile = resolveUserSshConfigFile() ?: return emptyList()
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：Include 展开 + 解析（任一步失败静默返回空）  start  ///
        return runCatching {
            val content = readConfigWithIncludes(configFile, 0, HashSet())
            if (content.isBlank()) emptyList() else parse(content)
        }.onFailure { log.warn("解析 ${configFile.absolutePath} 失败", it) }
            .getOrDefault(emptyList())
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 定位 ~/.ssh/config：家目录候选依次探测（user.home 失效时兜底 USERPROFILE/HOME 环境变量），
     * 第一个存在 config 文件的家目录胜出；都拿不到返回 null
     */
    private fun resolveUserSshConfigFile(): File? {
        // ////////////////  核心逻辑：候选家目录逐个探测  start  /////////////////////////
        for (home in homeCandidates()) {
            val candidate = File(home, ".ssh${File.separatorChar}config")
            if (candidate.isFile) return candidate
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
        return null
    }

    /**
     * 家目录候选列表（去重保序）：user.home 系统属性 → USERPROFILE 环境变量（Windows 兜底）
     * → HOME 环境变量（Linux/macOS 兜底）
     */
    private fun homeCandidates(): List<File> =
        listOfNotNull(System.getProperty("user.home"), System.getenv("USERPROFILE"), System.getenv("HOME"))
            .filter { it.isNotBlank() }
            .map(::File)
            .distinct()

    /**
     * 读取 ssh config 文本并展开 Include 指令（1.8.1）：
     * - Include 行按 token 展开（一行可多个路径），把被引文件内容原位拼接进结果；
     * - 相对路径相对 ~/.ssh/（即引用文件所在目录）解析，绝对路径直用，`~` 展开为家目录；
     * - 文件名段支持 `*`/`?` 简单通配（按名称排序展开；匹配到目录时跳过，仅展开普通文件）；
     * - 防环：递归深度超 [MAX_INCLUDE_DEPTH] 或同一文件重复引用时跳过（记日志）；
     * - 单个被引文件读取失败跳过，不影响主文件与其余 Include。
     *
     * @param file 当前读取的 ssh config 文件
     * @param depth 当前递归深度（主文件为 0）
     * @param visited 已读取文件的规范路径集合（防环）
     * @return 展开后的完整配置文本（读取失败返回空串）
     */
    private fun readConfigWithIncludes(file: File, depth: Int, visited: MutableSet<String>): String {
        // ////////////////  校验参数：防环（深度上限 + 已读文件跳过）  start  //////////////
        val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        if (!visited.add(canonical)) {
            log.info("ssh config Include 检测到循环引用或重复引用，跳过：$canonical")
            return ""
        }
        if (depth > MAX_INCLUDE_DEPTH) {
            log.warn("ssh config Include 嵌套深度超过 $MAX_INCLUDE_DEPTH，跳过：$canonical")
            return ""
        }
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：逐行读入，Include 行替换为被引文件内容  start  ///////
        val content = runCatching { file.readText() }.getOrElse {
            log.warn("读取 ssh config 失败：${file.absolutePath}", it)
            return ""
        }
        val result = StringBuilder()
        content.lineSequence().forEach { rawLine ->
            val stripped = rawLine.substringBefore('#').trim()
            val tokens = stripped.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
            if (tokens.size >= 2 && tokens[0].lowercase(Locale.ROOT) == "include") {
                for (pattern in tokens.drop(1)) {
                    for (included in expandIncludePattern(pattern, file)) {
                        result.append(readConfigWithIncludes(included, depth + 1, visited)).append('\n')
                    }
                }
            } else {
                result.append(rawLine).append('\n')
            }
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////

        // ////////////////  返回：展开后的配置全文  start  ///////////////////////////////
        return result.toString()
        // ////////////////  返回  end  ///////////////////////////////////////////////
    }

    /**
     * 展开 Include 单个路径 token 为具体文件列表：
     * `~`/`~/` 前缀展开为家目录；相对路径相对引用文件所在目录（约定即 ~/.ssh/）；
     * 文件名段含 `*`/`?` 时按简单通配列目录展开（仅普通文件、按名称排序）；其余原样返回
     *
     * @param pattern Include 行里的单个路径 token（已按空白切分）
     * @param includingFile 发起 Include 的文件（相对路径基准 = 其父目录）
     * @return 展开后的文件列表（无法解析/列目录失败返回空列表）
     */
    private fun expandIncludePattern(pattern: String, includingFile: File): List<File> {
        // ////////////////  准备数据：~ 展开 + 相对路径基准  start  //////////////////////
        var path = pattern.trim().trim('"')
        val home = homeCandidates().firstOrNull()
        if (path == "~") {
            return home?.let { listOf(it) } ?: emptyList()
        }
        if (path.startsWith("~/") && home != null) {
            path = File(home, path.substring(2)).path
        }
        val resolved = if (File(path).isAbsolute) {
            File(path)
        } else {
            includingFile.parentFile?.let { File(it, path) } ?: return emptyList()
        }
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：文件名段通配展开，非通配直接返回  start  /////////////
        val parent = resolved.parentFile
        val name = resolved.name
        if (parent == null || !(name.contains('*') || name.contains('?'))) {
            return listOf(resolved)
        }
        val matcher = globToRegex(name)
        return runCatching {
            parent.listFiles { candidate -> matcher.matches(candidate.name) }
                ?.filter { it.isFile }
                ?.sortedBy { it.name }
                .orEmpty()
        }.onFailure { log.warn("展开 ssh config Include 通配失败：$pattern", it) }
            .getOrDefault(emptyList())
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 简单通配转正则（纯函数）：`*` → 任意串、`?` → 单字符、其余字符字面转义
     *
     * @param pattern 通配串（仅文件名段，不含路径分隔符，如 config.d 目录展开用的单个星号）
     * @return 匹配正则
     */
    private fun globToRegex(pattern: String): Regex = buildString {
        for (ch in pattern) {
            when (ch) {
                '*' -> append(".*")
                '?' -> append('.')
                else -> append(Regex.escape(ch.toString()))
            }
        }
    }.let(::Regex)
}
