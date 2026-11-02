#include "napi/native_api.h"

#include <dlfcn.h>
#include <cstdint>
#include <mutex>
#include <string>

using ProbeVersionFn = char *(*)(void);
using ProbeAddFn = int32_t (*)(int32_t, int32_t);
using ProbeFreeFn = void (*)(char *);

static std::once_flag g_loadOnce;
static void *g_coreHandle = nullptr;
static ProbeVersionFn g_probeVersion = nullptr;
static ProbeAddFn g_probeAdd = nullptr;
static ProbeFreeFn g_probeFree = nullptr;
static std::string g_loadError;

static void EnsureCore()
{
    std::call_once(g_loadOnce, []() {
        g_coreHandle = dlopen("libzju_go_probe.so", RTLD_NOW | RTLD_GLOBAL);
        if (g_coreHandle == nullptr) {
            const char *error = dlerror();
            g_loadError = error == nullptr ? "dlopen failed without an error message" : error;
            return;
        }

        g_probeVersion = reinterpret_cast<ProbeVersionFn>(dlsym(g_coreHandle, "ZJUProbeVersion"));
        g_probeAdd = reinterpret_cast<ProbeAddFn>(dlsym(g_coreHandle, "ZJUProbeAdd"));
        g_probeFree = reinterpret_cast<ProbeFreeFn>(dlsym(g_coreHandle, "ZJUProbeFree"));
        if (g_probeVersion == nullptr || g_probeAdd == nullptr || g_probeFree == nullptr) {
            const char *error = dlerror();
            g_loadError = error == nullptr ? "required Go export is missing" : error;
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

    const bool loaded = g_probeVersion != nullptr && g_probeAdd != nullptr && g_probeFree != nullptr;
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

static napi_value Init(napi_env env, napi_value exports)
{
    napi_property_descriptor properties[] = {
        {"probe", nullptr, Probe, nullptr, nullptr, nullptr, napi_default, nullptr},
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
