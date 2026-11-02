package com.aogg.favorites.model

import com.aogg.favorites.helper.SharedBookmarkStorageHelper
import com.aogg.favorites.helper.SharedRefStorageHelper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import org.mockito.Mockito

/**
 * 共享收藏（SHARED 作用域）单元测试支撑工具
 *
 * 解决两个脱离 IDE 环境的问题：
 * - [SharedBookmarkStore] 的 init 块即触发 ensureLoaded（从应用级存储读全量），
 *   纯单元测试没有 Application 实例，会因 ApplicationManager.getApplication() 为 null 而 NPE；
 *   这里在构造前用反射把 ApplicationManager 的静态字段换成 mock Application（isDisposed=false、
 *   invokeLater 什么都不做），构造完成并注入测试假存储后即可脱离 IDE 运行；
 * - store 内部的项目级订阅引用走 SharedRefStorageHelper.getInstance(project) → project.getService，
 *   mock Project 需要统一 stub 该方法返回真实例（无状态的轻量对象，可直接 new）。
 */
object SharedStoreTestSupport {

    /**
     * ApplicationManager 内部持有应用实例的静态字段名（2023.3 SDK 稳定存在）
     */
    private const val APP_FIELD_NAME = "ourApplication"

    /**
     * 是否已安装 mock Application（独立标志位：原始应用实例可能本身就是 null，不能以原值判空）
     */
    private var installed = false

    /**
     * 安装前保存的原应用实例（一般为 null，测试结束后恢复现场）
     */
    private var originalApp: Any? = null

    /**
     * 已安装的 mock Application（供按需 stub 应用级服务解析）
     */
    private var mockApp: com.intellij.openapi.application.Application? = null

    /**
     * 安装 mock Application（幂等）：让 SharedBookmarkStore 的构造与写后广播不再依赖真实 IDE 容器
     */
    fun installMockApplication() {
        if (installed) return
        // ////////////////  准备数据：反射定位静态字段并保存原值  start  //////////////////
        val field = ApplicationManager::class.java.getDeclaredField(APP_FIELD_NAME)
        field.isAccessible = true
        originalApp = field.get(null)
        // ////////////////  准备数据  end  /////////////////////////////////////////////

        // ////////////////  核心逻辑：替换为 mock Application  start  ////////////////////
        val app = Mockito.mock(com.intellij.openapi.application.Application::class.java)
        Mockito.`when`(app.isDisposed).thenReturn(false)
        field.set(null, app)
        mockApp = app
        installed = true
        // ////////////////  核心逻辑  end  /////////////////////////////////////////////
    }

    /**
     * 卸载 mock Application，恢复安装前的现场（未安装时无副作用）
     */
    fun uninstallMockApplication() {
        if (!installed) return
        val field = ApplicationManager::class.java.getDeclaredField(APP_FIELD_NAME)
        field.isAccessible = true
        field.set(null, originalApp)
        originalApp = null
        mockApp = null
        installed = false
    }

    /**
     * 构造注入假应用级存储的共享仓库：
     * - mock Application 的 getService(SharedBookmarkStorageHelper) 指向测试假存储，
     *   使构造期 init 的首次加载就读假存储（而不是 NPE 或误触真实服务）；
     * - 构造完成立即注入 setStorageHelperForTest（同一实例），后续读写全部落在假存储上。
     *
     * @param helper 测试用 SharedBookmarkStorageHelper（真实例，State 可直接检查落盘内容）
     */
    fun newSharedStore(helper: SharedBookmarkStorageHelper): SharedBookmarkStore {
        installMockApplication()
        val app = mockApp!!
        Mockito.`when`(app.getService(SharedBookmarkStorageHelper::class.java)).thenReturn(helper)
        return SharedBookmarkStore().also { it.setStorageHelperForTest(helper) }
    }

    /**
     * mock 项目：提供 basePath/name（集合 id 与 owner 信息来源），
     * 并 stub 项目级服务解析，使订阅引用读写落到传入的真实例
     */
    fun mockProject(basePath: String, name: String, refHelper: SharedRefStorageHelper): Project {
        val project = Mockito.mock(Project::class.java)
        Mockito.`when`(project.basePath).thenReturn(basePath)
        Mockito.`when`(project.name).thenReturn(name)
        Mockito.`when`(project.getService(SharedRefStorageHelper::class.java)).thenReturn(refHelper)
        return project
    }
}
