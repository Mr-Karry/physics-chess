# 弹棋 / Physics Chess —— 开发进度存档

> 复刻自 `CrazyChess v1.3.2.apk`（AndEngine + Box2D 物理象棋）。
> 原版逻辑分析见 `D:\whaletest\CrazyChess_逻辑分析.md`。
>
> 本文件统一使用 **UTF-8（带 BOM）** 保存，Windows 记事本 / VS Code 都能正确识别中文。

---

## 一、项目概况

| 项 | 值 |
| --- | --- |
| 工程目录 | `D:\whaletest\crazychess` |
| 包名 | `dev.whale.crazychess` |
| 版本 | `versionCode = 1` / `versionName = "1.0"` |
| 技术栈 | Kotlin + Jetpack Compose + Material 3（minSdk 24 / compileSdk 35 / targetSdk 35） |
| 物理 | 自写圆形刚体（无 Box2D 依赖），质量 / 阻尼 / 分值照搬原版 |
| 产物 | `弹棋-1.0.apk`（release，R8 压缩，约 1.0 MB） |

---

## 二、已完成模块

| 模块 | 状态 | 说明 |
| --- | --- | --- |
| 工程脚手架 | ✅ | Compose + Material3，Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21 |
| 物理引擎 | ✅ | `game/GameEngine.kt`：圆形刚体、冲量解算、固定子步积分 |
| 规则 | ✅ | 拖拽甩子（**速度 = 拖拽格数**，与「1 格 = 60px、速度 = 像素 / 32」等价）、互撞不死、**棋子中心滑出棋盘才碎**、按分值计分、擒王 / 全歼两种胜利 |
| 人机 AI | ✅ | 复刻原版 `androidKick`：最强子→目标、目标贴边时换最远子、18% 概率换次强子打王，带难度抖动 |
| 双人对战 | ✅ | 同一台设备轮流甩子，`settleIfStuck` 停稳 0.45s 即清速换手 |
| M3E 设计语言 | ✅ | 大圆角 8/14/20/28/36dp、Expressive 字体层级、`SingleChoiceSegmentedButtonRow`、弹簧 / 淡入动画 |
| 动态取色 | ✅ | API 31+ 走 `dynamicLight/DarkColorScheme`；棋子与棋盘全部取自 `ColorScheme`，低版本回退到内置青绿 + 暖砂配色 |
| 应用图标 | ✅ | 自适应图标（渐变背景 + 光环王冠前景 + Android 13 `<monochrome>` 单色层），Android 8 以下有方形 / 圆形回退 |
| 深色模式 | ✅ | 已实测（`cmd uimode night yes`） |
| 响应式布局 | ✅ | 竖屏：得分卡在上、棋盘居中；横屏 / 平板：棋盘在左、面板在右；首页 `widthIn(max = 560.dp)` 居中 |
| 对局界面 | ✅ | 得分卡（逐帧快照、实时刷新）、棋盘、回合横幅、"已碎"战果条、暂停弹窗、终局印章 + 结算弹窗 |
| 音效 | ✅ | `SoundFx`（SoundPool）：`whoosh.ogg` 出手、`plum4.ogg` 决胜子掉落、`crowd.ogg` 欢呼 |
| 构建安装 | ✅ | debug / release 均可构建、可安装、可运行（模拟器 emulator-5554 实测） |

---

## 三、构建与运行

```powershell
cd D:\whaletest\crazychess
$env:GRADLE_USER_HOME="D:\whaletest\.gradle-home"          # 沙箱禁止写 C:\Users\Lenovo\.gradle
$env:JAVA_HOME="C:\Users\Lenovo\AppData\Local\AndroidDev\jdk-17.0.20.1+1"
$env:ANDROID_HOME="C:\Users\Lenovo\AppData\Local\Android\Sdk"
.\gradlew.bat :app:assembleDebug  --console=plain --no-watch-fs -q
.\gradlew.bat :app:assembleRelease --console=plain --no-watch-fs
```

产物：
- debug：`app\build\outputs\apk\debug\app-debug.apk`
- release：`app\build\outputs\apk\release\app-release.apk`（对外分发时复制为 `弹棋-1.0.apk`）

模拟器：

```powershell
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd medium_phone
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app\build\outputs\apk\release\app-release.apk
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell am start -n dev.whale.crazychess/.MainActivity
```

坑位备忘：

