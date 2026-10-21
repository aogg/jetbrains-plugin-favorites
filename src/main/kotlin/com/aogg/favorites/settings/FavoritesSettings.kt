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
 * 阶段说明：hideNativeBookmarks 本阶段只做 UI 与持久化，
 * 真正隐藏原生书签入口（ActionManager.replaceAction 等）在原生集成版本实现。
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
