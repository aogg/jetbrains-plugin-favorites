package com.aogg.favorites.rules

import com.aogg.favorites.model.MatchRule
import com.aogg.favorites.model.RuleType
import com.intellij.openapi.project.Project

/**
 * 远程文件夹/远程文件规则执行器（1.8.1 新增）：
 * - 复用 [SshRuleExecutor] 的本机 ssh 命令公共通道（BatchMode、ConnectTimeout、超时、
 *   错误信息尾部截断），v1 仅支持密钥/agent 认证（与 SSH 命令规则口径一致）；
 * - SSH_DIR：对规则 sshRemotePath 执行 `ls -1aAp` 列远端目录，产出
 *   [RuleItem.RemoteFileItem]（目录在前、名称不区分大小写排序，达 limit 截断）；
 * - SSH_FILE：对 sshRemotePath 执行 `ls -1ad` 取条目本体，产出单个
 *   [RuleItem.RemoteFileItem]（远端不存在时随退出码抛错，stderr 尾部进异常信息）；
 * - 树懒加载与浏览对话框直接复用 [listRemoteDir]/[readRemoteFile]（不经规则结果缓存）。
 *
 * ls 选项说明：`-1` 单列；`-a` 含隐藏项（`.`/`..` 由 [parseLsOutput] 过滤）；`-p` 目录制尾部加 `/`
 * 作为目录标记；`-d` 只列条目本体不进入目录。
 */
class SshDirRuleExecutor : RuleExecutor {

