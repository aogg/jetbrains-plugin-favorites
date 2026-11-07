package com.aogg.favorites.helper

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.project.ProjectManager
import java.util.Locale

/**
 * 收藏路径宏展开工具（1.7.3）
 *
 * 背景：原生书签导入进来的路径可能带未展开的 IDE 路径宏（如
 * `file://$APPLICATION_CONFIG_DIR$/consoles/xxx/console.sql`）。旧实现只识别 `$PROJECT_DIR$`，
 * 其它宏被当成「项目内相对路径」拼上项目根，得到一个必然不存在的绝对路径，双击收藏毫无反应。
 * 本工具把路径换算成按优先级排序的候选列表，解析侧逐个尝试即可同时兼容历史坏数据与新导入数据。
 *
 * 设计约束：
 * - [candidates] 只做字符串换算，必须能在无 IDE 运行时的纯 JVM 单测里安全调用：所有平台 API
 *   （[PathManager] / [PathMacroManager] / [ProjectManager]）都包在 runCatching 里，
 *   平台不可用/未初始化/类缺失（NoClassDefFoundError）时该宏保持原样，绝不抛异常。
 * - 返回的候选统一用正斜杠 `/`（VFS 风格），并按优先级去重。
 */
object BookmarkPathMacros {

    /** 宏名：项目根目录 */
    private const val MACRO_PROJECT_DIR = "PROJECT_DIR"

    /** 宏名：IDE 配置目录 */
    private const val MACRO_APPLICATION_CONFIG_DIR = "APPLICATION_CONFIG_DIR"

    /** 宏名：IDE 系统（缓存/索引）目录 */
    private const val MACRO_APPLICATION_SYSTEM_DIR = "APPLICATION_SYSTEM_DIR"

    /** 宏名：IDE 安装目录 */
    private const val MACRO_APPLICATION_HOME_DIR = "APPLICATION_HOME_DIR"

    /** 宏名：当前用户主目录 */
    private const val MACRO_USER_HOME = "USER_HOME"

    /**
     * 宏占位符：`$NAME$` 或 `$NAME`（尾随 `$` 可省略，大小写不敏感；NAME 只允许字母/数字/下划线）。
     * 第 1 组=宏名，第 2 组=可选尾随 `$`。
     */
    private val MACRO_PATTERN = Regex("\\$([A-Za-z_][A-Za-z0-9_]*)(\\$)?")

    /** 需要去掉的 url 协议前缀（原生书签 url 形如 file://$PROJECT_DIR$/src/a.php） */
    private val URL_PROTOCOL_PREFIXES = arrayOf("file://", "file:")

