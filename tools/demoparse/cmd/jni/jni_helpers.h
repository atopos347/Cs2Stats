#ifndef CS2_JNI_HELPERS_H
#define CS2_JNI_HELPERS_H

/*
 * JNI 辅助函数（只放声明，实现放 jni_helpers.c）。
 *
 * cgo 的规则：文件里出现 //export 时，其 preamble **只能有声明、不能有函数定义**，
 * 所以不能把这些 helper 直接写在 Go 文件的注释块里。
 *
 * 实现放在独立的 .c 文件，cgo 会自动一并编译（包目录下所有 .c/.h 都参与交叉编译）。
 * jni.h 由 NDK 的 clang 驱动从 sysroot 里带进来，无需额外 -I。
 */

#include <jni.h>

/* 把一段 UTF-8 字节拷成 Java byte[]（避免 Modified UTF-8 对 4 字节字符的破坏，emoji 名字也安全）。 */
jbyteArray cs2_bytes(JNIEnv *env, const char *data, int len);

/* 取 Java String 的 UTF-8 内容，返回值必须配对调用 cs2_release_string。 */
const char *cs2_get_string(JNIEnv *env, jstring s);
void cs2_release_string(JNIEnv *env, jstring s, const char *c);

/* 新建 Java String（MUTF-8，仅用于纯 ASCII 消息）。 */
jstring cs2_new_string(JNIEnv *env, const char *s);

/*
 * 空值判断放在 C 侧。
 *
 * jstring / jbyteArray 在 C 下是 `typedef void* jobject` 这条链上的别名，
 * cgo 把它映射成自己的 _Ctype_* 具名类型后，Go 侧写 `x == nil` 会报
 * 「mismatched types ... and untyped nil」。这里用 C 函数判空就绕开了。
 */
int cs2_jstring_null(jstring s);
int cs2_jbytearray_null(jbyteArray a);

/* 返回 NULL 的 byte[]，用于 Go 侧「必须返回空数组」的分支。 */
jbyteArray cs2_null_bytes(void);

#endif /* CS2_JNI_HELPERS_H */
