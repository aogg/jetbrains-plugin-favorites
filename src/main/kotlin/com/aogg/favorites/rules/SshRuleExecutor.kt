package com.aogg.favorites.rules

import com.aogg.favorites.model.MatchRule
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * SSH 规则执行器（1.4.0）：
 * - 主机解析：sshHostAlias 非空 → [SshConfigReader.listHosts] 按别名查找（IDE SSH 配置与
 *   ~/.ssh/config 双通道），找不到抛「SSH 主机 xxx 不存在…」；别名空 → 用 sshManualHost/Port/User；
 * - 执行：本机 `ssh` 命令（BatchMode + ConnectTimeout + accept-new），经
 *   [ExecUtil.execAndGetOutput] 拿 stdout/stderr/退出码（全 runCatching）；
 * - 输出：stdout 按行拆分、去尾部空行、达 limit 截断 → [RuleItem.LineItem]；
 * - 退出码非 0 或超时：throw 异常，message 带 stderr 尾部几行（[tailForMessage] 截到 ~300 字符），
 *   便于树节点 tooltip 展示原因。
 *
 * 输出说明：v1 走本机 ssh 命令，仅支持密钥/agent 认证（BatchMode 禁用任何交互提示），
 * 不支持交互式密码登录；远程命令多行文本整体作为单个 ssh 参数下发。
 */
class SshRuleExecutor : RuleExecutor {

    companion object {
        /** 单次执行结果条数硬上限（与 RegexRuleExecutor 一致） */
        const val MAX_RESULT_LIMIT = 10000

        /** ssh 命令整体超时（毫秒） */
        const val SSH_TIMEOUT_MS = 30000

        /** ssh 默认端口（非默认端口才追加 -p 参数） */
        const val DEFAULT_PORT = SshConfigReader.DEFAULT_PORT

        /** 失败信息里 stderr 保留的最大字符数 */
        private const val MAX_ERROR_TAIL_CHARS = 300

        private val log = Logger.getInstance(SshRuleExecutor::class.java)

        /**
         * 拆分 stdout 为行并去掉尾部空行（纯函数，便于单测）
         *
         * @param stdout 进程标准输出全文
         * @return 非尾部空行 filtered 的行列表（保留行内空白与中间空行）
         */
        @JvmStatic
        fun splitOutputLines(stdout: String): List<String> {
            val lines = stdout.replace("\r\n", "\n").split('\n')
            // split 会在结尾多出一个空串；再额外去掉尾部连续空行
            return lines.dropLastWhile { it.isEmpty() }
        }

        /**
         * stdout → 达 limit 截断的行列表（纯函数，便于单测）
         *
         * @param stdout 进程标准输出全文
         * @param limit 行数上限（<=0 视为 0，返回空列表）
         */
        @JvmStatic
        fun processOutput(stdout: String, limit: Int): List<String> {
            // ////////////////  校验参数  start  ///////////////////////////////////////////
            if (limit <= 0) return emptyList()
            // ////////////////  校验参数  end  /////////////////////////////////////////////

            // ////////////////  返回：拆行 + 去尾部空行 + 截断  start  ///////////////////////
            return splitOutputLines(stdout).take(limit)
            // ////////////////  返回  end  ///////////////////////////////////////////////
        }

        /**
         * 取文本尾部内容用于错误信息（纯函数，便于单测）：按行从尾部向前收集，累计不超过
         * maxChars 字符；空白文本返回占位说明
         *
         * @param text 原始文本（如 ssh stderr）
         * @param maxChars 最大字符数
         */
        @JvmStatic
        fun tailForMessage(text: String, maxChars: Int = MAX_ERROR_TAIL_CHARS): String {
            // ////////////////  校验参数：空文本占位  start  ///////////////////////////////
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return "（无错误输出）"
            // ////////////////  校验参数  end  ////////////////////////////////////////////

            // ////////////////  返回：尾部行累计至 maxChars  start  /////////////////////////
            val lines = trimmed.split('\n')
            val result = StringBuilder()
            for (line in lines.reversed()) {
                val candidate = (if (result.isEmpty()) line.trimEnd() else line.trimEnd() + "\n" + result)
                if (candidate.length > maxChars) {
                    if (result.isEmpty()) {
                        // 单行即超长：直接截尾部
                        return "…" + candidate.takeLast(maxChars)
                    }
                    break
                }
                result.clear()
                result.append(candidate)
            }
            return if (result.length < trimmed.length) "…$result" else result.toString()
            // ////////////////  返回  end  ///////////////////////////////////////////////
        }
    }

    override fun execute(project: Project, rule: MatchRule): List<RuleItem> {
        // ////////////////  校验参数：命令非空 + limit 收敛  start  ///////////////////////
        val command = rule.sshCommand.trim()
        if (command.isEmpty()) {
            throw IllegalArgumentException("远程命令为空，请编辑规则补充")
        }
        val limit = rule.limit.coerceIn(1, MAX_RESULT_LIMIT)
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  主机解析：别名通道 / 手填通道  start  /////////////////////////
        val alias = rule.sshHostAlias.trim()
        val host: String
        val port: Int
        val user: String
        if (alias.isNotEmpty()) {
            val entry = SshConfigReader.listHosts(project).firstOrNull { it.alias == alias }
                ?: throw IllegalArgumentException(
                    "SSH 主机 $alias 不存在，请检查 IDE SSH 配置或 ~/.ssh/config"
                )
            host = entry.hostName
            port = entry.port
            user = entry.user
        } else {
            host = rule.sshManualHost.trim()
            port = rule.sshManualPort
            user = rule.sshManualUser.trim()
        }
        if (host.isEmpty()) {
            throw IllegalArgumentException("SSH 主机为空，请编辑规则选择别名或填写主机")
        }
        // ////////////////  主机解析  end  ///////////////////////////////////////////

        // ////////////////  组装命令行：ssh [opts] [-p port] [user@]host command  start  ////
        val commandLine = GeneralCommandLine("ssh")
        commandLine.addParameters("-o", "BatchMode=yes")
        commandLine.addParameters("-o", "ConnectTimeout=8")
        commandLine.addParameters("-o", "StrictHostKeyChecking=accept-new")
        if (port in 1..65535 && port != DEFAULT_PORT) {
            commandLine.addParameters("-p", port.toString())
        }
        commandLine.addParameter(if (user.isEmpty()) host else "$user@$host")
        commandLine.addParameter(command)
        // ////////////////  组装命令行  end  /////////////////////////////////////////

        // ////////////////  核心逻辑：执行 + 输出整形/失败抛错  start  /////////////////////
        val target = if (user.isEmpty()) host else "$user@$host"
        val output = runCatching { ExecUtil.execAndGetOutput(commandLine, SSH_TIMEOUT_MS) }.getOrElse { error ->
            log.warn("SSH 规则执行进程异常（$target）", error)
            throw IllegalStateException(
                "SSH 执行失败（$target）：${tailForMessage(error.message ?: error.javaClass.simpleName)}",
                error
            )
        }
        if (output.exitCode != 0) {
            val timeoutHint = if (output.isTimeout) "（连接或命令超时）" else ""
            throw IllegalStateException(
                "SSH 命令退出码 ${output.exitCode}$timeoutHint（$target）：${tailForMessage(output.stderr)}"
            )
        }
        return processOutput(output.stdout, limit).map { RuleItem.LineItem(it) }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }
}
