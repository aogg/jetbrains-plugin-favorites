package com.aogg.favorites.model

/**
 * 收藏作用域枚举（1.2.0 双作用域模型）
 *
 * 一个收藏位置在添加时必须二选一归属某个作用域，两个作用域各自持久化、互不干扰：
 * - PROJECT：数据随项目走（.idea/favorites.xml），换项目即消失；
 * - GLOBAL：数据随 IDE 应用走（favorites.global.xml），跨项目共享。
 */
enum class FavoriteScope(val label: String) {

    /**
     * 项目收藏：filePath 存相对项目根的路径（项目内可移植）
     */
    PROJECT("项目收藏"),

    /**
     * 全局收藏：filePath 存绝对路径（跨项目定位同一位置）
     */
    GLOBAL("全局收藏");

    companion object {
        /**
         * 按名称安全解析作用域（持久化字符串 → 枚举）
         *
         * @param name 作用域名称（PROJECT/GLOBAL）
         * @return 对应枚举；未知/空值保守回退为 PROJECT（与历史默认行为一致）
         */
        @JvmStatic
        fun fromNameOrDefault(name: String?): FavoriteScope =
            entries.firstOrNull { it.name == name } ?: PROJECT
    }
}
