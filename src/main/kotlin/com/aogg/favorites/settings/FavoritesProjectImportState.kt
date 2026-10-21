package com.aogg.favorites.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 项目级"原生书签导入"状态（持久化）
 *
 * 阶段说明：本阶段只建好持久化字段，阶段 B（原生书签导入/首次引导通知）使用：
 * - importedNative：本项目是否已完成原生书签导入
 * - dontAskAgain：首次引导是否选择"不再提示"
 */
@State(
    name = "FavoritesProjectImportState",
    storages = [Storage(value = "favorites.import.xml", roamingType = RoamingType.DISABLED)]
)
@Service(Service.Level.PROJECT)
class FavoritesProjectImportState : PersistentStateComponent<FavoritesProjectImportState.State> {

    /**
     * 项目级导入状态
     */
    class State {
        /**
         * 是否已导入原生书签
         */
        var importedNative: Boolean = false

        /**
         * 首次引导是否选择"不再提示"
         */
        var dontAskAgain: Boolean = false
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
         * 获取项目级实例
         */
        @JvmStatic
        fun getInstance(project: Project): FavoritesProjectImportState =
            project.getService(FavoritesProjectImportState::class.java)
    }
}
