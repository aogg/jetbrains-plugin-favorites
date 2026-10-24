package com.aogg.favorites.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Favorites 应用级设置（持久化）
 *
 * hideNativeBookmarks 默认开启（1.4.x 语义收窄：只控制「隐藏原生书签界面」，不影响快捷键归属）：
 * - 原生书签快捷键（F11/Ctrl+F11/Shift+F11/Alt+2/Ctrl+0-9 等）与 Ctrl+点击行号区手势
 *   由 NativeBookmarkActionCustomizer / FavoritesGutterGestureListener 无条件接管；
 * - 开启时额外隐藏原生书签界面：原生 Bookmarks 工具窗口、残余原生动作
 *   （ToggleBookmark0-9/A-Z、ShowTypeBookmarks）与原生动作组；
 * - apply 变化时由 FavoritesNativeIntegration.applyOrRestore() 立即生效/恢复界面显隐。
 */
@State(
    name = "FavoritesSettings",
    storages = [Storage(value = "favorites.settings.xml")]
)
@Service
class FavoritesSettings : PersistentStateComponent<FavoritesSettings.State> {

    /**
     * 设置状态
     */
    class State {
        /**
         * 是否隐藏 PhpStorm 原生书签界面（默认开启）
         * 1.4.x 语义收窄：只控制原生 Bookmarks 工具窗口与残余原生动作/动作组的可见性；
         * 原生书签快捷键与 Ctrl+点击行号区手势始终由本插件接管，不受本开关影响
         */
        var hideNativeBookmarks: Boolean = true

        /**
         * 添加收藏时上次选择的作用域（1.2.0 新增）：
         * 存 FavoriteScope 枚举名（PROJECT/GLOBAL），默认 PROJECT；
         * 弹出"项目收藏/全局收藏"二选一时默认高亮该作用域，选择后记住
         */
        var lastAddScope: String = "PROJECT"
    }

    private var state = State()

    override fun getState(): State {
        return state
    }

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    /**
     * 获取上次添加收藏时选择的作用域（异常值保守回退 PROJECT）
     */
    fun getLastAddScope(): com.aogg.favorites.model.FavoriteScope =
        com.aogg.favorites.model.FavoriteScope.fromNameOrDefault(state.lastAddScope)

    /**
     * 记住本次添加收藏选择的作用域（下次弹出选择时默认高亮）
     */
    fun setLastAddScope(scope: com.aogg.favorites.model.FavoriteScope) {
        state.lastAddScope = scope.name
    }

    companion object {
        /**
         * 获取应用级实例
         */
        @JvmStatic
        fun getInstance(): FavoritesSettings =
            ApplicationManager.getApplication().getService(FavoritesSettings::class.java)
    }
}
