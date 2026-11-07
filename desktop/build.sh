#!/usr/bin/env bash
# 离线编译 Windows 桌面版。
#
# 这台机器上没有外网，也没有系统的 kotlinc，所以直接用 Gradle 缓存里的
# kotlin-compiler-embeddable（一个可以 `java -cp` 调起来的 K2JVMCompiler）。
# 编译对象是「共享源码 + 桌面 UI」：GameEngine / Json / LanSession / LanDiscovery / I18n
# 这五个文件和 Android 端是同一份，所以两边的规则、协议、文案完全一致。
set -euo pipefail

cd "$(dirname "$0")/.."

JDK="${JDK:-C:/Users/Lenovo/AppData/Local/AndroidDev/jdk-17.0.20.1+1}"
M="${M:-D:/whaletest/.gradle-home/caches/modules-2/files-2.1}"
KC="$M/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21/79346ed53db48b18312a472602eb5c057070c54d/kotlin-compiler-embeddable-2.0.21.jar"
STD="$M/org.jetbrains.kotlin/kotlin-stdlib/2.0.21/618b539767b4899b4660a83006e052b63f1db551/kotlin-stdlib-2.0.21.jar"
CO="$M/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.7.3/2b09627576f0989a436a00a4a54b55fa5026fb86/kotlinx-coroutines-core-jvm-1.7.3.jar"
TV="$M/org.jetbrains.intellij.deps/trove4j/1.0.20200330/3afb14d5f9ceb459d724e907a21145e8ff394f02/trove4j-1.0.20200330.jar"
AN="$M/org.jetbrains/annotations/13.0/919f0dfe192fb4e063e7dacadee7f8bb9a2672a9/annotations-13.0.jar"

OUT="desktop/build/classes"
rm -rf "$OUT"
mkdir -p "$OUT"

SHARED=(
  "app/src/main/java/dev/whale/crazychess/game/GameEngine.kt"
  "app/src/main/java/dev/whale/crazychess/net/Json.kt"
  "app/src/main/java/dev/whale/crazychess/net/LanSession.kt"
  "app/src/main/java/dev/whale/crazychess/net/LanDiscovery.kt"
  "app/src/main/java/dev/whale/crazychess/i18n/I18n.kt"
)
DESKTOP=(
  "desktop/src/main/kotlin/dev/whale/crazychess/desktop/Theme.kt"
  "desktop/src/main/kotlin/dev/whale/crazychess/desktop/BoardPanel.kt"
  "desktop/src/main/kotlin/dev/whale/crazychess/desktop/Main.kt"
)

echo "· 编译 $((${#SHARED[@]} + ${#DESKTOP[@]})) 个源文件 → $OUT"
"$JDK/bin/java.exe" -cp "$KC;$STD;$CO;$TV;$AN" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-reflect -jvm-target 17 -nowarn \
  -cp "$STD;$CO" -d "$OUT" "${SHARED[@]}" "${DESKTOP[@]}"

echo "· 产物：$(find "$OUT" -name '*.class' | wc -l) 个 class"
echo "· 运行：JDK=$JDK  \"$JDK/bin/java.exe\" -cp \"$OUT;$STD;$CO\" dev.whale.crazychess.desktop.MainKt"
