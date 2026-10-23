package com.aogg.favorites.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.sql.ResultSet
import java.sql.ResultSetMetaData

/**
 * SqlRuleExecutor 纯函数单元测试（不依赖 IntelliJ 平台、不真正连数据库）
 *
 * 只覆盖 companion 纯函数与私有的 readRows 行映射（经反射 + Mockito 模拟 java.sql 接口，
 * ResultSet/ResultSetMetaData 均为 JDK 接口，属纯 JVM 测试）。
 * execute()/query() 依赖平台 Database Tools 反射通道与真实 JDBC 连接，不属纯 JVM 单测范围。
 */
@DisplayName("SqlRuleExecutor 单元格转文本/路径列定位/结果行映射")
class SqlRuleExecutorTest {

    // ==================== cellToText：单元格转文本 ====================

    @Test
    fun `null cell becomes empty string`() {
        assertEquals("", SqlRuleExecutor.cellToText(null), "null 单元格应转为空串")
    }

    @Test
    fun `non string cell falls back to toString`() {
        assertEquals("abc", SqlRuleExecutor.cellToText("abc"))
        assertEquals("42", SqlRuleExecutor.cellToText(42), "整型应 toString")
        assertEquals("true", SqlRuleExecutor.cellToText(true), "布尔应 toString")
        assertEquals("1.50", SqlRuleExecutor.cellToText(java.math.BigDecimal("1.50")), "数值类型按 toString 输出")
    }

    // ==================== resolvePathColumnIndex：路径列定位 ====================

    @Test
    fun `path column resolved to 1-based index`() {
        assertEquals(3, SqlRuleExecutor.resolvePathColumnIndex(listOf("id", "name", "path"), "path"), "命中列应返回 1-based 下标")
    }

    @Test
    fun `path column match is case insensitive and trims`() {
        assertEquals(2, SqlRuleExecutor.resolvePathColumnIndex(listOf("ID", "PATH"), "path"), "列名匹配应忽略大小写")
        assertEquals(1, SqlRuleExecutor.resolvePathColumnIndex(listOf(" path "), "path"), "列名首尾空白应容忍")
        assertEquals(2, SqlRuleExecutor.resolvePathColumnIndex(listOf("a", "Path"), "  path  "), "目标列名首尾空白应容忍")
    }

    @Test
    fun `disabled or missing path column returns minus one`() {
        assertEquals(-1, SqlRuleExecutor.resolvePathColumnIndex(listOf("id"), ""), "未启用路径列应返回 -1")
        assertEquals(-1, SqlRuleExecutor.resolvePathColumnIndex(listOf("id"), "   "), "纯空白路径列视为未启用")
        assertEquals(-1, SqlRuleExecutor.resolvePathColumnIndex(listOf("id", "name"), "nope"), "列不存在应返回 -1 而非报错")
    }

    // ==================== readRows：结果行映射为 RowItem（反射调用） ====================

    private fun readRows(rs: ResultSet, limit: Int, pathColumn: String): List<RuleItem> {
        val method = SqlRuleExecutor::class.java.getDeclaredMethod(
            "readRows", ResultSet::class.java, Integer.TYPE, String::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(SqlRuleExecutor(), rs, limit, pathColumn) as List<RuleItem>
    }

    /** 构造按行返回 values 的 ResultSet mock（labels 为列名，values 外层为行、内层为列） */
    private fun mockResultSet(labels: List<String>, rows: List<List<Any?>>): ResultSet {
        val meta = Mockito.mock(ResultSetMetaData::class.java)
        Mockito.`when`(meta.columnCount).thenReturn(labels.size)
        labels.forEachIndexed { index, label ->
            Mockito.`when`(meta.getColumnLabel(index + 1)).thenReturn(label)
        }

        val rs = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(rs.metaData).thenReturn(meta)
        val nextResults: List<Boolean> = rows.map { true } + false
        Mockito.`when`(rs.next()).thenReturn(nextResults.first(), *nextResults.drop(1).toTypedArray())
        labels.indices.forEach { column ->
            val valuesInRowOrder = rows.map { it[column] }.toTypedArray()
            Mockito.`when`(rs.getObject(column + 1)).thenReturn(valuesInRowOrder[0], *valuesInRowOrder.drop(1).toTypedArray())
        }
        return rs
    }

    @Test
    fun `rows map to RowItem with path column value`() {
        val rs = mockResultSet(
            labels = listOf("name", "path"),
            rows = listOf(listOf("A.php", "src/A.php"), listOf("B.php", "src/lib/B.php"))
        )

        val items = readRows(rs, limit = 10, pathColumn = "path")

        assertEquals(2, items.size, "两行数据应映射为两条条目")
        val first = items[0] as RuleItem.RowItem
        assertTrue(items.all { it is RuleItem.RowItem }, "SQL 产物应为 RowItem")
        assertEquals(listOf("A.php", "src/A.php"), first.cells, "单元格应按列顺序转为文本")
        assertEquals("src/A.php", first.path, "路径列的值应填入 RowItem.path")
        assertEquals("src/lib/B.php", (items[1] as RuleItem.RowItem).path)
    }

    @Test
    fun `missing path column maps to null path`() {
        val rs = mockResultSet(
            labels = listOf("name", "note"),
            rows = listOf(listOf("A.php", "whatever"))
        )

        val items = readRows(rs, limit = 10, pathColumn = "path")

        val row = items.single() as RuleItem.RowItem
        assertEquals(listOf("A.php", "whatever"), row.cells)
        assertEquals(null, row.path, "路径列不存在时 path 应为 null，不报错")
    }

    @Test
    fun `blank or null path value maps to null path`() {
        val rs = mockResultSet(
            labels = listOf("name", "path"),
            rows = listOf(listOf("A.php", "   "), listOf("B.php", null))
        )

        val items = readRows(rs, limit = 10, pathColumn = "path")

        assertEquals(null, (items[0] as RuleItem.RowItem).path, "路径列值为空白时 path 应为 null")
        assertEquals(null, (items[1] as RuleItem.RowItem).path, "路径列值为 null 时 path 应为 null")
        assertEquals(listOf("B.php", ""), (items[1] as RuleItem.RowItem).cells, "null 单元格应转为空串")
    }

    @Test
    fun `reading stops at limit`() {
        val rs = mockResultSet(
            labels = listOf("name"),
            rows = listOf(listOf("r1"), listOf("r2"), listOf("r3"))
        )

        val items = readRows(rs, limit = 2, pathColumn = "")

        assertEquals(2, items.size, "达 limit 应停止读取")
    }
}
