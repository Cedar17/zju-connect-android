#include "napi/native_api.h"

#include <dlfcn.h>
#include <cstdint>
#include <mutex>
#include <string>

using ProbeVersionFn = char *(*)(void);
using ProbeAddFn = int32_t (*)(int32_t, int32_t);
using ProbeFreeFn = void (*)(char *);
using ProbeFdValidFn = int32_t (*)(int32_t);

static std::once_flag g_loadOnce;
static void *g_coreHandle = nullptr;
static ProbeVersionFn g_probeVersion = nullptr;
static ProbeAddFn g_probeAdd = nullptr;
static ProbeFreeFn g_probeFree = nullptr;
static ProbeFdValidFn g_probeFdValid = nullptr;
static std::string g_loadError;

static void EnsureCore()
{
    std::call_once(g_loadOnce, []() {
        Dl_info moduleInfo{};
        std::string libraryPath;
        if (dladdr(reinterpret_cast<void *>(&EnsureCore), &moduleInfo) != 0 &&
            moduleInfo.dli_fname != nullptr) {
            libraryPath = moduleInfo.dli_fname;
            const size_t separator = libraryPath.find_last_of('/');
            if (separator != std::string::npos) {
                libraryPath.resize(separator + 1);
                libraryPath += "libzju_go_probe.so";
            } else {
                libraryPath.clear();
            }
        }

        if (!libraryPath.empty()) {
            dlerror();
            g_coreHandle = dlopen(libraryPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
            if (g_coreHandle == nullptr) {
                const char *error = dlerror();
                g_loadError = "dlopen " + libraryPath + ": " +
                    (error == nullptr ? "unknown error" : error);
            }
        }

        if (g_coreHandle == nullptr) {
            dlerror();
            g_coreHandle = dlopen("libzju_go_probe.so", RTLD_NOW | RTLD_GLOBAL);
            if (g_coreHandle == nullptr) {
                const char *error = dlerror();
                const std::string fallbackError =
                    error == nullptr ? "unknown error" : error;
                if (!g_loadError.empty()) {
                    g_loadError += "; fallback: " + fallbackError;
                } else {
                    g_loadError = "dlopen libzju_go_probe.so: " + fallbackError;
                }
                return;
            }
        }

        dlerror();
        g_probeVersion = reinterpret_cast<ProbeVersionFn>(
            dlsym(g_coreHandle, "ZJUProbeVersion"));
        g_probeAdd = reinterpret_cast<ProbeAddFn>(
            dlsym(g_coreHandle, "ZJUProbeAdd"));
        g_probeFree = reinterpret_cast<ProbeFreeFn>(
            dlsym(g_coreHandle, "ZJUProbeFree"));
        g_probeFdValid = reinterpret_cast<ProbeFdValidFn>(
            dlsym(g_coreHandle, "ZJUProbeFdValid"));
        const char *symbolError = dlerror();

        if (g_probeVersion == nullptr || g_probeAdd == nullptr || g_probeFree == nullptr ||
            g_probeFdValid == nullptr) {
            g_loadError = symbolError == nullptr
                ? "one or more required Go exports are missing"
                : symbolError;
        }
    });
}

static void SetString(napi_env env, napi_value object, const char *name, const std::string &value)
{
    napi_value stringValue = nullptr;
    napi_create_string_utf8(env, value.c_str(), NAPI_AUTO_LENGTH, &stringValue);
    napi_set_named_property(env, object, name, stringValue);
}

static napi_value Probe(napi_env env, napi_callback_info)
{
    EnsureCore();

    napi_value result = nullptr;
    napi_create_object(env, &result);

    const bool loaded = g_probeVersion != nullptr && g_probeAdd != nullptr &&
        g_probeFree != nullptr && g_probeFdValid != nullptr;
    napi_value loadedValue = nullptr;
    napi_create_boolean(env, loaded, &loadedValue);
    napi_set_named_property(env, result, "loaded", loadedValue);

    std::string version;
    int32_t addResult = 0;
    if (loaded) {
        char *value = g_probeVersion();
        if (value != nullptr) {
            version.assign(value);
            g_probeFree(value);
        }
        addResult = g_probeAdd(19, 23);
    }

    SetString(env, result, "version", version);
    SetString(env, result, "error", g_loadError);

    napi_value addValue = nullptr;
    napi_create_int32(env, addResult, &addValue);
    napi_set_named_property(env, result, "addResult", addValue);
    return result;
}

static napi_value InspectFd(napi_env env, napi_callback_info info)
{
    EnsureCore();
    if (g_probeFdValid == nullptr) {
        napi_throw_error(env, nullptr, g_loadError.empty() ?
            "Go probe is unavailable" : g_loadError.c_str());
        return nullptr;
    }

    size_t argc = 1;
    napi_value args[1] = { nullptr };
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    int32_t fd = -1;
    if (argc != 1 || napi_get_value_int32(env, args[0], &fd) != napi_ok) {
        napi_throw_type_error(env, nullptr, "inspectFd requires an integer fd");
        return nullptr;
    }

    napi_value valid = nullptr;
    napi_create_boolean(env, g_probeFdValid(fd) == 1, &valid);
    return valid;
}

static napi_value Init(napi_env env, napi_value exports)
{
    napi_property_descriptor properties[] = {
        {"probe", nullptr, Probe, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"inspectFd", nullptr, InspectFd, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof(properties) / sizeof(properties[0]), properties);
    return exports;
}

static napi_module g_module = {
    1,
    0,
    nullptr,
    Init,
    "zjuprobe_bridge",
    nullptr,
    {0}
};

extern "C" __attribute__((constructor)) void RegisterZjuProbeBridge(void)
{
    napi_module_register(&g_module);
}
