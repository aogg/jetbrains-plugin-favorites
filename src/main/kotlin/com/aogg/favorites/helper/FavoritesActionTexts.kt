package com.aogg.favorites.helper

import com.aogg.favorites.settings.FavoritesSettings

/**
 * 菜单动作显示名统一出口（1.8.0）
 *
 * 背景：未开启「隐藏 PhpStorm 原生书签界面」（[FavoritesSettings.State.hideNativeBookmarks]=false）时，
 * 原生「书签」菜单组 / 行号区书签组 / 原生 ToggleBookmark* 动作会与插件菜单项同屏出现，
 * 光看「添加收藏」「添加助记收藏」分不清是谁家的。本对象按开关决定要不要给插件菜单项名称加后缀，
 * 让「隐藏」与「不隐藏」两种情况下菜单都无歧义：
 * - 开启隐藏（默认）：用短名 `添加收藏`（原生界面已被隐藏，不存在混淆）；
 * - 关闭隐藏：加后缀 `添加收藏（Favorites）`（与原生「书签」项区分）。
 *
 * 使用约定：各动作在 `update(e)` 里用 [of] 设置 [com.intellij.openapi.actionSystem.Presentation.text]，
 * 不要只改构造参数/`templatePresentation.text`——菜单每次打开都会 `update`，这样切换设置后立刻生效，
 * 无需重启 IDE；`templatePresentation` 保留 base 文案，供工具窗口按钮等非菜单入口复用。
 */
object FavoritesActionTexts {

    /**
     * 插件名后缀（中文全角括号，与 IDE 中文菜单风格一致）
     */
    const val PLUGIN_SUFFIX = "（Favorites）"

    /**
     * 读取「隐藏 PhpStorm 原生书签界面」开关（读取失败按默认值 true 处理，即不加后缀）
     *
     * @return true=原生书签界面已隐藏（菜单用短名）；false=原生书签界面可见（菜单加插件名后缀）
     */
    fun nativeBookmarksHidden(): Boolean =
        runCatching { FavoritesSettings.getInstance().state.hideNativeBookmarks }.getOrDefault(true)

    /**
     * 纯函数版本：按显式传入的开关值计算显示名（供单测与无 IDE 环境使用）
     *
     * @param base base 文案（如「添加收藏」）
     * @param nativeBookmarksHidden true=短名；false=base + 插件名后缀
     * @return 菜单显示文案
     */
    fun of(base: String, nativeBookmarksHidden: Boolean): String =
        if (nativeBookmarksHidden) base else base + PLUGIN_SUFFIX

    /**
     * 按当前设置计算菜单显示名（从 [FavoritesSettings] 读取开关）
     *
     * @param base base 文案（如「添加收藏」）
     * @return 菜单显示文案
     */
    fun of(base: String): String = of(base, nativeBookmarksHidden())
}
