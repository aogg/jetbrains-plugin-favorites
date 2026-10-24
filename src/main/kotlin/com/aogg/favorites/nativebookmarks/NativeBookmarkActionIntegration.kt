package com.aogg.favorites.nativebookmarks

import com.aogg.favorites.action.ScopedAddAction
import com.aogg.favorites.model.FavoriteScope
import com.aogg.favorites.settings.FavoritesSettings
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.ActionConfigurationCustomizer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.xmlb.XmlSerializerUtil

// =====================================================================================
// 应用级持久化状态：已替换/已隐藏的原生动作 id 集合
// =====================================================================================

/**
 * 应用级"原生书签动作接管"状态（持久化）
 *
 * - replacedActionIds：已用 replaceAction 接管的原生动作 id（供记录与防重复；接管为无条件，运行期不再恢复）
 * - hiddenActionIds：已被 templatePresentation.isVisible=false 隐藏的原生动作/组 id
 *   （随"隐藏原生书签界面"开关显隐，关闭开关时恢复）
 *
 * 注意：原动作实例只保存在内存（[NativeBookmarkActionService]）中，
 * [NativeBookmarkActionIntegration.restoreAll] 仅供记录回滚/卸载路径使用；
 * 重启后未替换的 id 自动跳过，无需处理。
 */
@State(
    name = "FavoritesNativeIntegrationState",
    storages = [Storage(value = StoragePathMacros.NON_ROAMABLE_FILE)]
)
@Service(Service.Level.APP)
class FavoritesNativeIntegrationState : PersistentStateComponent<FavoritesNativeIntegrationState.State> {

    class State {
        /**
         * 已被 replaceAction 接管的原生动作 id
         */
        var replacedActionIds: MutableList<String> = mutableListOf()

        /**
         * 已被隐藏（templatePresentation.isVisible=false）的原生动作/动作组 id
         */
        var hiddenActionIds: MutableList<String> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    companion object {
        @JvmStatic
        fun getInstance(): FavoritesNativeIntegrationState =
            ApplicationManager.getApplication().getService(FavoritesNativeIntegrationState::class.java)
    }
}

/**
 * 应用级服务：保存被替换前原生动作实例（仅内存，用于运行期恢复）
 */
@Service(Service.Level.APP)
class NativeBookmarkActionService {

    private val originalActions = HashMap<String, AnAction>()

    @Synchronized
    fun putOriginal(actionId: String, action: AnAction) {
        originalActions[actionId] = action
    }

    @Synchronized
    fun getOriginal(actionId: String): AnAction? = originalActions[actionId]

    @Synchronized
    fun clearOriginal(actionId: String) {
        originalActions.remove(actionId)
    }

