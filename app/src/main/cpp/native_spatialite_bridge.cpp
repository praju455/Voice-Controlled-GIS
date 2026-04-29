#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>

namespace {
constexpr const char* kTag = "NativeSpatialiteBridge";
std::string g_driver_status = "Native bridge compiled; waiting for bundled Android libspatialite binaries.";
bool g_checked_spatialite = false;
bool g_has_spatialite = false;
void* g_spatialite_handle = nullptr;

using SpatialiteVersionFn = const char* (*)();

bool tryLoadLibrary(const char* library_name) {
    g_spatialite_handle = dlopen(library_name, RTLD_NOW | RTLD_LOCAL);
    if (g_spatialite_handle == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "dlopen failed for %s: %s", library_name, dlerror());
        return false;
    }

    const auto version_fn = reinterpret_cast<SpatialiteVersionFn>(dlsym(g_spatialite_handle, "spatialite_version"));
    if (version_fn == nullptr) {
        g_driver_status = std::string("Loaded ") + library_name + ", but spatialite_version symbol was not found.";
        __android_log_print(ANDROID_LOG_WARN, kTag, "%s", g_driver_status.c_str());
        return false;
    }

    const char* version = version_fn();
    g_driver_status = std::string("Loaded ") + library_name + " (" + (version != nullptr ? version : "unknown version") + ").";
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", g_driver_status.c_str());
    return true;
}

bool tryLoadWrapperLibrary(const char* library_name) {
    g_spatialite_handle = dlopen(library_name, RTLD_NOW | RTLD_LOCAL);
    if (g_spatialite_handle == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "dlopen failed for %s: %s", library_name, dlerror());
        return false;
    }

    g_driver_status = std::string("Loaded ") + library_name + " wrapper library.";
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", g_driver_status.c_str());
    return true;
}

void ensureSpatialiteChecked() {
    if (g_checked_spatialite) return;
    g_checked_spatialite = true;

    if (tryLoadLibrary("libspatialite.so")) {
        g_has_spatialite = true;
        return;
    }

    if (tryLoadLibrary("mod_spatialite.so")) {
        g_has_spatialite = true;
        return;
    }

    if (tryLoadWrapperLibrary("libandroid_spatialite.so")) {
        g_has_spatialite = true;
        return;
    }

    g_has_spatialite = false;
    g_driver_status = "Native bridge compiled; no bundled Android libspatialite/mod_spatialite library found yet.";
}
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
    ensureSpatialiteChecked();
    return g_has_spatialite ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_defense_tacticalmap_NativeSpatialiteBridge_nativeGetDriverStatus(
        JNIEnv* env,
        jobject) {
    ensureSpatialiteChecked();
    return env->NewStringUTF(g_driver_status.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_defense_tacticalmap_NativeSpatialiteBridge_nativeComputeBufferSummary(
        JNIEnv* env,
        jobject,
        jstring entity,
        jint distance_meters) {
    ensureSpatialiteChecked();
    const char* entity_chars = env->GetStringUTFChars(entity, nullptr);
    std::string summary = "Native driver online for ";
    summary += entity_chars;
    summary += " @ ";
    summary += std::to_string(distance_meters);
    summary += "m. ";
    summary += g_driver_status;
    env->ReleaseStringUTFChars(entity, entity_chars);
    return env->NewStringUTF(summary.c_str());
}
