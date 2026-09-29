// jni —— 安卓 JNI 导出层：把 demostats 解析器编译成 libcs2demo.so（arm64-v8a）。
//
// 交叉编译（在仓库根目录的 tools/demoparse 下执行，见 build-android.ps1）：
//
//	set  GOOS=android
//	set  GOARCH=arm64
//	set  CGO_ENABLED=1
//	set  CC=D:\android-sdk\ndk\27.2.12479018\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang.cmd
//	go build -buildmode=c-shared -o ..\..\app\src\main\jniLibs\arm64-v8a\libcs2demo.so .\cmd\jni
//
// Kotlin 侧入口（签名必须与下面的导出符号一一对应）：
//
//	object DemoNative {
//	    external fun parseJson(path: String): ByteArray   // UTF-8 JSON（含 "error" 字段即失败）
//	    external fun progress(): Int                      // 0..10000
//	    external fun cancel()
//	}
package main

/*
#cgo CFLAGS: -I${SRCDIR}
#include <jni.h>
#include <stdlib.h>
#include "jni_helpers.h"
*/
import "C"

import (
	"fmt"
	"runtime/debug"
	"sync"
	"sync/atomic"
	"unsafe"

	"cs2stats/demoparse/internal/demostats"
)

func init() {
	// 手机解析一场 CS2 demo 峰值内存几百 MB，给 Go 运行时一个 1GiB 上限：
	// 内存吃紧时宁可多跑 GC，也不要被 lmkd 整个进程杀掉。
	debug.SetMemoryLimit(1 << 30)
}

var (
	progress atomic.Uint64 // 进度 * 10000（跨线程安全）
	parseMu  sync.Mutex    // 同一时间只解析一个
	canceled atomic.Bool
)

//export Java_com_cs2stats_app_data_demo_DemoNative_parseJson
// 解析 demo（阻塞，必须在工作线程调用）。返回 UTF-8 JSON 字节数组；
// 失败时返回 {"version":1,"error":"..."}，调用方用 Kotlin 的 String(bytes, UTF_8) 解码。
func Java_com_cs2stats_app_data_demo_DemoNative_parseJson(env *C.JNIEnv, cls C.jclass, jpath C.jstring) C.jbyteArray {
	_ = cls
	if C.cs2_jstring_null(jpath) != 0 {
		return cs2Error(env, "路径为空")
	}
	cpath := C.cs2_get_string(env, jpath)
	if cpath == nil {
		return cs2Error(env, "无法读取路径")
	}
	path := C.GoString(cpath)
	C.cs2_release_string(env, jpath, cpath)

	if !parseMu.TryLock() {
		return cs2Error(env, "已有解析任务在进行中")
	}
	defer parseMu.Unlock()
	canceled.Store(false)
	progress.Store(0)

	res, err := demostats.ParseCancel(path, func(p float64) {
		progress.Store(uint64(p * 10000))
	}, canceled.Load)

	js := res.JSON(false)
	if err != nil {
		js = fmt.Sprintf(`{"version":%d,"error":%s}`, demostats.Version, jsonQuote(err.Error()))
	}
	return cs2Bytes(env, js)
}

//export Java_com_cs2stats_app_data_demo_DemoNative_progress
// 当前解析进度 0..10000（万分比）。未开始 = 0，结束 = 10000。
func Java_com_cs2stats_app_data_demo_DemoNative_progress(env *C.JNIEnv, cls C.jclass) C.jint {
	_ = env
	_ = cls
	return C.jint(progress.Load())
}

//export Java_com_cs2stats_app_data_demo_DemoNative_cancel
// 请求取消当前解析：正在读的 reader 会返回错误，ParseToEnd 随即中断。
func Java_com_cs2stats_app_data_demo_DemoNative_cancel(env *C.JNIEnv, cls C.jclass) {
	_ = env
	_ = cls
	canceled.Store(true)
}

func main() {}

// ---------- C 边界小工具 ----------

func cs2Bytes(env *C.JNIEnv, js string) C.jbyteArray {
	if js == "" {
		return C.cs2_null_bytes()
	}
	c := C.CString(js)
	defer C.free(unsafe.Pointer(c))
	return C.cs2_bytes(env, c, C.int(len(js)))
}

func cs2Error(env *C.JNIEnv, msg string) C.jbyteArray {
	return cs2Bytes(env, fmt.Sprintf(`{"version":%d,"error":%s}`, demostats.Version, jsonQuote(msg)))
}

// jsonQuote 把任意字符串转义成合法 JSON 字符串字面量（含引号）。
func jsonQuote(s string) string {
	const hex = "0123456789abcdef"
	b := make([]byte, 0, len(s)+2)
	b = append(b, '"')
	for _, r := range s {
		switch r {
		case '"':
			b = append(b, '\\', '"')
		case '\\':
			b = append(b, '\\', '\\')
		case '\n':
			b = append(b, '\\', 'n')
		case '\r':
			b = append(b, '\\', 'r')
		case '\t':
			b = append(b, '\\', 't')
		default:
			if r < 0x20 {
				b = append(b, '\\', 'u', '0', '0', hex[r>>4], hex[r&0xf])
			} else {
				b = append(b, []byte(string(r))...)
			}
		}
	}
	b = append(b, '"')
	return string(b)
}
