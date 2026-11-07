#!/usr/bin/env bash
# 打 Windows 桌面版自包含包。
#
# 产物：desktop/dist/PhysicsChess/            ← 双击 PhysicsChess.bat 就能跑
#       desktop/dist/PhysicsChess-1.4-win.zip ← 发布用压缩包
#
# 说明：本来想用 jpackage app-image，但这台机器的沙箱里 jpackage 一律报
# 「目标目录不可写」（连 hello world 都打不出来，且 jlink 单独跑是好的），
# 所以改成「jlink 精简 JRE + jar + 启动脚本」手工组装，效果等价：
# 目标机器不用装 Java，解压即用。
set -euo pipefail

cd "$(dirname "$0")/.."

JDK="${JDK:-C:/Users/Lenovo/AppData/Local/AndroidDev/jdk-17.0.20.1+1}"
VER="1.4"
M="${M:-D:/whaletest/.gradle-home/caches/modules-2/files-2.1}"
STD="$M/org.jetbrains.kotlin/kotlin-stdlib/2.0.21/618b539767b4899b4660a83006e052b63f1db551/kotlin-stdlib-2.0.21.jar"
CO="$M/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.7.3/2b09627576f0989a436a00a4a54b55fa5026fb86/kotlinx-coroutines-core-jvm-1.7.3.jar"

bash desktop/build.sh

OUT="desktop/dist/PhysicsChess"
rm -rf "$OUT" "desktop/dist/PhysicsChess-${VER}-win.zip"
mkdir -p "$OUT/app"

# 1) 应用 jar
"$JDK/bin/jar.exe" --create --file "$OUT/app/physics-chess-desktop.jar" -C desktop/build/classes .
cp "$STD" "$OUT/app/kotlin-stdlib.jar"
cp "$CO" "$OUT/app/kotlinx-coroutines-core-jvm.jar"

# 2) 精简 JRE（只带 Swing + 基础模块，43 MB 左右）
"$JDK/bin/jlink.exe" \
  --add-modules java.base,java.desktop,java.logging \
  --strip-debug --no-header-files --no-man-pages --compress=2 \
  --output "$OUT/runtime"

# 3) 启动脚本：用 javaw（无控制台窗口），日志写到同目录 app.log
printf '@echo off\r\nstart "Physics Chess" "%%~dp0runtime\\bin\\javaw.exe" -Dfile.encoding=UTF-8 -Xmx512m -cp "%%~dp0app\\*" dev.whale.crazychess.desktop.MainKt\r\n' > "$OUT/PhysicsChess.bat"

echo "· 产物：desktop/dist/PhysicsChess/PhysicsChess.bat"

# 4) 发布用 zip
cd desktop/dist
powershell.exe -NoProfile -Command "Compress-Archive -Path 'PhysicsChess' -DestinationPath 'PhysicsChess-${VER}-win.zip' -Force" >/dev/null
cd ../..
ls -lh "desktop/dist/PhysicsChess-${VER}-win.zip" | awk '{print "· 产物：desktop/dist/PhysicsChess-'"$VER"'-win.zip  " $5}'