- `libs.versions.toml` 与所有 xml 必须 **UTF-8 无 BOM**（PowerShell 5.1 的 `Set-Content -Encoding UTF8` 会写 BOM，Gradle 会报 `Unexpected '\ufeff'`）。
- 必须加 `--no-watch-fs`，否则沙箱下文件监视器报 `Couldn't open current thread, error = 5`。
- `gradle.properties` 必须有 `kotlin.compiler.execution.strategy=in-process`，否则 Kotlin 守护进程写 `C:\Users\Lenovo\AppData\Local\kotlin\daemon` 被拒（`AccessDeniedException` / `Could not connect to Kotlin compile daemon`）。
- 签名：debug 与 release **共用**同一把自签证书，否则 `adb install -r` 会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。证书路径不写进仓库，改用环境变量：`CRAZYCHESS_KEYSTORE`（配合 `CRAZYCHESS_KEYSTORE_PASSWORD` / `CRAZYCHESS_KEY_ALIAS`，默认均为 `crazychess`）；不设时 `app/build.gradle.kts` 退回 Gradle 默认签名行为。
- 截图用 `android_screenshot` 工具；`adb exec-out screencap -p > file` 在 PowerShell 下会损坏 PNG（抓动画帧用 `adb shell screencap -p /sdcard/x.png` + `adb pull`）。
- Gradle wrapper 的 `gradle-8.9-bin.zip` 是本机打包的（必须包含目录条目，否则解压报 `LICENSE` 找不到）。

---

## 四、源码结构

```
app/src/main/java/dev/whale/crazychess/
├── MainActivity.kt          # 首页↔对局 的状态导航 + AnimatedContent 转场
├── SoundFx.kt               # SoundPool 封装（出手 / 决胜 / 欢呼）
├── game/GameEngine.kt       # 规则 + 物理 + AI（纯 Kotlin，无 Android 依赖）
└── ui/
    ├── Theme.kt             # M3E 形状/字体、动态取色、BoardColors / PiecePaint
    ├── HomeScreen.kt        # 模式 / 难度 / 胜利条件 / 棋子颜色（先后手）
    ├── GameScreen.kt        # 对局页 + 主循环 + 快照 + 计分/战果条/战报/弹窗 + 横竖屏 + 平移条
    └── BoardView.kt         # Canvas 棋盘 + 拖拽甩子 + 瞄准线 + 坠落特效
```

---

## 五、版本记录

### v1.1 —— 实机反馈的四项修复（m01327）

1. **计分板不实时**：根因是 `engine.snapshot()` 在 `Scaffold` 外层求值，子层拿不到新值。改为 `snapshotState = mutableStateOf(...)`，帧循环写入、在 `BoxWithConstraints` **内部**读取；`ScoreHeader / FallenStrip / TurnBanner` 只吃快照基本类型。
2. **出局判定改成"掉出棋盘"**：`checkDeaths()` 从「中心 ± 半径碰边」改为「中心越界」（`x<0 || x>8 || y<0 || y>8`）；半径 0.34 → 0.44，棋盘四周留 5.5% 边距，贴边棋子呈"半个身子悬在崖边"的样子，再滑一下才掉下去，带缩小淡出的坠落动画（`FallFx` + `Rules.FALL_FX_MS = 520`）。
3. **先手不再必赢**：`SHOT_POWER` 2.15 → **1.0**。原版换算 `速度(px/s) = 像素偏移 / 32`、`1 格 = 60px`，等价于「速度(格/秒) = 拖拽格数」；此前放大 2.15 倍，一发满力（5.5 格）能直接把对面王顶出场。
4. **双人模式自动换手**：新增 `Rules.SETTLE_SPEED = 0.35` / `SETTLE_TIME = 0.45`，`tick()` 里 `settleIfStuck()`：全场都慢下来超过 0.45s 就统一清零速度 → `isSettled()` 立刻为真。
5. **平板横屏棋盘超屏**：`GameScreen` 用 `BoxWithConstraints` 判横竖屏；横屏 `Row`（棋盘 `weight(1.1f)` + 面板），棋盘用显式 `size(minOf(maxWidth, maxHeight) - 8.dp)`，不再依赖 `aspectRatio(fillMaxHeight)`。

### v1.2 —— 双人可读性 / 扁平棋子 / 深色配色 / 音效 / 签名（m01642）

1. **双人模式镜像 UI**：新增 `HumanPlayerBar(side, snap, colors, mirrored, onPause)`，顶部玩家的整条状态栏 `rotate(180f)`；竖屏上下各一条，横屏右侧面板也是两条。条内含阵营色点、`下方/上方`、大号分数、剩余子数、当前行动方的「出手」胶囊，下方条带暂停键。
2. **棋子扁平化**：`drawPieceAt` 去掉径向渐变、内圈、模糊阴影，改成「硬投影 + 实心圆 + 描边 + Black 字重字形」；棋盘底色也从渐变改成纯色圆角矩形 + 1dp 描边。
3. **深色模式不再发灰**：新增 `PiecePaint(face, rim, ink)`，字形颜色按对比度自动选深/浅。
4. **音效**：`SoundFx`（SoundPool），三个资源从原版 assets 拷进 `res/raw`。
5. **release 签名**：`D:\whaletest\work\release.keystore` + R8（`isMinifyEnabled` / `isShrinkResources`）+ `app/proguard-rules.pro`。

### v1.3 —— 配色回退到动态取色原色（m01898）

