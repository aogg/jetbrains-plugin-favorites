package com.aogg.favorites.rules

import com.aogg.favorites.model.RegexTarget

/**
 * 正则规则纯逻辑匹配器（不依赖任何平台 API，可独立单元测试）：
 * - 相对路径规范化（反斜杠→正斜杠、去前导斜杠）
 * - 正则编译（可选忽略大小写）
 * - 隐藏目录跳过判定（.git/.idea 等）
 * - 单条路径匹配（RegexTarget 过滤 + find() 语义）
 *
 * RegexRuleExecutor 与规则编辑对话框「测试」按钮均复用本对象，保证行为一致。
 */
object RegexRuleMatcher {

    /** 跳过的隐藏目录名前缀（以 . 开头即整棵子树跳过，如 .git/.idea/.svn/.gradle） */
    private const val SKIPPED_DIR_NAME_PREFIX = "."

    /**
     * 相对路径规范化：反斜杠→正斜杠、去首尾空白与前导斜杠
     *
     * @param path 原始路径
     * @return 规范化相对路径（'/' 分隔、无前导斜杠）
     */
    @JvmStatic
    fun normalizeRelativePath(path: String): String =
        path.replace('\\', '/').trim().trimStart('/')

    /**
     * 编译正则（caseInsensitive=true 追加 RegexOption.IGNORE_CASE）
     *
     * @param pattern 正则表达式（空串视为非法）
     * @param caseInsensitive 是否忽略大小写
     * @return 编译后的 Regex；无法编译返回 null（调用方转 ERROR/校验提示）
     */
    @JvmStatic
    fun compile(pattern: String, caseInsensitive: Boolean): Regex? {
        // ////////////////  校验参数  start  /////////////////////////////////////////
        if (pattern.isEmpty()) return null
        // ////////////////  校验参数  end  ///////////////////////////////////////////

        // ////////////////  返回：编译失败兜底返回 null  start  ////////////////////////
        return runCatching {
            if (caseInsensitive) Regex(pattern, setOf(RegexOption.IGNORE_CASE)) else Regex(pattern)
        }.getOrNull()
        // ////////////////  返回  end  ///////////////////////////////////////////////
    }

    /**
     * 目录名是否应跳过（隐藏目录：以 . 开头，遍历时整棵子树跳过）
     *
     * @param name 目录名
     * @return true=跳过
     */
    @JvmStatic
    fun isSkippedDirectoryName(name: String): Boolean = name.startsWith(SKIPPED_DIR_NAME_PREFIX)

    /**
     * 单条相对路径是否命中（先按 [RegexTarget] 过滤条目类型，再按 find() 语义匹配）
     *
     * @param regex 已编译正则
     * @param relativePath 相对项目根路径（'/' 分隔、无前导斜杠）
     * @param isFolder 条目是否为目录
     * @param target 匹配目标过滤
     * @return true=命中
     */
    @JvmStatic
    fun matches(regex: Regex, relativePath: String, isFolder: Boolean, target: RegexTarget): Boolean {
        // ////////////////  校验参数：按目标类型过滤  start  ///////////////////////////
        when (target) {
            RegexTarget.FILE -> if (isFolder) return false
            RegexTarget.FOLDER -> if (!isFolder) return false
            RegexTarget.ALL -> {}
        }
        // ////////////////  校验参数  end  ///////////////////////////////////////////

        // ////////////////  返回：find() 语义（部分匹配即命中）  start  /////////////////
        return regex.containsMatchIn(relativePath)
        // ////////////////  返回  end  ///////////////////////////////////////////////
    }
}
