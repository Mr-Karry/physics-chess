# 弹棋 —— R8 规则
#
# 这是纯 Compose 应用：没有反射、没有 JNI、没有序列化框架，
# 默认的 AGP + Compose 规则已经够用，这里只做两点保守加固。

# 保留行号信息，崩溃栈才可读。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Compose 运行时通过 @Composable 的注解信息做部分检查，别裁掉注解。
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# Kotlin 协程的调试/内部实现偶尔会触发缺失类的告警，保持构建干净。
-dontwarn kotlinx.coroutines.**
-dontwarn kotlin.**

# SoundPool 只按资源 id 加载，不需要额外规则；
# GameEngine / UI 全部是普通 Kotlin 代码，可安全混淆与裁剪。