    companion object {
        /** ssh 命令整体超时（毫秒，与 SshRuleExecutor 一致） */
        const val LS_TIMEOUT_MS = SshRuleExecutor.SSH_TIMEOUT_MS

        /** 查看远端文件内容的最大读取字节数（防止 cat 巨型文件撑爆内存） */
        const val READ_FILE_MAX_BYTES = 256 * 1024

        /**
         * 归一远端路径（纯函数，便于单测）：去首尾空白、`\` 归一为 `/`、连续 `/` 折叠、
         * 去尾部 `/`（根 `/` 保留）；`~` 前缀原样保留交由远端 shell 展开
         *
         * @param raw 原始输入
         * @return 归一路径（空串表示无效）
         */
        @JvmStatic
        fun normalizeRemotePath(raw: String): String {
            // ////////////////  校验参数  start  ///////////////////////////////////////////
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            // ////////////////  校验参数  end  /////////////////////////////////////////////

            // ////////////////  返回：分隔符归一 + 连续斜杠折叠 + 去尾部斜杠  start  ///////////
            val collapsed = trimmed.replace('\\', '/').replace(Regex("/{2,}"), "/")
            val stripped = collapsed.removeSuffix("/")
            return stripped.ifEmpty { "/" }
            // ////////////////  返回  end  ///////////////////////////////////////////////
        }

        /**
         * 拼接父子远端路径（纯函数）：父为根 `/` 时直接 `/name`，否则 `parent/name`
         *
         * @param parent 父目录路径（已归一）
         * @param name 子条目名（不含 `/`）
         */
        @JvmStatic
        fun joinRemotePath(parent: String, name: String): String =
            if (parent == "/") "/$name" else "$parent/$name"

        /**
         * 单引号 shell 转义（纯函数）：包裹单引号，内部单引号按 `'\''` 转义，防远端命令注入
         *
         * @param arg 待转义参数（远端路径/文件名）
         */
        @JvmStatic
        fun shellQuote(arg: String): String = "'${arg.replace("'", "'\\''")}'"

        /**
         * 远端路径转 ls 参数（纯函数）：`~`/`~/x` 前缀不加引号（保留远端 shell 的家目录展开），
         * 其余整体单引号转义
         *
         * @param path 已归一远端路径
         */
        @JvmStatic
        fun quotedRemotePath(path: String): String =
            when {
                path == "~" -> "~"
                path.startsWith("~/") -> "~/" + shellQuote(path.removePrefix("~/"))
                else -> shellQuote(path)
            }

        /**
         * 解析 `ls -1aAp` 输出为远端条目列表（纯函数，便于单测）：
         * 尾部 `/` 标记目录；过滤空行与 `.`/`..`；子条目路径按父路径拼接；达 limit 截断
         *
         * @param parentPath 父目录路径（已归一）
         * @param stdout ls 标准输出全文
         * @param limit 条数上限（<=0 返回空列表）
         */
        @JvmStatic
        fun parseLsOutput(parentPath: String, stdout: String, limit: Int): List<RuleItem.RemoteFileItem> {
            // ////////////////  校验参数  start  ///////////////////////////////////////////
            if (limit <= 0) return emptyList()
            // ////////////////  校验参数  end  /////////////////////////////////////////////

            // ////////////////  返回：逐行转条目（过滤 . / ..）+ 截断  start  /////////////////
            return SshRuleExecutor.splitOutputLines(stdout)
                .mapNotNull { line ->
                    if (line.isEmpty() || line == "." || line == "..") return@mapNotNull null
                    val isDir = line.endsWith("/")
                    val name = if (isDir) line.removeSuffix("/") else line
                    if (name.isEmpty()) return@mapNotNull null
                    RuleItem.RemoteFileItem(joinRemotePath(parentPath, name), isDir)
                }
                .take(limit)
            // ////////////////  返回  end  ///////////////////////////////////////////////
        }

        /**
         * 远端条目排序（纯函数）：目录在前，名称不区分大小写（与本地目录懒加载排序口径一致）
         *
         * @param items 待排序条目
         */
        @JvmStatic
        fun sortRemoteEntries(items: List<RuleItem.RemoteFileItem>): List<RuleItem.RemoteFileItem> =
            items.sortedWith(
                compareBy({ !it.isDir }, { it.path.substringAfterLast('/').lowercase() })
            )

        /**
         * ls 远端目录为条目列表（后台线程调用）：
         * `ls -1aAp <path>` 经 [SshRuleExecutor.runSshCommand] 执行，退出码非 0/超时抛
         * IllegalStateException（message 带 stderr 尾部）；结果目录在前排序后按 limit 截断
         *
         * @param project 项目
         * @param rule SSH_DIR/SSH_FILE 规则（提供连接目标与 limit）
         * @param path 待列目录远端路径（内部归一）
         * @return 条目列表（目录在前、名称不区分大小写）
         */
        @JvmStatic
        fun listRemoteDir(project: Project, rule: MatchRule, path: String): List<RuleItem.RemoteFileItem> {
            // ////////////////  校验参数：路径非空 + limit 收敛  start  ///////////////////////
            val normalized = normalizeRemotePath(path)
            if (normalized.isEmpty()) {
                throw IllegalArgumentException("远程路径为空，请编辑规则填写远端目录")
            }
            val limit = rule.limit.coerceIn(1, SshRuleExecutor.MAX_RESULT_LIMIT)
            // ////////////////  校验参数  end  ////////////////////////////////////////////

            // ////////////////  核心逻辑：公共 ssh 通道执行 ls + 解析排序  start  //////////////
            val target = SshRuleExecutor.resolveTarget(project, rule)
            val output = SshRuleExecutor.runSshCommand(
                target,
                "ls -1aAp ${quotedRemotePath(normalized)}",
                LS_TIMEOUT_MS
            )
            return sortRemoteEntries(parseLsOutput(normalized, output.stdout, limit))
            // ////////////////  核心逻辑  end  ////////////////////////////////////////////
        }

        /**
         * 探测远端路径本体（SSH_FILE 规则执行；后台线程调用）：
         * `ls -1ad <path>` 一次拿到存在性/目录标记；不存在/无权限时随退出码抛
         * IllegalStateException（stderr 尾部含 ssh/远端原因）
         *
         * @param project 项目
         * @param rule SSH_FILE 规则
         * @param path 远端路径（内部归一）
         * @return 单条目（isDir 按尾部 `/` 判定）
         */
        @JvmStatic
        fun statRemotePath(project: Project, rule: MatchRule, path: String): RuleItem.RemoteFileItem {
            // ////////////////  校验参数：路径非空  start  ///////////////////////////////////
            val normalized = normalizeRemotePath(path)
            if (normalized.isEmpty()) {
                throw IllegalArgumentException("远程路径为空，请编辑规则填写远端文件")
            }
            // ////////////////  校验参数  end  ////////////////////////////////////////////

            // ////////////////  核心逻辑：ls -1ad 探测本体 + 解析首行  start  /////////////////
            val target = SshRuleExecutor.resolveTarget(project, rule)
            val output = SshRuleExecutor.runSshCommand(
                target,
                "ls -1ad ${quotedRemotePath(normalized)}",
                LS_TIMEOUT_MS
            )
            val line = SshRuleExecutor.splitOutputLines(output.stdout).firstOrNull().orEmpty()
            if (line.isEmpty()) {
                throw IllegalStateException("远端路径不存在或不可读（${target.userHost()}）：$normalized")
            }
            return RuleItem.RemoteFileItem(normalized, line.endsWith("/"))
            // ////////////////  核心逻辑  end  ////////////////////////////////////////////
        }

        /**
         * 读取远端文件内容用于只读查看（后台线程调用）：
         * `head -c <上限> <path>` 拉取内容（不落项目内文件），超上限时尾部追加截断说明
         *
         * @param project 项目
         * @param rule SSH_DIR/SSH_FILE 规则（提供连接目标）
         * @param path 远端文件路径（内部归一）
         * @return 文件文本（可能带截断说明尾注）
         */
        @JvmStatic
        fun readRemoteFile(project: Project, rule: MatchRule, path: String): String {
            // ////////////////  校验参数：路径非空  start  ///////////////////////////////////
            val normalized = normalizeRemotePath(path)
            if (normalized.isEmpty()) {
                throw IllegalArgumentException("远程路径为空")
            }
            // ////////////////  校验参数  end  ////////////////////////////////////////////

            // ////////////////  核心逻辑：head -c 限量拉取 + 截断尾注  start  //////////////////
            val target = SshRuleExecutor.resolveTarget(project, rule)
            val output = SshRuleExecutor.runSshCommand(
                target,
                "head -c $READ_FILE_MAX_BYTES -- ${quotedRemotePath(normalized)}",
                LS_TIMEOUT_MS
            )
            val content = output.stdout
            return if (content.length >= READ_FILE_MAX_BYTES) {
                content + "\n\n…（内容超过 ${READ_FILE_MAX_BYTES / 1024}KB 已截断）"
            } else {
                content
            }
            // ////////////////  核心逻辑  end  ////////////////////////////////////////////
        }
    }

    override fun execute(project: Project, rule: MatchRule): List<RuleItem> {
        // ////////////////  核心逻辑：按规则类型分流（SSH_DIR=列目录；SSH_FILE=探测本体）  start
        return when (rule.type) {
            RuleType.SSH_DIR -> listRemoteDir(project, rule, rule.sshRemotePath)
            RuleType.SSH_FILE -> listOf(statRemotePath(project, rule, rule.sshRemotePath))
            else -> throw IllegalArgumentException("SshDirRuleExecutor 不支持规则类型 ${rule.type}")
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }
}
