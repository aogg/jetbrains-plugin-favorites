#!/usr/bin/env bash
# 便捷打包脚本：自动定位 JDK17 → 执行 Gradle 打包 → 打印插件 zip 的路径/大小/sha256
# 用法:
#   bash bin/build.sh                    # 默认执行 buildPlugin
#   bash bin/build.sh --test             # 先跑 test 再打包
#   bash bin/build.sh --clean            # 打包前先 clean
#   bash bin/build.sh --clean --test     # 组合使用
#   其余参数原样透传给 gradlew（作为 Gradle 任务/参数）
set -euo pipefail

# ---- 定位项目根（脚本位于 bin/ 下）并切换过去 ----
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

# ---- 定位 Java：已设置的 JAVA_HOME 优先 → PATH 里的 java → 探测 /usr/lib/jvm/java-17* ----
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME:-}/bin/java" ]]; then
    if command -v java >/dev/null 2>&1; then
        : # 直接用 PATH 里的 java
    else
        for candidate in /usr/lib/jvm/java-17*; do
            if [[ -x "$candidate/bin/java" ]]; then
                export JAVA_HOME="$candidate"
                export PATH="$JAVA_HOME/bin:$PATH"
                break
            fi
        done
    fi
fi

# 最终校验：仍找不到就报错退出（本项目需 JDK17）
if ! command -v java >/dev/null 2>&1; then
    echo "[错误] 未找到可用的 JDK17（JAVA_HOME / PATH / /usr/lib/jvm/java-17* 均未命中）" >&2
    echo "       请安装 JDK17，例如: export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64" >&2
    exit 1
fi

echo "[INFO] JAVA_HOME=${JAVA_HOME:-<未设置，使用 PATH 中的 java>}"
echo "[INFO] java -version:"
java -version

# ---- 解析参数 ----
RUN_TEST=0
DO_CLEAN=0
PASSTHROUGH=()
for arg in "$@"; do
    case "$arg" in
        --test)  RUN_TEST=1 ;;
        --clean) DO_CLEAN=1 ;;
        *)       PASSTHROUGH+=("$arg") ;; # 其余参数原样透传给 gradlew
    esac
done

# 组装 Gradle 任务顺序：clean → test → 透传参数；无参数时默认 buildPlugin
GRADLE_TASKS=()
if [[ $DO_CLEAN -eq 1 ]]; then GRADLE_TASKS+=("clean"); fi
if [[ $RUN_TEST -eq 1 ]]; then GRADLE_TASKS+=("test"); fi
GRADLE_TASKS+=("${PASSTHROUGH[@]}")
if [[ ${#GRADLE_TASKS[@]} -eq 0 ]]; then GRADLE_TASKS=("buildPlugin"); fi

# ---- gradlew 无执行权限时自动补（Windows checkout 后常见）----
if [[ ! -x ./gradlew ]]; then
    echo "[INFO] gradlew 无执行权限，自动执行 chmod +x"
    chmod +x ./gradlew
fi

echo "[INFO] 执行: ./gradlew ${GRADLE_TASKS[*]}"
./gradlew "${GRADLE_TASKS[@]}"

# ---- 构建成功：取 distributions/ 下最新的 zip（找不到再退回 build/distributions/）----
LATEST_ZIP=""
for dir in distributions build/distributions; do
    candidate="$(ls -t "$dir"/*.zip 2>/dev/null | head -n 1 || true)"
    if [[ -n "$candidate" ]]; then
        LATEST_ZIP="$candidate"
        break
    fi
done
if [[ -z "$LATEST_ZIP" ]]; then
    echo "[错误] 未找到插件 zip（distributions/ 与 build/distributions/ 均为空）" >&2
    exit 1
fi
ABS_ZIP="$(cd "$(dirname "$LATEST_ZIP")" && pwd)/$(basename "$LATEST_ZIP")"

echo ""
echo "=========================================================="
echo "构建成功！"
echo "插件包 : $ABS_ZIP"
echo "大小   : $(du -h "$LATEST_ZIP" | cut -f1)"
echo "sha256 : $(sha256sum "$LATEST_ZIP" | cut -d' ' -f1)"
echo "安装   : PhpStorm → Settings → Plugins → ⚙ → Install Plugin from Disk 选择该 zip"
echo "=========================================================="
