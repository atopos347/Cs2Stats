package com.cs2stats.app.data.demo

/**
 * Go 解析器（`tools/demoparse/cmd/jni` → `libcs2demo.so`）的 JNI 绑定。
 *
 * 符号必须与 Go 侧 `//export Java_com_cs2stats_app_data_demo_DemoNative_*` 一一对上：
 * `build-android.ps1` 会用 `llvm-nm` 校验这三个符号，漏一个就构建失败。
 *
 * ABI 说明：只编了 **arm64-v8a**（APK 体积 +12MB 左右）。其它架构上
 * [System.loadLibrary] 会抛 `UnsatisfiedLinkError`，[available] 恒为 false，
 * 界面直接隐藏解析入口——**不崩、不假装能用**。
 *
 * 线程模型：[parseJson] 阻塞直到解析结束（手机上约 1~3 分钟），只能在工作线程调；
 * [progress] / [cancel] 随时可调（Go 侧用原子量，无锁）。
 */
object DemoNative {

    /** 本机型能否本地解析 demo（只有 arm64-v8a 带了 .so）。 */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("cs2demo")
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 解析 demo 文件（`.dem` / `.dem.bz2`），返回 **UTF-8 JSON 字节数组**。
     *
     * 成功形如 `{version, parser, mapName, durationSec, hasEconomy, players[], rounds[]}`；
     * 失败形如 `{"version":1,"error":"..."}` —— 调用方解码后自行看 `error` 字段。
     *
     * 用 `ByteArray` 而不是 `String` 返回，是为了绕开 JNI `NewStringUTF` 的 Modified UTF-8
     * 限制（玩家名里有 4 字节 emoji 时会乱码）。
     */
    external fun parseJson(path: String): ByteArray

    /** 当前解析进度 0..10000（万分比）。未开始 = 0，结束 = 10000。 */
    external fun progress(): Int

    /** 请求取消当前解析；正在读文件的 reader 会立即返回错误，解析随之中断。 */
    external fun cancel()
}
