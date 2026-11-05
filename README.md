<div align="center">

<img src="docs/00-icon.png" width="112" alt="弹棋图标" />

# 弹棋 · Physics Chess

**把棋子甩出去，把对手的王撞出棋盘。**

Kotlin + Jetpack Compose + Material 3 Expressive 重写的物理象棋小游戏，
复刻自 Android 老游戏 `CrazyChess v1.3.2`（AndEngine + Box2D）。

</div>

---

## 这是什么

棋盘上摆着国际象棋的 16 枚棋子，但**没有走格规则**。

按住自己的棋子、朝着目标方向拖动，松手就把它整枚甩出去：

- 棋子之间会真实碰撞、传递动量，可以隔山打牛把后面的敌人顶飞；
- 棋子互相撞击**不会**出局，只有**整个滑出棋盘**才碎掉；
- 碎掉的棋子按原版分值给对方加分：兵 5 / 马 25 / 象 20 / 车 30 / 后 50 / 王 100；
- 己方每损失一子，己方的王会变得「更滑」，更容易被推出去（原版的滚雪球机制）。

胜负条件二选一：**擒王**（对方王出局）或**全歼**（吃光对方所有棋子）。

## 截图

| 首页 | 对局（浅色） | 对局（深色） |
| :--: | :--: | :--: |
| ![首页](docs/01-home-light.png) | ![对局](docs/02-game-light.png) | ![深色](docs/03-game-dark.png) |

## 特性

- **完整玩法复刻**：质量 / 阻尼 / 分值 / 出局判定 / 计分口径全部对齐原版；甩子速度按原版换算 `速度(格/秒) = 拖拽格数`。
- **人机 + 双人**：AI 复刻原版启发式（最强子打对面最强子、目标贴边时换最远子、18% 概率换次强子直取对方王），三档难度只调精度与力度。
- **选颜色 = 选先后手**：先手由颜色决定，与坐哪边无关（原版语义）。
- **动态取色**：棋子、棋盘、拖动条、图标全部取自 Material You 配色，跟随壁纸变化；深色模式另有一套明度处理。
- **Material 3 Expressive**：大圆角、弹性动画、分段按钮、终局印章（胜利方配色的「大拇哥」，坐对面的人看到是正的）。
- **响应式**：手机竖屏 / 横屏、平板都做了布局；横屏是「棋盘在左、面板在右」。
- **左右挪棋盘**：靠边的棋子拖不满力，用棋盘上/下那条动态取色的拖动条把棋盘推过去即可；换手自动回中。

## 技术要点

| | |
| --- | --- |
| 语言 / UI | Kotlin 2.0、Jetpack Compose、Material 3 |
| 物理 | 自写圆形刚体（固定子步积分 + 冲量解算 + 恢复系数 0.5），无 Box2D 依赖 |
| 架构 | `GameEngine` 纯 Kotlin 无 Android 依赖，UI 每帧取快照驱动重组 |
| 音效 | `SoundPool`（出手 / 决胜 / 欢呼，素材取自原版 `assets/mfx`） |
| 图标 | 自适应图标（渐变底 + 光环王冠 + Android 13 单色层） |

## 构建

需要 JDK 17 与 Android SDK（compileSdk 35）。

```bash
# debug
./gradlew :app:assembleDebug

# release（需要自备签名，见 app/build.gradle.kts 的 signingConfigs）
./gradlew :app:assembleRelease
```

安装到设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.whale.crazychess/.MainActivity
```

如果 Gradle 提示 `Could not connect to Kotlin compile daemon`，在 `gradle.properties` 里加：

```properties
kotlin.compiler.execution.strategy=in-process
```

## 目录

```
app/src/main/java/dev/whale/crazychess/
├── MainActivity.kt          # 首页 ↔ 对局 的状态导航与转场
├── SoundFx.kt               # SoundPool 封装
├── game/GameEngine.kt       # 规则 + 物理 + AI（纯 Kotlin）
└── ui/
    ├── Theme.kt             # M3E 形状/字体、动态取色、棋盘配色映射
    ├── HomeScreen.kt        # 模式 / 难度 / 胜负条件 / 颜色（先后手）
    ├── GameScreen.kt        # 对局页、主循环、计分、战果、弹窗、横竖屏、拖动条
    └── BoardView.kt         # Canvas 棋盘、拖拽甩子、瞄准线、坠落特效
```

开发过程与踩坑记录见 [PROGRESS.md](PROGRESS.md)。

## 致谢

- 玩法与数值来自 **CrazyChess v1.3.2**（原作者 Expedition107），本项目是对其规则的重写实现，未使用其贴图资源（音效除外，仅作个人学习用途）。
- 界面参考 **Material 3 Expressive** 设计语言。

## License

[MIT](LICENSE)
