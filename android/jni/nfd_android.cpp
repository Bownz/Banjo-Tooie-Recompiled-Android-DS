// Android implementation of the nativefiledialog-extended API.
//
// nfd has no Android backend, but the launcher uses it to pick the ROM (and mods).
// These functions call BTActivity.nfdRequest() over JNI, which shows Android's
// system file picker, copies the chosen document(s) into app storage and returns
// real filesystem paths. The call blocks the calling native thread until the user
// picks or cancels; the Android UI thread is never blocked.

#include "nfd.h"

#include <SDL.h>
#include <SDL_system.h>
#include <android/log.h>
#include <jni.h>

#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {
    // Must match the constants in BTActivity.java.
    enum RequestMode : jint {
        OpenFile = 0,
        OpenMultiple = 1,
    };

    std::string last_error;

    struct PathSet {
        std::vector<std::string> paths;
    };

    void set_error(const char* message) {
        last_error = message;
        __android_log_print(ANDROID_LOG_WARN, "BTRecomp", "nfd: %s", message);
    }

    char* duplicate(const std::string& value) {
        char* out = static_cast<char*>(std::malloc(value.size() + 1));
        if (out != nullptr) {
            std::memcpy(out, value.c_str(), value.size() + 1);
        }
        return out;
    }

    // Returns NFD_OKAY and fills `out` with newline-separated paths, NFD_CANCEL when the
    // user backs out, or NFD_ERROR.
    nfdresult_t request(RequestMode mode, std::string& out) {
        JNIEnv* env = static_cast<JNIEnv*>(SDL_AndroidGetJNIEnv());
        jobject activity = static_cast<jobject>(SDL_AndroidGetActivity());
        if (env == nullptr || activity == nullptr) {
            set_error("No Android activity available");
            return NFD_ERROR;
        }

        nfdresult_t result = NFD_ERROR;
        jclass activity_class = env->GetObjectClass(activity);
        jmethodID method = env->GetMethodID(activity_class, "nfdRequest", "(I)Ljava/lang/String;");
        if (method == nullptr) {
            env->ExceptionClear();
            set_error("BTActivity.nfdRequest(int) not found");
        }
        else {
            auto java_result = static_cast<jstring>(env->CallObjectMethod(activity, method, static_cast<jint>(mode)));
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                set_error("File picker threw an exception");
            }
            else if (java_result == nullptr) {
                result = NFD_CANCEL;
            }
            else {
                const char* chars = env->GetStringUTFChars(java_result, nullptr);
                out = chars;
                env->ReleaseStringUTFChars(java_result, chars);
                env->DeleteLocalRef(java_result);
                result = out.empty() ? NFD_CANCEL : NFD_OKAY;
            }
        }

        env->DeleteLocalRef(activity_class);
        env->DeleteLocalRef(activity);
        return result;
    }
}

