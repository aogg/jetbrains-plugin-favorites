package com.aogg.favorites.rules

import com.aogg.favorites.model.MatchRule
import com.aogg.favorites.model.RegexTarget
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.util.Locale

/**
 * 正则规则执行器：从项目根（或规则根目录 regexBaseDir）遍历 VirtualFile，
 * 对「相对项目根、'/' 分隔、无前导斜杠」的路径做正则 find() 匹配。
 *
 * 实现约定：
 * - 纯 VFS 遍历（不碰 PSI、不需要 ReadAction），可在后台线程直接执行；
 * - 隐藏目录（.git/.idea 等以 . 开头）整棵子树跳过；
 * - 目录命中也作为结果条目（isFolder=true），并继续向下遍历子项；
 * - 结果数达 limit（硬上限 [MAX_RESULT_LIMIT]）即停止遍历；
 * - 遍历按 目录在前、文件在后（组内名称不区分大小写）排序，保证结果顺序稳定；
 * - 匹配逻辑复用纯逻辑对象 [RegexRuleMatcher]，可独立单元测试。
 */
class RegexRuleExecutor : RuleExecutor {

    companion object {
        /** 单次执行结果条数硬上限 */
        const val MAX_RESULT_LIMIT = 10000

        private val log = Logger.getInstance(RegexRuleExecutor::class.java)
    }

    override fun execute(project: Project, rule: MatchRule): List<RuleItem> {
        // ////////////////  校验参数：正则可编译 + 项目根/规则根目录可读  start  //////////
        val regex = RegexRuleMatcher.compile(rule.regexPattern, rule.caseInsensitive)
            ?: throw IllegalArgumentException("正则表达式无法编译：${rule.regexPattern}")
        val basePath = project.basePath?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("无法定位项目根目录")
        val projectRoot = runCatching {
            LocalFileSystem.getInstance().findFileByPath(basePath.replace('\\', '/'))
        }.getOrNull()?.takeIf { it.isValid && it.isDirectory }
            ?: throw IllegalStateException("项目根目录不存在或不可读：$basePath")
        val baseDir = RegexRuleMatcher.normalizeRelativePath(rule.regexBaseDir)
        val startDir = if (baseDir.isEmpty()) {
            projectRoot
        } else {
            runCatching { projectRoot.findFileByRelativePath(baseDir) }.getOrNull()
                ?.takeIf { it.isValid && it.isDirectory }
                ?: throw IllegalStateException("规则根目录不存在或不可读：$baseDir")
        }
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：深度优先遍历并收集命中条目  start  /////////////////
        val limit = rule.limit.coerceIn(1, MAX_RESULT_LIMIT)
        val results = mutableListOf<RuleItem>()
        traverse(startDir, "", regex, rule.regexTarget, limit, results)
        return results
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }

    /**
     * 深度优先遍历目录并收集命中条目（目录在前、文件在后，组内名称不区分大小写排序）
     * 单个目录/子项读取失败（VFS 竞态等）仅记日志，不影响其余遍历。
     *
     * @param current 当前目录
     * @param relativeDir 当前目录相对项目根路径（""=项目根）
     * @param regex 已编译正则
     * @param target 匹配目标过滤
     * @param limit 结果条数上限
     * @param results 收集结果（原地追加）
     */
    private fun traverse(
        current: VirtualFile,
        relativeDir: String,
        regex: Regex,
        target: RegexTarget,
        limit: Int,
        results: MutableList<RuleItem>
    ) {
        // ////////////////  校验参数：已达上限立即停止  start  ///////////////////////////
        if (results.size >= limit) return
        val children = runCatching { current.children }.getOrNull() ?: return
        // ////////////////  校验参数  end  ////////////////////////////////////////////

        // ////////////////  核心逻辑：逐子项匹配（目录递归/文件收集）  start  /////////////
        val sorted = children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
        for (child in sorted) {
            if (results.size >= limit) return
            val relativePath = if (relativeDir.isEmpty()) child.name else "$relativeDir/${child.name}"
            if (child.isDirectory) {
                // 隐藏目录（.git/.idea 等）整棵子树跳过
                if (RegexRuleMatcher.isSkippedDirectoryName(child.name)) continue
                // 目录命中也作为结果（isFolder=true），并继续向下遍历子项
                if (RegexRuleMatcher.matches(regex, relativePath, true, target)) {
                    results.add(RuleItem.FileItem(relativePath, true))
                    if (results.size >= limit) return
                }
                traverse(child, relativePath, regex, target, limit, results)
            } else {
                if (RegexRuleMatcher.matches(regex, relativePath, false, target)) {
                    results.add(RuleItem.FileItem(relativePath, false))
                }
            }
        }
        // ////////////////  核心逻辑  end  ////////////////////////////////////////////
    }
}
