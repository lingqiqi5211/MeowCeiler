# 只裁不混淆：hook 日志与堆栈要能对回源码，反射用的自家类名也不能变。
-dontobfuscate

# Xposed 入口按 META-INF/xposed/java_init.list 里的名字加载。
-keep class io.github.lingqiqi.meowceiler.hook.HookEntry { *; }
-keep class io.github.libxposed.api.** { *; }
-keep class io.github.lingqiqi5211.ezhooktool.** { *; }

# 日志用类名当功能标签。
-keepnames class io.github.lingqiqi.meowceiler.hook.** { *; }
