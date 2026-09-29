#include "jni_helpers.h"

#include <stdlib.h>
#include <string.h>

jbyteArray cs2_bytes(JNIEnv *env, const char *data, int len) {
    if (data == NULL || len < 0) {
        return NULL;
    }
    jbyteArray arr = (*env)->NewByteArray(env, (jsize)len);
    if (arr == NULL) {
        return NULL; /* OOM，Java 侧会抛 OutOfMemoryError */
    }
    (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (const jbyte *)data);
    return arr;
}

const char *cs2_get_string(JNIEnv *env, jstring s) {
    if (s == NULL) {
        return NULL;
    }
    return (*env)->GetStringUTFChars(env, s, NULL);
}

void cs2_release_string(JNIEnv *env, jstring s, const char *c) {
    if (s != NULL && c != NULL) {
        (*env)->ReleaseStringUTFChars(env, s, c);
    }
}

jstring cs2_new_string(JNIEnv *env, const char *s) {
    if (s == NULL) {
        return NULL;
    }
    return (*env)->NewStringUTF(env, s);
}

int cs2_jstring_null(jstring s) { return s == NULL ? 1 : 0; }

int cs2_jbytearray_null(jbyteArray a) { return a == NULL ? 1 : 0; }

jbyteArray cs2_null_bytes(void) { return NULL; }
