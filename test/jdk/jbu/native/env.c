#include <jni.h>
#include <stdio.h>
#include <stdlib.h>

/*
 * Class:     quality_util_EnvUtil
 * Method:    setenv
 * Signature: (Ljava/lang/String;Ljava/lang/String;I)I
 */
JNIEXPORT jint JNICALL Java_quality_util_EnvUtil_setenv
  (JNIEnv *env, jclass c, jstring var, jstring val, jint overwrite)
{
    const char *varStr, *valStr;
    varStr = (*env)->GetStringUTFChars(env, var, NULL);
    if (varStr == NULL) {
        return 1;
    }

    valStr = (*env)->GetStringUTFChars(env, val, NULL);
    if (valStr == NULL) {
        return 1;
    }
    return setenv(varStr, valStr, overwrite);
}

/*
 * Class:     quality_util_EnvUtil
 * Method:    getenv
 * Signature: (Ljava/lang/String;)Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_quality_util_EnvUtil_getenv
  (JNIEnv *env, jclass c, jstring var)
{
    char* valStr;
    const char* varStr = (*env)->GetStringUTFChars(env, var, NULL);

    if (varStr == NULL) return NULL;

    valStr = getenv(varStr);

    if (valStr == NULL) {
        (*env)->ReleaseStringUTFChars(env, var, varStr);
        return NULL;
    }

    (*env)->ReleaseStringUTFChars(env, var, varStr);

    return (*env)->NewStringUTF(env, valStr);
}

