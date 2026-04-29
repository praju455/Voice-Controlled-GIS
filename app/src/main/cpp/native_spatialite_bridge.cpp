#include <jni.h>
#include <android/log.h>
#include <string>

namespace {
constexpr const char* kTag = "NativeSpatialiteBridge";
constexpr const char* kDriverStatus = "Native bridge compiled; waiting for bundled libspatialite binaries.";
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_defense_tacticalmap_NativeSpatialiteBridge_nativeIsBridgeReady(
        JNIEnv*,
        jobject) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "Native bridge loaded successfully.");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_defense_tacticalmap_NativeSpatialiteBridge_nativeHasSpatialiteSupport(
        JNIEnv*,
        jobject) {
    return JNI_FALSE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_defense_tacticalmap_NativeSpatialiteBridge_nativeGetDriverStatus(
        JNIEnv* env,
        jobject) {
    return env->NewStringUTF(kDriverStatus);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_defense_tacticalmap_NativeSpatialiteBridge_nativeComputeBufferSummary(
        JNIEnv* env,
        jobject,
        jstring entity,
        jint distance_meters) {
    const char* entity_chars = env->GetStringUTFChars(entity, nullptr);
    std::string summary = "Native driver online for ";
    summary += entity_chars;
    summary += " @ ";
    summary += std::to_string(distance_meters);
    summary += "m, but libspatialite is not linked yet.";
    env->ReleaseStringUTFChars(entity, entity_chars);
    return env->NewStringUTF(summary.c_str());
}