1. 删除 `vivid()`（强制饱和度 / 固定明度会让棋子和主题脱节），浅色模式直接用 `primary` / `tertiary` 原色。
2. 深色模式用 `faceOf()` 往棋盘底色 `lerp(..., 0.34f)` 压一档，形成「浅蓝紫 / 浅藕紫底 + 深色字」，与浅色模式明显不同。
3. `inkOn()` 改成比较「白字对比度 vs 深字对比度」（`1.05/(L+0.05)` 与 `(L+0.05)/0.05`），避免固定阈值在动态取色下翻车。
4. 首页文案按现玩法重写，并强调"朝目标方向拖"。
5. 移除首页右上角装饰用的 sideB 小色点。
6. debug / release 统一签名，`adb install -r` 可以来回覆盖。

### v1.4 —— AI 出手音效 + 终局印章与欢呼（m02033）

1. AI 出手没声音：此前只有 `BoardView.onShot` 播 whoosh，AI 在帧循环里直接改速度。改为引擎维护 `shotStamp` / `lastShotBy`，`shoot()` 与 `aiShoot()` 都 +1，界面统一读 `shotStamp` 播放（AI 用略低的 rate 区分音色）。
2. 掉落音效只在决胜时响：去掉"每死一子播 plum4"，改成 `isOver` 第一次成立时播 `SoundFx.decide()`，随后 `SoundFx.cheer()` 循环两轮（对齐原版 crowd）。
3. 终局大字报：新增 `WinStamp`——屏幕中央胜利方配色的圆底 + 大拇指（`Icons.Filled.ThumbUp`），spring 弹入；`side == TOP` 时整体旋转 180°，坐在对面的人看到是正的。印章停留约 3.4 秒后再弹结算弹窗；平局不显示印章。
4. 依赖 `androidx.compose.material:material-icons-extended`（只用一个图标，R8 裁剪后 release 仅增加约 200 字节）。

### v1.5 —— 选颜色 = 选先后手（m02257）

- 首页新增「棋子颜色 · 决定先后手」分区，两张卡片："我方先出 / 执此色" 与 "对方先出 / 执此色"。
- 规则按原版语义：**先手由颜色决定，与坐哪边无关**；原版"选金色阵营双方都动不了"的锁死 bug 没有复刻。
- `GameEngine` 增加构造参数 `bottomMovesFirst: Boolean = true`，`reset()` 里据此设 `activeSide`。
- `BoardColors.mirrored()`：把 A/B 的所有颜色（face / container / on / paint / selection）整体对调；`bottomMovesFirst == false` 时用镜像版本。
- 顺带：`App()` 的 `Surface` 加 `statusBarsPadding() + navigationBarsPadding()`；首页「开始对局」固定在底部、内容区可滚动。

### v1.6 —— 横向平移棋盘（m02445）

- 目的：贴左边线的棋子只有约 150px 可拖（约 1.3 格），把棋盘推过去才能拉满力度。
- 起初实现为"拖棋盘空白处平移"，实测容易和甩子抢手势，**v1.0 修订版已改为独立的拖动条**（见下）。
- 平移最多 ±3.5 格；换手时自动平滑回中（对应原版 `returnCamera`）。

### v1.0 —— 第一个正式版

- 版本号 `versionCode = 1` / `versionName = "1.0"`，release 签名打包，产物 `弹棋-1.0.apk`。
- **精简首页说明文字**：去掉「王滑出棋盘即落败 · 原版默认规则 / 吃光对手所有棋子才获胜 · 原版里选不到的模式」以及颜色区下方「…和原版一样，选哪个颜色就决定谁先手」三段旁注。
- **去掉战果条的空状态文案**：`FallenStrip` 在没有棋子出局时直接不渲染，有棋子出局才显示"已碎 + 棋子圆点"。
- **应用图标重做**：与首页右上角 `PieceHalo` 同构的扁平版。
- **平移方式改造**：删掉"拖棋盘空白处"，改成棋盘上 / 下方的**拖动条**（浅色胶囊轨道 + 动态取色滑块 + ⇆ 提示），任意位置横拖即可平移，松手保留，换手自动回中；`BoardView` 只接收 `panFraction` 负责绘制，误触消失。
- **双人模式两条拖动条**：竖屏 / 横屏都是棋盘上下各一条（上方用顶方配色），人机模式只有下方一条。
- 底部提示改为「拖动己方棋子瞄准，松手甩出 · 拖横条可左右挪棋盘」。

---

## 六、待办 / 可选打磨

1. **棋子贴图**：现用 ♟♜♞♝♛♚ 字形绘制，可换成矢量棋子或原版 army1 / army2 风格资源。
2. **AI 强度曲线**：三档差异目前只在精度与力度（约 36% / 44% / 54% 的每手吃子率），可再加"轻松不打王""凶狠预演候选线路"。
3. **发布包**：`bundleRelease` 生成 AAB；上架前补隐私政策与 `targetSdk` 复核。
4. **平板细节**：横屏右侧面板可把两张得分卡并排，进一步利用宽度。
5. **无障碍**：棋盘是 Canvas，缺 TalkBack 语义，可补 `contentDescription` 与替代操作。
