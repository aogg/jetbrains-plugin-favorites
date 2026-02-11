# JetBrains Plugin - Favorites

收藏插件 - 为 PhpStorm 提供代码行、文件和文件夹的收藏功能。

## 功能特性

- **匿名收藏**：无限制数量的收藏，在编辑器行号区域显示书签图标
- **助记符收藏**：使用数字（0-9）或字母（A-Z）作为标识符，最多36个
- **收藏管理工具窗口**：统一管理所有收藏
- **快速访问**：弹出窗口快速跳转到收藏位置

## 开发状态

### ✅ 阶段一：基础框架（已完成）

1. ✅ 创建项目结构
2. ✅ 配置 `plugin.xml` 和 `build.gradle.kts`
3. ✅ 实现插件主类 `FavoritesPlugin`
4. ✅ 实现基础数据模型 `Bookmark`、`BookmarkType`
5. ✅ 实现 `BookmarkManager` 单例

## 项目结构

```
src/
├── main/
│   ├── kotlin/
│   │   └── com/
│   │       └── aogg/
│   │           └── favorites/
│   │               ├── FavoritesPlugin.kt      # 插件主类
│   │               └── model/                   # 数据模型
│   │                   ├── Bookmark.kt          # 收藏数据模型
│   │                   ├── BookmarkType.kt      # 收藏类型枚举
│   │                   └── BookmarkManager.kt   # 收藏管理器
│   └── resources/
│       └── META-INF/
│           └── plugin.xml                      # 插件配置
```

## 构建说明

### 方式一：使用 IntelliJ IDEA（推荐）

1. 在 IntelliJ IDEA 中打开项目
2. IDE 会自动下载 Gradle wrapper
3. 在 Gradle 工具窗口中运行 `buildPlugin` 任务
4. 插件包将生成在 `build/distributions/` 目录

### 方式二：使用命令行

```bash
# Windows
gradlew.bat buildPlugin

# Linux/macOS
./gradlew buildPlugin
```

## 安装插件

1. 构建完成后，在 `build/distributions/` 目录找到生成的 zip 文件
2. 在 PhpStorm 中：`File` -> `Settings` -> `Plugins` -> `Install Plugin from Disk`
3. 选择生成的 zip 文件进行安装
4. 重启 PhpStorm

## 技术栈

- **语言**：Kotlin
- **平台**：IntelliJ Platform SDK
- **目标IDE**：PhpStorm 2023.3+
- **构建工具**：Gradle

## 开发计划

详见 [docs/favorites-plugin-implementation-plan.md](docs/favorites-plugin-implementation-plan.md)

## 作者

- 作者：aogg
- 官网：https://github.com/aogg

## 许可证

待定