    /**
     * 路径 → 按优先级排序、去重后的候选路径列表（路径宏已尽量展开）
     *
     * 语义边界：
     * - rawPath 为 null/空白 → 返回空列表；
     * - 无宏路径 → 只返回 1 条（`\` 归一为 `/` 的原样路径）；
     * - 已知宏（[MACRO_PROJECT_DIR]/[MACRO_APPLICATION_CONFIG_DIR]/[MACRO_APPLICATION_SYSTEM_DIR]/
     *   [MACRO_APPLICATION_HOME_DIR]/[MACRO_USER_HOME]，带/不带尾随 `$`、大小写不敏感）→ 展开成绝对路径；
     *   `$PROJECT_DIR$` 在 projectBasePath 为空时无法展开，该宏保持原样留在候选里；
     * - 未知宏（如 `$MODULE_DIR$`）→ 先尝试平台 [PathMacroManager] 兜底展开；平台不可用时保持原样；
     * - 宏不在开头（如 `D:/proj/$APPLICATION_CONFIG_DIR$/consoles/a.sql`）→ 额外产出「从第一个宏
     *   位置截断后展开」的候选（`C:/Users/xxx/AppData/.../consoles/a.sql`）；
     * - 候选顺序：① 完整展开 → ② 截断展开（仅宏不在开头时）→ ③ 原样；重复项只保留最先出现的一条。
     *
     * @param rawPath 原始路径（可带 file:// 前缀、反斜杠、未展开宏）
     * @param projectBasePath 项目根路径（可空；为空时 `$PROJECT_DIR$` 无法展开）
     * @return 候选路径列表（已展开的绝对路径排在原样路径前面；空输入返回空列表）
     */
    fun candidates(rawPath: String?, projectBasePath: String?): List<String> {
        // ////////////////  校验参数：空路径没有候选  start  ////////////////////////////
        val path = normalizeInput(rawPath) ?: return emptyList()
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  准备数据：候选集合（LinkedHashSet 保序去重）  start  /////////
        val result = LinkedHashSet<String>()
        // ////////////////  准备数据  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑一：① 宏全部展开  start  /////////////////////////////
        result += expandMacros(path, projectBasePath)
        // ////////////////  核心逻辑一  end  ///////////////////////////////////////////

        // ////////////////  核心逻辑二：② 宏不在开头时，从第一个宏位置截断后展开  start  ////
        // 历史坏数据的典型形态：项目根 + 未展开宏混拼（D:/proj/$APPLICATION_CONFIG_DIR$/consoles/...），
        // 完整展开会得到 D:/proj/C:/Users/... 这种嵌套盘符的无效路径，必须再给一条截断展开的候选
        val macroIndex = firstMacroIndex(path)
        if (macroIndex > 0) {
            result += expandMacros(path.substring(macroIndex), projectBasePath)
        }
        // ////////////////  核心逻辑二  end  ///////////////////////////////////////////

        // ////////////////  核心逻辑三：③ 原样路径兜底（仅分隔符归一）  start  ////////////
        result += path
        // ////////////////  核心逻辑三  end  ///////////////////////////////////////////

        // ////////////////  返回：去空白项后的有序候选  start  //////////////////////////
        return result.filter { it.isNotBlank() }
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    /**
     * 原始路径归一：去首尾空白、去 file:// 协议前缀、反斜杠统一为正斜杠
     *
     * @param rawPath 原始路径
     * @return 归一后的路径；null/空白返回 null
     */
    private fun normalizeInput(rawPath: String?): String? {
        // ////////////////  校验参数  start  ///////////////////////////////////////////
        if (rawPath.isNullOrBlank()) return null
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：去协议前缀 + 分隔符归一  start  ////////////////////
        var path = rawPath.trim()
        for (prefix in URL_PROTOCOL_PREFIXES) {
            if (path.startsWith(prefix)) {
                path = path.substring(prefix.length)
                break
            }
        }
        return path.replace('\\', '/').trim()
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 展开一个路径字符串里的全部路径宏（已知宏直连查值，未知宏走平台兜底）
     *
     * @param path 已归一（正斜杠）的路径
     * @param projectBasePath 项目根路径（可空）
     * @return 展开后的路径（无法展开的宏保持原样，绝不为 null）
     */
    private fun expandMacros(path: String, projectBasePath: String?): String {
        // ////////////////  核心逻辑一：已知宏直接替换（大小写不敏感，兼容尾随 $ 省略）  start
        var expanded = MACRO_PATTERN.replace(path) { match ->
            val macroName = match.groupValues[1].uppercase(Locale.ROOT)
            knownMacroValue(macroName, projectBasePath) ?: match.value
        }
        // ////////////////  核心逻辑一  end  ///////////////////////////////////////////

        // ////////////////  核心逻辑二：仍残留宏时用平台 PathMacroManager 再兜底一次  start
        if (MACRO_PATTERN.containsMatchIn(expanded)) {
            expanded = expandByPlatformMacros(expanded) ?: expanded
        }
        // ////////////////  核心逻辑二  end  ///////////////////////////////////////////

        // ////////////////  返回：分隔符统一为正斜杠  start  ////////////////////////////
        return expanded.replace('\\', '/')
        // ////////////////  返回  end  /////////////////////////////////////////////////
    }

    /**
     * 已知宏名 → 展开值（未知宏返回 null，由平台兜底处理）
     *
     * @param macroName 宏名（调用方已转大写）
     * @param projectBasePath 项目根路径（可空）
     * @return 展开值（末尾不带分隔符）；无法展开返回 null
     */
    private fun knownMacroValue(macroName: String, projectBasePath: String?): String? = when (macroName) {
        MACRO_PROJECT_DIR -> projectBasePath?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        MACRO_APPLICATION_CONFIG_DIR -> platformPathValue { PathManager.getConfigPath() }
        MACRO_APPLICATION_SYSTEM_DIR -> platformPathValue { PathManager.getSystemPath() }
        MACRO_APPLICATION_HOME_DIR -> platformPathValue { PathManager.getHomePath() }
        MACRO_USER_HOME -> System.getProperty("user.home")?.trim()?.takeIf { it.isNotEmpty() }
        else -> null
    }

    /**
     * 平台路径取值兜底：平台未初始化/类缺失等任何 Throwable 都按「取不到」处理
     *
     * @param block 平台路径读取（如 PathManager.getConfigPath()）
     * @return 去尾部分隔符的路径；取不到返回 null
     */
    private fun platformPathValue(block: () -> String?): String? =
        runCatching(block).getOrNull()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    /**
     * 未知宏的平台兜底展开（PathMacroManager 支持 MODULE_DIR 等 IDE 内置宏）
     *
     * 注意：本方法只为「仍有宏残留」的路径调用，纯 JVM 单测里若平台类不可用会被 runCatching 吞掉
     * （返回 null，宏保持原样），不会阻断 [candidates] 的纯函数行为。
     *
     * @param path 仍带宏的路径
     * @return 平台展开结果；平台不可用返回 null
     */
    private fun expandByPlatformMacros(path: String): String? = runCatching {
        // 优先用已打开项目（项目级 MODULE_DIR 等宏更准确），没有项目时退回默认项目
        val projectManager = ProjectManager.getInstance()
        val project = projectManager.openProjects.firstOrNull() ?: projectManager.defaultProject
        PathMacroManager.getInstance(project).expandPath(path)
    }.getOrNull()

    /**
     * 路径中第一个路径宏的起始下标
     *
     * @param path 已归一（正斜杠）的路径
     * @return 宏起始下标；没有宏返回 -1
     */
    private fun firstMacroIndex(path: String): Int = MACRO_PATTERN.find(path)?.range?.first ?: -1
}
