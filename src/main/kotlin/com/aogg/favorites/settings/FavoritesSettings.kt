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
 * hideNativeBookmarks 默认开启：
 * - 开启时由 NativeBookmarkActionCustomizer（actionConfigurationCustomizer EP）接管原生书签动作，
 *   并在项目打开后隐藏原生 Bookmarks 工具窗口；
 * - apply 变化时由 FavoritesNativeIntegration.applyOrRestore() 立即生效/恢复。
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
         * 是否隐藏 PhpStorm 原生书签入口（默认开启）
         */
        var hideNativeBookmarks: Boolean = true
    }

    private var state = State()

    override fun getState(): State {
        return state
    }

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
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
