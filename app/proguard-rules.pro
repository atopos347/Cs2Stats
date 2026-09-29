# R8 / ProGuard 规则

# ---- 本地 demo 解析（libcs2demo.so）----
# Go 侧通过 JNI 导出符号 Java_com_cs2stats_app_data_demo_DemoNative_* 找入口，
# 一旦类名或方法名被混淆就 UnsatisfiedLinkError。两个规则都留着：
# -keepclasseswithmembernames 保住「类名+方法名」，-keep 保住 object 与字段布局。
-keepclasseswithmembernames,includedescriptorclasses class com.cs2stats.app.data.demo.DemoNative {
    native <methods>;
}
-keep class com.cs2stats.app.data.demo.DemoNative { *; }

# 服务组件靠 manifest 反射启动，不能被移除
-keep class com.cs2stats.app.data.demo.DemoParseService { *; }
