# JetBrains Plugin - Favorites

收藏插件 - 为 PhpStorm 提供代码行、文件和文件夹的收藏功能，行为尽量与 PhpStorm 原生 Bookmarks 功能保持一致，但通过独立插件实现。

## 功能特性

- **匿名收藏**：无限制数量的收藏，在编辑器行号区域显示书签图标
- **助记符收藏**：使用数字（0-9）或字母（A-Z）作为标识符，最多36个
- **收藏管理工具窗口**：统一管理所有收藏
- **快速访问**：弹出窗口快速跳转到收藏位置
- **多入口支持**：支持从编辑器行号、项目视图、编辑器标签页等多处添加收藏

## 使用说明

### 支持的收藏类型

- **匿名收藏**：
  - 无标识符，数量不限
  - 推荐用于临时标记、审查点、调试观察点等
- **助记符收藏**：
  - 使用数字 `0-9` 和字母 `A-Z` 作为标识符（最多 36 个）
  - 推荐用于常驻、需要快速跳转的位置

### 快捷键（默认键位）

> 所有快捷键都采用 `Ctrl+Alt` 前缀，**避免与 PhpStorm 原生 F11 / Ctrl+F11 / Alt+2 / Shift+F11 等快捷键冲突**，可以在 IDE `Settings | Keymap` 中自行修改。

- **添加/移除匿名收藏（全局）**：`Ctrl+Alt+F11`
- **添加/移除助记符收藏（全局）**：`Ctrl+Alt+Shift+F11`
- **显示/隐藏收藏工具窗口**：`Ctrl+Alt+2`
- **显示收藏弹出列表**：`Ctrl+Alt+Shift+B`
- **跳转到助记符收藏 [0-9, A-Z]**：`Ctrl+Alt+[0-9/A-Z]`

### 入口与场景

- **编辑器行号区域**：
  - 通过快捷键在当前行添加/移除收藏
  - Gutter 区域显示匿名图标或 `[助记符]` 标记
- **项目视图（Project Tool Window）**：
  - 右键文件/文件夹菜单中新增 “添加匿名收藏 / 添加助记符收藏”
  - 支持多选批量收藏
- **编辑器标签页（Editor Tabs）**：
  - 标签右键菜单中新增收藏操作
  - 适合为当前工作集快速建立一组入口

### 收藏管理

- **收藏工具窗口 `Favorites`**：
  - 统一展示所有收藏（行 / 文件 / 文件夹）
  - 支持双击跳转、右键编辑描述和删除
- **快速访问弹出 `收藏列表`**：
  - 使用 `Ctrl+Alt+Shift+B` 打开
  - 支持键盘导航和输入过滤

## 开发状态

### ✅ 阶段一 ~ 七：核心功能与集成（已完成）

- 收藏数据模型与管理器
- 持久化存储与加载
- 编辑器、项目视图、标签页右键菜单集成
- 工具窗口与弹出列表
- 监听器与性能优化

### ✅ 阶段八：测试与文档（已完成）

- 为 `Bookmark` 与 `BookmarkManager` 添加单元测试，覆盖助记符冲突、路径更新、无效行清理等核心逻辑
- 补充本 README 使用说明与快捷键说明
- 检查插件快捷键全部采用 `Ctrl+Alt` 前缀，避免与 PhpStorm 原生收藏快捷键冲突

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