extern "C" {

nfdresult_t NFD_Init(void) { return NFD_OKAY; }
void NFD_Quit(void) {}

const char* NFD_GetError(void) { return last_error.empty() ? nullptr : last_error.c_str(); }
void NFD_ClearError(void) { last_error.clear(); }

void NFD_FreePathN(nfdnchar_t* filePath) { std::free(filePath); }
void NFD_FreePathU8(nfdu8char_t* filePath) { std::free(filePath); }

// ---- Single file -----------------------------------------------------------------

nfdresult_t NFD_OpenDialogN(nfdnchar_t** outPath, const nfdnfilteritem_t*, nfdfiltersize_t, const nfdnchar_t*) {
    std::string path;
    nfdresult_t result = request(OpenFile, path);
    if (result == NFD_OKAY) {
        *outPath = duplicate(path);
    }
    return result;
}

nfdresult_t NFD_OpenDialogU8(nfdu8char_t** outPath, const nfdu8filteritem_t* filterList, nfdfiltersize_t filterCount, const nfdu8char_t* defaultPath) {
    return NFD_OpenDialogN(outPath, filterList, filterCount, defaultPath);
}

// ---- Multiple files --------------------------------------------------------------

nfdresult_t NFD_OpenDialogMultipleN(const nfdpathset_t** outPaths, const nfdnfilteritem_t*, nfdfiltersize_t, const nfdnchar_t*) {
    std::string joined;
    nfdresult_t result = request(OpenMultiple, joined);
    if (result != NFD_OKAY) {
        return result;
    }

    auto* set = new PathSet{};
    size_t start = 0;
    while (start <= joined.size()) {
        size_t end = joined.find('\n', start);
        if (end == std::string::npos) {
            end = joined.size();
        }
        if (end > start) {
            set->paths.emplace_back(joined.substr(start, end - start));
        }
        start = end + 1;
    }
    *outPaths = set;
    return NFD_OKAY;
}

nfdresult_t NFD_OpenDialogMultipleU8(const nfdpathset_t** outPaths, const nfdu8filteritem_t* filterList, nfdfiltersize_t filterCount, const nfdu8char_t* defaultPath) {
    return NFD_OpenDialogMultipleN(outPaths, filterList, filterCount, defaultPath);
}

// ---- Not supported on Android ----------------------------------------------------
// The system picker returns document URIs, not directories or writable paths, so
// these report an error. Users can reach the app folder directly instead
// (Android/data/<package>/files).

nfdresult_t NFD_SaveDialogN(nfdnchar_t**, const nfdnfilteritem_t*, nfdfiltersize_t, const nfdnchar_t*, const nfdnchar_t*) {
    set_error("Save dialogs are not supported on Android");
    return NFD_ERROR;
}
nfdresult_t NFD_SaveDialogU8(nfdu8char_t** outPath, const nfdu8filteritem_t* filterList, nfdfiltersize_t filterCount, const nfdu8char_t* defaultPath, const nfdu8char_t* defaultName) {
    return NFD_SaveDialogN(outPath, filterList, filterCount, defaultPath, defaultName);
}
nfdresult_t NFD_PickFolderN(nfdnchar_t**, const nfdnchar_t*) {
    set_error("Folder dialogs are not supported on Android");
    return NFD_ERROR;
}
nfdresult_t NFD_PickFolderU8(nfdu8char_t** outPath, const nfdu8char_t* defaultPath) {
    return NFD_PickFolderN(outPath, defaultPath);
}
nfdresult_t NFD_PickFolderMultipleN(const nfdpathset_t**, const nfdnchar_t*) {
    set_error("Folder dialogs are not supported on Android");
    return NFD_ERROR;
}
nfdresult_t NFD_PickFolderMultipleU8(const nfdpathset_t** outPaths, const nfdu8char_t* defaultPath) {
    return NFD_PickFolderMultipleN(outPaths, defaultPath);
}
// ---- Path sets -------------------------------------------------------------------

nfdresult_t NFD_PathSet_GetCount(const nfdpathset_t* pathSet, nfdpathsetsize_t* count) {
    *count = static_cast<nfdpathsetsize_t>(static_cast<const PathSet*>(pathSet)->paths.size());
    return NFD_OKAY;
}

nfdresult_t NFD_PathSet_GetPathN(const nfdpathset_t* pathSet, nfdpathsetsize_t index, nfdnchar_t** outPath) {
    const auto* set = static_cast<const PathSet*>(pathSet);
    if (index >= set->paths.size()) {
        set_error("Path set index out of range");
        return NFD_ERROR;
    }
    *outPath = duplicate(set->paths[index]);
    return NFD_OKAY;
}

nfdresult_t NFD_PathSet_GetPathU8(const nfdpathset_t* pathSet, nfdpathsetsize_t index, nfdu8char_t** outPath) {
    return NFD_PathSet_GetPathN(pathSet, index, outPath);
}

void NFD_PathSet_FreePathN(const nfdnchar_t* filePath) { std::free(const_cast<nfdnchar_t*>(filePath)); }
void NFD_PathSet_FreePathU8(const nfdu8char_t* filePath) { std::free(const_cast<nfdu8char_t*>(filePath)); }

nfdresult_t NFD_PathSet_GetEnum(const nfdpathset_t*, nfdpathsetenum_t* outEnumerator) {
    // Not used by the game or RT64; GetCount/GetPath cover every caller.
    outEnumerator->ptr = nullptr;
    set_error("Path set enumeration is not supported on Android");
    return NFD_ERROR;
}

void NFD_PathSet_FreeEnum(nfdpathsetenum_t*) {}

void NFD_PathSet_Free(const nfdpathset_t* pathSet) { delete static_cast<const PathSet*>(pathSet); }

} // extern "C"
