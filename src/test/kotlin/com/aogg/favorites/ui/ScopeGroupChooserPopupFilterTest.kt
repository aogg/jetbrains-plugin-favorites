package com.aogg.favorites.ui

import com.aogg.favorites.model.FavoriteScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * ScopeGroupChooserPopup 自带输入过滤（TypeAheadSelector）命中语义单元测试（1.5.x 添加选分组新增）
 *
 * 说明：
 * - TypeAheadSelector 与 NodePayload 为私有类型，但过滤匹配是纯逻辑（无平台依赖，仅读 JTree 模型），
 *   为覆盖「按作用域名/分组完整路径前置匹配」的回归（f9f0c77），这里经反射访问；
 *   类/方法改名时测试会显式失败，属预期维护点
 * - 只测纯 JVM/Swing，不弹真实 JBPopup
 */
class ScopeGroupChooserPopupFilterTest {

    private val scopeNodeClass =
        Class.forName("com.aogg.favorites.ui.ScopeGroupChooserPopup\$NodePayload\$ScopeNode")
    private val groupNodeClass =
        Class.forName("com.aogg.favorites.ui.ScopeGroupChooserPopup\$NodePayload\$GroupNode")
    private val selectorClass =
        Class.forName("com.aogg.favorites.ui.ScopeGroupChooserPopup\$TypeAheadSelector")

    // 树上各分组节点引用（断言命中具体节点用）
    private lateinit var defaultNode: DefaultMutableTreeNode
    private lateinit var workApiNode: DefaultMutableTreeNode
    private lateinit var docsApiNode: DefaultMutableTreeNode
    private lateinit var projectScopeNode: DefaultMutableTreeNode
    private lateinit var globalScopeNode: DefaultMutableTreeNode

    private fun newScopePayload(scope: FavoriteScope): Any =
        scopeNodeClass.getDeclaredConstructor(FavoriteScope::class.java)
            .apply { isAccessible = true }
            .newInstance(scope)!!

    private fun newGroupPayload(scope: FavoriteScope, listName: String, path: String, isDefault: Boolean): Any =
        groupNodeClass.getDeclaredConstructor(
            FavoriteScope::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }.newInstance(scope, listName, path, isDefault)!!

    /** 构建测试树：项目收藏（收藏/ work→api / docs→api）+ 全局收藏 */
    private fun buildTree(): JTree {
        val root = DefaultMutableTreeNode("root", true)

        projectScopeNode = DefaultMutableTreeNode(newScopePayload(FavoriteScope.PROJECT), true)
        root.add(projectScopeNode)
        defaultNode = DefaultMutableTreeNode(newGroupPayload(FavoriteScope.PROJECT, "收藏", "收藏", true), false)
        projectScopeNode.add(defaultNode)
        val workNode = DefaultMutableTreeNode(newGroupPayload(FavoriteScope.PROJECT, "work", "work", false), true)
        workApiNode = DefaultMutableTreeNode(newGroupPayload(FavoriteScope.PROJECT, "api", "work/api", false), false)
        workNode.add(workApiNode)
        projectScopeNode.add(workNode)
        val docsNode = DefaultMutableTreeNode(newGroupPayload(FavoriteScope.PROJECT, "docs", "docs", false), true)
        docsApiNode = DefaultMutableTreeNode(newGroupPayload(FavoriteScope.PROJECT, "api", "docs/api", false), false)
        docsNode.add(docsApiNode)
        projectScopeNode.add(docsNode)

        globalScopeNode = DefaultMutableTreeNode(newScopePayload(FavoriteScope.GLOBAL), true)
        root.add(globalScopeNode)

        return JTree(DefaultTreeModel(root))
    }

    /** 经反射调用 TypeAheadSelector.firstMatchingPath，返回命中节点的用户对象 */
    private fun firstMatchingPayload(tree: JTree, query: String): Any? {
        val selector = selectorClass.getDeclaredConstructor(JTree::class.java)
            .apply { isAccessible = true }
            .newInstance(tree)!!
        val method = selectorClass.getDeclaredMethod("firstMatchingPath", String::class.java)
            .apply { isAccessible = true }
        val path = method.invoke(selector, query) as TreePath?
        return (path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject
    }

    private fun payloadPath(payload: Any): String =
        groupNodeClass.getDeclaredField("path").apply { isAccessible = true }.get(payload) as String

    // ==================== 「父/子」完整路径前缀命中（回归 f9f0c77） ====================

    @Test
    fun `query should match group by full parent child path prefix`() {
        val tree = buildTree()
        assertEquals(
            workApiNode.userObject,
            firstMatchingPayload(tree, "work/api"),
            "输入「父/子」完整路径应命中子分组"
        )
    }

    @Test
    fun `query should match parent path prefix including deeper nodes`() {
        val tree = buildTree()
        val payload = firstMatchingPayload(tree, "work")
        assertTrue(payload != null && payloadPath(payload).startsWith("work"), "父路径前缀应命中（深度优先先返回更深命中节点）")
    }

    @Test
    fun `query should match default group by its path`() {
        val tree = buildTree()
        assertEquals(defaultNode.userObject, firstMatchingPayload(tree, "收藏"), "默认列表按自身路径命中")
    }

    @Test
    fun `query should match scope by label`() {
        val tree = buildTree()
        assertEquals(projectScopeNode.userObject, firstMatchingPayload(tree, "项目"), "作用域名命中项目收藏根")
        assertEquals(globalScopeNode.userObject, firstMatchingPayload(tree, "全局"), "作用域名命中全局收藏根")
    }

    @Test
    fun `non prefix query should not match`() {
        val tree = buildTree()
        assertNull(firstMatchingPayload(tree, "api"), "分组名出现在路径中段而非前缀时不命中（前置匹配语义）")
        assertNull(firstMatchingPayload(tree, "nomatch"), "无命中返回 null（不改变选中）")
    }

    @Test
    fun `key typed should lowercase input before matching`() {
        val tree = buildTree()
        val selector = selectorClass.getDeclaredConstructor(JTree::class.java)
            .apply { isAccessible = true }
            .newInstance(tree)!!
        val keyTyped = selectorClass.getDeclaredMethod("keyTyped", KeyEvent::class.java)
            .apply { isAccessible = true }

        keyTyped.invoke(selector, KeyEvent(tree, KeyEvent.KEY_TYPED, 0L, 0, KeyEvent.VK_UNDEFINED, 'W'))

        val selected = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode
        assertTrue(selected != null, "键入应选中首个命中节点")
        val payload = selected!!.userObject
        assertTrue(payloadPath(payload).startsWith("w"), "大写输入转小写后按前缀命中")
    }
}