    companion object {
        @JvmStatic
        fun getInstance(): NativeBookmarkActionService =
            ApplicationManager.getApplication().getService(NativeBookmarkActionService::class.java)
    }
}

// =====================================================================================
// 委托动作：单个委托类构造传参，按参数转发到对应的 Favorites 动作
// =====================================================================================

/**
 * 原生动作的委托动作：保留原生动作 id（用户键位映射不变），执行时转发到我们的动作
 *
 * @param nativeActionId 被接管的原生动作 id（仅用于展示文案兜底）
 * @param favoritesActionId 转发目标的 Favorites 动作 id
 * @param text 菜单/键位编辑器中的显示文案
 * @param fixedScope 非空时按固定作用域直接执行（转发目标需实现 [ScopedAddAction]），
 *   不弹作用域二选一；原生快捷键接管路径固定为当前项目作用域。null 时保持原转发行为
 */
class DelegateToFavoritesAction(
    private val nativeActionId: String,
    private val favoritesActionId: String,
    text: String?,
    private val fixedScope: FavoriteScope? = null
) : AnAction() {

    init {
        templatePresentation.text = text?.takeIf { it.isNotBlank() } ?: nativeActionId
        templatePresentation.description = "由 Favorites 插件接管（原动作：$nativeActionId）"
    }

    override fun actionPerformed(e: AnActionEvent) {
        runCatching {
            val actionManager = ActionManager.getInstance()
            val target = actionManager.getAction(favoritesActionId) ?: return

            // 固定作用域路径：不弹作用域选择，直接按当前项目作用域执行（添加类动作接管专用）
            if (fixedScope != null && target is ScopedAddAction) {
                val scopedEvent = AnActionEvent.createFromDataContext(
                    "FavoritesNativeAction",
                    null,
                    e.dataContext
                )
                target.performWithFixedScope(scopedEvent, fixedScope)
                return
            }

            // 普通转发路径：行为与原 Favorites 动作一致（含作用域二选一）
            val event = AnActionEvent.createFromDataContext(
                "FavoritesNativeAction",
                null,
                e.dataContext
            )
            target.actionPerformed(event)
        }.onFailure { log.warn("委托动作执行失败：$nativeActionId", it) }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    companion object {
        private val log = Logger.getInstance(DelegateToFavoritesAction::class.java)
    }
}

// =====================================================================================
// 动作接管/恢复核心逻辑
// =====================================================================================

/**
 * 原生书签动作接管（全部 null-safe + runCatching，隐藏不掉只记日志不报错）
 */
object NativeBookmarkActionIntegration {

    private val log = Logger.getInstance(NativeBookmarkActionIntegration::class.java)

    private const val OUR_PACKAGE_PREFIX = "com.aogg.favorites."
    private const val NATIVE_BOOKMARKS_GROUP_ID = "Bookmarks"
    private const val GOTO_BOOKMARK_PREFIX = "GotoBookmark"

    /**
     * 需要接管（replaceAction）的原生动作 id → 转发目标与执行方式
     * 语义（接管为无条件，与设置开关无关）：
     * ToggleBookmark→匿名收藏（固定当前项目作用域）；ToggleBookmarkWithMnemonic→助记符收藏（固定当前项目作用域）；
     * ShowBookmarks→收藏弹出列表；ActivateBookmarksToolWindow→收藏工具窗口开关
     */
    private val REPLACED_BY_FAVORITES_ACTION: Map<String, TakeoverSpec> = linkedMapOf(
        "ToggleBookmark" to TakeoverSpec("Favorites.AddAnonymousBookmark", FavoriteScope.PROJECT),
        "ToggleBookmarkWithMnemonic" to TakeoverSpec("Favorites.AddMnemonicBookmark", FavoriteScope.PROJECT),
        "ShowBookmarks" to TakeoverSpec("Favorites.ShowBookmarksPopup"),
        "ActivateBookmarksToolWindow" to TakeoverSpec("Favorites.ToggleBookmarksToolWindow")
    )

    /**
     * 接管规格
     *
     * @param favoritesActionId 转发目标的 Favorites 动作 id
     * @param fixedScope 非空时按固定作用域执行（不弹作用域二选一；原生快捷键路径固定为当前项目）
     */
    private class TakeoverSpec(val favoritesActionId: String, val fixedScope: FavoriteScope? = null)

    /**
     * 未替换、仅随开关隐藏的原生书签动作（id 不存在时自动跳过）
     * - ToggleBookmark0..9 / ToggleBookmarkA..Z：助记符独立切换动作
     * - ShowTypeBookmarks：原生助记符书签弹出（Ctrl+Shift+F11）
     */
    private val HIDDEN_ACTION_IDS: List<String> = buildList {
        ('0'..'9').forEach { add("ToggleBookmark$it") }
        ('A'..'Z').forEach { add("ToggleBookmark$it") }
        add("ShowTypeBookmarks")
    }

    /**
     * 接管原生动作（无条件执行；动作管理器初始化与设置页 apply 共用，幂等可重复调用）
     *
     * 只做 replaceAction 语义接管（id 不变 → 用户键位映射保留），不改动作可见性；
     * 设置开关只控制[隐藏原生界面][hideNativeActions]/[restoreHiddenActions]
     */
    fun applyTo(actionManager: ActionManager) {
        runCatching {
            val state = FavoritesNativeIntegrationState.getInstance().state
            val originals = NativeBookmarkActionService.getInstance()

            // 1) replaceAction 语义接管（id 不存在自动跳过；防重复替换）
            for ((nativeId, spec) in REPLACED_BY_FAVORITES_ACTION) {
                replaceWithDelegate(actionManager, originals, state, nativeId, spec)
            }
            // 2) GotoBookmark0..9（及存在时的 A..Z 版本）→ 对应助记符跳转（按 id 尾字符分发，作用域内先项目后全局）
            for (mnemonic in ('0'..'9') + ('A'..'Z')) {
                replaceWithDelegate(
                    actionManager, originals, state,
                    "$GOTO_BOOKMARK_PREFIX$mnemonic",
                    TakeoverSpec("Favorites.JumpToMnemonic.$mnemonic")
                )
            }
        }.onFailure { log.warn("接管原生书签动作失败（不影响其它功能）", it) }
    }

    /**
     * 隐藏原生书签界面动作（"隐藏原生书签界面"开关开启时调用）
     *
     * 隐藏未接管的残余原生动作（ToggleBookmark0-9/A-Z、ShowTypeBookmarks）与原生 Bookmarks 动作组
     */
    fun hideNativeActions(actionManager: ActionManager) {
        runCatching {
            val state = FavoritesNativeIntegrationState.getInstance().state
            for (hiddenId in HIDDEN_ACTION_IDS) {
                hideAction(actionManager, state, hiddenId)
            }
            hideAction(actionManager, state, NATIVE_BOOKMARKS_GROUP_ID)
        }.onFailure { log.warn("隐藏原生书签界面动作失败（不影响其它功能）", it) }
    }

    /**
     * 恢复原生界面动作可见性（"隐藏原生书签界面"开关关闭时调用；不影响已接管的快捷键动作）
     */
    fun restoreHiddenActions(actionManager: ActionManager) {
        runCatching {
            val state = FavoritesNativeIntegrationState.getInstance().state
            for (actionId in state.hiddenActionIds.toList()) {
                runCatching {
                    actionManager.getAction(actionId)?.let { action ->
                        action.templatePresentation.isVisible = true
                    }
                }.onFailure { log.warn("恢复原生动作可见性失败：$actionId", it) }
            }
            state.hiddenActionIds.clear()
        }.onFailure { log.warn("恢复原生书签界面动作失败（不影响其它功能）", it) }
    }

    /**
     * 全量恢复原生动作（按记录回滚 replaceAction 与可见性；仅供记录回滚/卸载路径使用，
     * 运行期设置开关不再调用——快捷键接管为无条件）
     */
    fun restoreAll(actionManager: ActionManager) {
        runCatching {
            val state = FavoritesNativeIntegrationState.getInstance().state
            val originals = NativeBookmarkActionService.getInstance()

            for (actionId in state.replacedActionIds.toList()) {
                runCatching {
                    val original = originals.getOriginal(actionId) ?: return@runCatching
                    val current = actionManager.getAction(actionId) ?: return@runCatching
                    // 仅当当前仍是我们委托动作时才恢复，避免覆盖用户后续变更
                    if (current.javaClass.name.startsWith(OUR_PACKAGE_PREFIX)) {
                        actionManager.replaceAction(actionId, original)
                    }
                    originals.clearOriginal(actionId)
                }.onFailure { log.warn("恢复原生动作失败：$actionId", it) }
            }

            restoreHiddenActions(actionManager)
            state.replacedActionIds.clear()
        }.onFailure { log.warn("恢复原生书签动作失败", it) }
    }

    /**
     * 用委托动作替换原生动作（逐 id 判空：id 不存在直接跳过；防重复替换）
     */
    private fun replaceWithDelegate(
        actionManager: ActionManager,
        originals: NativeBookmarkActionService,
        state: FavoritesNativeIntegrationState.State,
        nativeActionId: String,
        spec: TakeoverSpec
    ) {
        runCatching {
            val current = actionManager.getAction(nativeActionId) ?: return // 不同版本无此 id，跳过
            if (current.javaClass.name.startsWith(OUR_PACKAGE_PREFIX)) return // 已是委托动作，防重复

            val favoritesAction = actionManager.getAction(spec.favoritesActionId)
            val delegate = DelegateToFavoritesAction(
                nativeActionId = nativeActionId,
                favoritesActionId = spec.favoritesActionId,
                text = favoritesAction?.templatePresentation?.text,
                fixedScope = spec.fixedScope
            )
            originals.putOriginal(nativeActionId, current)
            actionManager.replaceAction(nativeActionId, delegate)
            if (nativeActionId !in state.replacedActionIds) {
                state.replacedActionIds.add(nativeActionId)
            }
        }.onFailure { log.warn("接管原生动作失败：$nativeActionId", it) }
    }

    /**
     * 隐藏未替换的原生动作/动作组（仅 templatePresentation，失败只记日志）
     */
    private fun hideAction(
        actionManager: ActionManager,
        state: FavoritesNativeIntegrationState.State,
        actionId: String
    ) {
        runCatching {
            val action = actionManager.getAction(actionId) ?: return // id 不存在（不同版本），跳过
            if (action.javaClass.name.startsWith(OUR_PACKAGE_PREFIX)) return
            action.templatePresentation.isVisible = false
            if (actionId !in state.hiddenActionIds) {
                state.hiddenActionIds.add(actionId)
            }
        }.onFailure { log.warn("隐藏原生动作失败：$actionId", it) }
    }
}

/**
 * ActionConfigurationCustomizer 扩展点实现（233 签名：void customize(@NotNull ActionManager)）
 *
 * 动作管理器初始化时执行一次：
 * - 无条件接管原生书签动作（与设置开关无关，快捷键始终由本插件接管）；
 * - "隐藏原生书签界面"开关开启（默认）时额外隐藏残余原生动作与原生 Bookmarks 动作组。
 * 注册：plugin.xml 中 <actionConfigurationCustomizer implementation="..."/>
 */
class NativeBookmarkActionCustomizer : ActionConfigurationCustomizer {

    override fun customize(actionManager: ActionManager) {
        // 1) 快捷键接管：无条件（幂等），不再受设置开关影响
        NativeBookmarkActionIntegration.applyTo(actionManager)
        // 2) 原生界面可见性：随"隐藏原生书签界面"开关（默认开启）
        val hideNative = runCatching {
            FavoritesSettings.getInstance().state.hideNativeBookmarks
        }.getOrDefault(true)
        if (hideNative) {
            NativeBookmarkActionIntegration.hideNativeActions(actionManager)
        }
    }
}

// =====================================================================================
// 统一门面：设置变化时对所有已打开项目生效
// =====================================================================================

/**
 * 原生书签集成统一门面：
 * - 快捷键接管：无条件执行（幂等，不再随开关恢复）；
 * - "隐藏原生书签界面"开关：只控制残余原生动作/动作组可见性与原生 Bookmarks 工具窗口显隐；
 * - 手势接管（Ctrl+点击 gutter）：由 FavoritesGutterGestureListener 在项目打开时无条件注册
 */
object FavoritesNativeIntegration {

    private val log = Logger.getInstance(FavoritesNativeIntegration::class.java)

    /**
     * 按当前设置应用到动作管理器与所有已打开项目（设置页 apply 时调用）
     */
    @JvmStatic
    fun applyOrRestore() {
        val hideNative = runCatching {
            FavoritesSettings.getInstance().state.hideNativeBookmarks
        }.getOrDefault(true)

        val actionManager = ActionManager.getInstance()
        // 1) 快捷键接管：无条件（幂等，开关只影响界面可见性）
        NativeBookmarkActionIntegration.applyTo(actionManager)
        // 2) 残余原生动作/动作组可见性：随开关
        if (hideNative) {
            NativeBookmarkActionIntegration.hideNativeActions(actionManager)
        } else {
            NativeBookmarkActionIntegration.restoreHiddenActions(actionManager)
        }

        // 3) 原生 Bookmarks 工具窗口：随开关
        val projects = runCatching {
            ProjectManager.getInstance().openProjects.toList()
        }.getOrNull().orEmpty()
        for (project in projects) {
            if (hideNative) {
                NativeBookmarksToolWindowHider.hide(project)
            } else {
                NativeBookmarksToolWindowHider.restore(project)
            }
        }
    }
}
