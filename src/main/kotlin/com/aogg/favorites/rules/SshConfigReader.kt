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
 *   读文件失败返回空列表）。
 *
 * [listHosts] 通道一结果在前，通道二按别名去重补充（同名以通道一为准）。
 */
object SshConfigReader {

    private val log = Logger.getInstance(SshConfigReader::class.java)

    /** ssh config 默认端口 */
    const val DEFAULT_PORT = 22

    /** 通道一候选类名（233 已确认 com.intellij.ssh.config.unified.SshConfigManager；后续版本挪包时逐个兜底） */
    private val SSH_MANAGER_CLASS_NAMES = listOf(
        "com.intellij.ssh.config.unified.SshConfigManager",
        "com.intellij.ssh.SshConfigManager"
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
     * - key 大小写不敏感；`#` 起视为注释；`Include` 及其它未知 key 忽略；
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
     * 通道二：读取并解析 ~/.ssh/config（文件不存在/读取失败返回空列表）
     */
    private fun readUserSshConfigFile(): List<SshHostEntry> {
        // ////////////////  核心逻辑：读文件失败静默返回空  start  /////////////////////////
        val content = runCatching {
            File(System.getProperty("user.home"), ".ssh${File.separatorChar}config").readText()
        }.getOrNull() ?: return emptyList()
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////

        // ////////////////  返回：纯函数解析  start  /////////////////////////////////////
        return runCatching { parse(content) }.getOrDefault(emptyList())
        // ////////////////  返回  end  ///////////////////////////////////////////////
    }
}
