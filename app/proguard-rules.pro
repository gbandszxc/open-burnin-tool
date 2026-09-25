# 煲机助手 ProGuard/R8 规则。
#
# AGP 9 下 R8 full mode 始终开启，AndroidX / Room / Kotlin 各自的 keep 规则由库的
# consumer rules 自动引入（见 build/intermediates 的 aar-metadata），本文件只补充
# 应用自身的规则，无需再手写 dontwarn。

# 崩溃栈可读化：保留源文件名与行号，配合 build/outputs/mapping/release/mapping.txt
# 即可还原混淆后的堆栈；同时抹掉原始文件名，避免泄露工程内路径。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 裁剪 verbose/debug 级别日志。release 包没有 logcat 采集价值，少留信息面。
# 有意保留 i/w/e —— 未接入崩溃上报时，它们是 release 出问题后唯一的本地诊断通道。
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
