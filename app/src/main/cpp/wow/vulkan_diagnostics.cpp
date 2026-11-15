// A host-side probe and a transparent tracing ICD in front of the existing Bionic wrapper.
// No GPU names, API versions, extensions or feature bits are rewritten here.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#define EXPORT extern "C" __attribute__((visibility("default")))

static std::string version(uint32_t v) {
    return std::to_string(VK_API_VERSION_MAJOR(v)) + "." +
        std::to_string(VK_API_VERSION_MINOR(v)) + "." + std::to_string(VK_API_VERSION_PATCH(v));
}
static const char* resultName(VkResult r) {
    switch (r) {
        case VK_SUCCESS: return "VK_SUCCESS";
        case VK_TIMEOUT: return "VK_TIMEOUT";
        case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
        case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
        case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
        case VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
        case VK_ERROR_INCOMPATIBLE_DRIVER: return "VK_ERROR_INCOMPATIBLE_DRIVER";
        case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
        case VK_ERROR_FEATURE_NOT_PRESENT: return "VK_ERROR_FEATURE_NOT_PRESENT";
        case VK_ERROR_LAYER_NOT_PRESENT: return "VK_ERROR_LAYER_NOT_PRESENT";
        default: return "VkResult";
    }
}
static std::string result(VkResult r) {
    return std::string(resultName(r)) + " (" + std::to_string(r) + ")";
}
static void logLine(const std::string& line) {
    __android_log_print(ANDROID_LOG_INFO, "WoWVulkan", "%s", line.c_str());
    fprintf(stderr, "WoWVulkan: %s\n", line.c_str());
    const char* path = getenv("WOW_VULKAN_LOG");
    if (path && *path) {
        FILE* f = fopen(path, "a");
        if (f) { fprintf(f, "%s\n", line.c_str()); fclose(f); }
    }
}
template<typename T> static T proc(PFN_vkGetInstanceProcAddr g, VkInstance i, const char* name) {
    return reinterpret_cast<T>(g(i, name));
}

static std::string probe() {
    std::ostringstream out;
    auto say = [&](const std::string& s) { out << s << '\n'; logLine("system probe: " + s); };
    auto fail = [&](const std::string& s) { say("RESULT: FAIL: " + s); return out.str(); };
    void* lib = dlopen("/system/lib64/libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) return fail(std::string("dlopen Android Vulkan loader: ") + dlerror());
    // Keep the Android loader loaded for the process lifetime.
    auto g = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr"));
    if (!g) return fail("Android loader has no vkGetInstanceProcAddr");
    uint32_t api = VK_API_VERSION_1_0;
    auto ev = proc<PFN_vkEnumerateInstanceVersion>(g, VK_NULL_HANDLE, "vkEnumerateInstanceVersion");
    if (ev) { VkResult r = ev(&api); if (r != VK_SUCCESS) return fail("vkEnumerateInstanceVersion: " + result(r)); }
    say("loaderApiVersion=" + version(api));
    auto ei = proc<PFN_vkEnumerateInstanceExtensionProperties>(g, VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties");
    if (!ei) return fail("Missing vkEnumerateInstanceExtensionProperties");
    uint32_t count = 0;
    VkResult r = ei(nullptr, &count, nullptr);
    if (r != VK_SUCCESS) return fail("instance extension count: " + result(r));
    std::vector<VkExtensionProperties> exts(count);
    r = ei(nullptr, &count, exts.data());
    if (r != VK_SUCCESS) return fail("instance extensions: " + result(r));
    exts.resize(count);
    for (const auto& e : exts) say(std::string("instanceExtension=") + e.extensionName);
    if (api < VK_API_VERSION_1_3) return fail("DXVK 2.4.1 requires Vulkan 1.3; Android loader exposes " + version(api));
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO, nullptr, "WoW Vulkan probe", 1,
        "DXVK", VK_MAKE_VERSION(2, 4, 1), VK_API_VERSION_1_3};
    VkInstanceCreateInfo ci{}; ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO; ci.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    auto create = proc<PFN_vkCreateInstance>(g, VK_NULL_HANDLE, "vkCreateInstance");
    if (!create) return fail("Missing vkCreateInstance");
    r = create(&ci, nullptr, &instance);
    say("vkCreateInstance=" + result(r));
    if (r != VK_SUCCESS) return fail("vkCreateInstance: " + result(r));
    auto destroy = proc<PFN_vkDestroyInstance>(g, instance, "vkDestroyInstance");
    auto finish = [&](const std::string& error) {
        destroy(instance, nullptr);
        if (!error.empty()) return fail(error);
        say("RESULT: PASS: native Vulkan device and queue submission succeeded; Wine/DXVK/WSI remain unverified");
        return out.str();
    };
    auto ep = proc<PFN_vkEnumeratePhysicalDevices>(g, instance, "vkEnumeratePhysicalDevices");
    count = 0; r = ep(instance, &count, nullptr);
    if (r != VK_SUCCESS || !count) return finish("vkEnumeratePhysicalDevices: " + result(r) + "; count=" + std::to_string(count));
    std::vector<VkPhysicalDevice> devices(count);
    r = ep(instance, &count, devices.data());
    if (r != VK_SUCCESS) return finish("vkEnumeratePhysicalDevices: " + result(r));
    // Android typically exposes one GPU. Record all devices; use the first like the launcher.
    auto props = proc<PFN_vkGetPhysicalDeviceProperties>(g, instance, "vkGetPhysicalDeviceProperties");
    auto ee = proc<PFN_vkEnumerateDeviceExtensionProperties>(g, instance, "vkEnumerateDeviceExtensionProperties");
    for (uint32_t n = 0; n < count; n++) {
        VkPhysicalDeviceProperties p{}; props(devices[n], &p);
        say("GPU[" + std::to_string(n) + "]=" + p.deviceName + "; vendorID=" + std::to_string(p.vendorID) +
            "; deviceID=" + std::to_string(p.deviceID) + "; driverVersion(raw)=" + std::to_string(p.driverVersion) +
            "; apiVersion=" + version(p.apiVersion));
    }
    VkPhysicalDevice physical = devices[0];
    VkPhysicalDeviceProperties p{}; props(physical, &p);
    uint32_t ec = 0; r = ee(physical, nullptr, &ec, nullptr);
    if (r != VK_SUCCESS) return finish("device extension count: " + result(r));
    exts.resize(ec); r = ee(physical, nullptr, &ec, exts.data());
    if (r != VK_SUCCESS) return finish("device extensions: " + result(r));
    exts.resize(ec);
    bool swapchain = false;
    for (const auto& e : exts) {
        say(std::string("deviceExtension=") + e.extensionName);
        if (!strcmp(e.extensionName, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) swapchain = true;
    }
    if (p.apiVersion < VK_API_VERSION_1_3) return finish("DXVK 2.4.1 requires physical-device Vulkan 1.3; got " + version(p.apiVersion));
    auto getProps2 = proc<PFN_vkGetPhysicalDeviceProperties2>(g, instance, "vkGetPhysicalDeviceProperties2");
    VkPhysicalDeviceDriverProperties driver{}; driver.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;
    VkPhysicalDeviceProperties2 p2{}; p2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2; p2.pNext = &driver;
    getProps2(physical, &p2);
    say(std::string("driverName=") + driver.driverName + "; driverInfo=" + driver.driverInfo);
    VkPhysicalDeviceVulkan13Features f13{}; f13.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;
    VkPhysicalDeviceVulkan12Features f12{}; f12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES; f12.pNext = &f13;
    VkPhysicalDeviceFeatures2 f{}; f.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2; f.pNext = &f12;
    proc<PFN_vkGetPhysicalDeviceFeatures2>(g, instance, "vkGetPhysicalDeviceFeatures2")(physical, &f);
    say("features: BC=" + std::to_string(f.features.textureCompressionBC) + "; geometryShader=" + std::to_string(f.features.geometryShader) +
        "; tessellationShader=" + std::to_string(f.features.tessellationShader) + "; dualSrcBlend=" + std::to_string(f.features.dualSrcBlend));
    say("features: timelineSemaphore=" + std::to_string(f12.timelineSemaphore) + "; hostQueryReset=" + std::to_string(f12.hostQueryReset) +
        "; vulkanMemoryModel=" + std::to_string(f12.vulkanMemoryModel) + "; synchronization2=" + std::to_string(f13.synchronization2) +
        "; dynamicRendering=" + std::to_string(f13.dynamicRendering) + "; maintenance4=" + std::to_string(f13.maintenance4));
    // Raw capabilities are diagnostic only: the existing wrapper emulates some missing desktop features.
    uint32_t qc = 0;
    auto queues = proc<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(g, instance, "vkGetPhysicalDeviceQueueFamilyProperties");
    queues(physical, &qc, nullptr); std::vector<VkQueueFamilyProperties> q(qc); queues(physical, &qc, q.data());
    uint32_t family = 0;
    while (family < qc && (!(q[family].queueFlags & VK_QUEUE_GRAPHICS_BIT) || !q[family].queueCount)) family++;
    if (family == qc) return finish("No graphics queue family in system ICD");
    float priority = 1;
    VkDeviceQueueCreateInfo qi{}; qi.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qi.queueFamilyIndex = family; qi.queueCount = 1; qi.pQueuePriorities = &priority;
    const char* swap = VK_KHR_SWAPCHAIN_EXTENSION_NAME;
    VkDeviceCreateInfo dc{}; dc.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO; dc.queueCreateInfoCount = 1; dc.pQueueCreateInfos = &qi;
    if (swapchain) { dc.enabledExtensionCount = 1; dc.ppEnabledExtensionNames = &swap; }
    VkDevice device = VK_NULL_HANDLE;
    r = proc<PFN_vkCreateDevice>(g, instance, "vkCreateDevice")(physical, &dc, nullptr, &device);
    say("vkCreateDevice(minimal graphics device)=" + result(r));
    if (r != VK_SUCCESS) return finish("vkCreateDevice: " + result(r));
    auto gd = proc<PFN_vkGetDeviceProcAddr>(g, instance, "vkGetDeviceProcAddr");
    VkQueue queue;
    reinterpret_cast<PFN_vkGetDeviceQueue>(gd(device, "vkGetDeviceQueue"))(device, family, 0, &queue);
    VkFence fence = VK_NULL_HANDLE;
    VkFenceCreateInfo fc{}; fc.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    r = reinterpret_cast<PFN_vkCreateFence>(gd(device, "vkCreateFence"))(device, &fc, nullptr, &fence);
    if (r == VK_SUCCESS) {
        VkSubmitInfo si{}; si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        r = reinterpret_cast<PFN_vkQueueSubmit>(gd(device, "vkQueueSubmit"))(queue, 1, &si, fence);
        say("vkQueueSubmit(empty fenced submission)=" + result(r));
        if (r == VK_SUCCESS) r = reinterpret_cast<PFN_vkWaitForFences>(gd(device, "vkWaitForFences"))(device, 1, &fence, VK_TRUE, 5000000000ULL);
        say("vkWaitForFences=" + result(r));
        // Ensure the queue is idle before destroying the fence, including after a timeout.
        reinterpret_cast<PFN_vkDeviceWaitIdle>(gd(device, "vkDeviceWaitIdle"))(device);
        reinterpret_cast<PFN_vkDestroyFence>(gd(device, "vkDestroyFence"))(device, fence, nullptr);
    }
    reinterpret_cast<PFN_vkDestroyDevice>(gd(device, "vkDestroyDevice"))(device, nullptr);
    if (r != VK_SUCCESS) return finish("native fence/submission: " + result(r));
    return finish("");
}

EXPORT jstring Java_app_gamenative_ui_screen_wow_WowVulkanDiagnostics_probeSystem(JNIEnv* env, jclass) {
    return env->NewStringUTF(probe().c_str());
}

// ICD interface v5 proxy. The desktop loader owns its dispatch tables. Wrapper handles and
// proc addresses are forwarded unchanged; only instance/device creation is logged.
static std::once_flag wrapperOnce;
static PFN_vkGetInstanceProcAddr wrapperGipa = nullptr;
static PFN_vkGetInstanceProcAddr wrapperPhysicalProcAddr = nullptr;
using NegotiateInterface = VkResult (VKAPI_PTR *)(uint32_t*);
static NegotiateInterface wrapperNegotiate = nullptr;
static std::mutex instanceLock;
static std::unordered_map<VkPhysicalDevice, VkInstance> physicalInstances;
static void recordPhysicalDevice(VkInstance i, VkPhysicalDevice device) {
    bool first;
    {
        std::lock_guard<std::mutex> lock(instanceLock);
        first = physicalInstances.emplace(device, i).second;
    }
    if (!first) return;
    VkPhysicalDeviceProperties p{};
    proc<PFN_vkGetPhysicalDeviceProperties>(wrapperGipa, i, "vkGetPhysicalDeviceProperties")(device, &p);
    logLine(std::string("Wine/wrapper GPU=") + p.deviceName + "; API=" + version(p.apiVersion) + "; driverVersion(raw)=" + std::to_string(p.driverVersion));
    auto ee = proc<PFN_vkEnumerateDeviceExtensionProperties>(wrapperGipa, i, "vkEnumerateDeviceExtensionProperties");
    uint32_t count = 0;
    if (ee(device, nullptr, &count, nullptr) == VK_SUCCESS) {
        std::vector<VkExtensionProperties> extensions(count);
        if (ee(device, nullptr, &count, extensions.data()) == VK_SUCCESS)
            for (uint32_t n = 0; n < count; n++) logLine(std::string("wrapper deviceExtension=") + extensions[n].extensionName);
    }
    VkPhysicalDeviceFeatures features{};
    proc<PFN_vkGetPhysicalDeviceFeatures>(wrapperGipa, i, "vkGetPhysicalDeviceFeatures")(device, &features);
    logLine("wrapper features: BC=" + std::to_string(features.textureCompressionBC) +
        "; geometryShader=" + std::to_string(features.geometryShader) + "; tessellationShader=" + std::to_string(features.tessellationShader));
}
static void loadWrapper() {
    const char* path = getenv("WOW_VULKAN_WRAPPER");
    if (!path || !*path) { logLine("ERROR: WOW_VULKAN_WRAPPER is unset"); return; }
    void* h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!h) { logLine(std::string("ERROR: dlopen Bionic wrapper: ") + dlerror()); return; }
    wrapperGipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(h, "vk_icdGetInstanceProcAddr"));
    wrapperPhysicalProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(h, "vk_icdGetPhysicalDeviceProcAddr"));
    wrapperNegotiate = reinterpret_cast<NegotiateInterface>(dlsym(h, "vk_icdNegotiateLoaderICDInterfaceVersion"));
    if (!wrapperGipa) wrapperGipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(h, "vkGetInstanceProcAddr"));
    if (!wrapperGipa) logLine("ERROR: Bionic wrapper has no ICD entry point");
}
static VKAPI_ATTR VkResult VKAPI_CALL traceCreateInstance(const VkInstanceCreateInfo* ci, const VkAllocationCallbacks* a, VkInstance* i) {
    auto fn = proc<PFN_vkCreateInstance>(wrapperGipa, VK_NULL_HANDLE, "vkCreateInstance");
    if (!fn) { logLine("ERROR: wrapper has no vkCreateInstance"); return VK_ERROR_INCOMPATIBLE_DRIVER; }
    logLine("Wine/wrapper vkCreateInstance requested API=" + version(ci->pApplicationInfo ? ci->pApplicationInfo->apiVersion : VK_API_VERSION_1_0));
    if (ci->pApplicationInfo) {
        logLine(std::string("instance application=") + (ci->pApplicationInfo->pApplicationName ? ci->pApplicationInfo->pApplicationName : "unknown") +
            "; engine=" + (ci->pApplicationInfo->pEngineName ? ci->pApplicationInfo->pEngineName : "unknown"));
    }
    for (uint32_t n = 0; n < ci->enabledExtensionCount; n++) logLine(std::string("requested instanceExtension=") + ci->ppEnabledExtensionNames[n]);
    VkResult r = fn(ci, a, i); logLine("Wine/wrapper vkCreateInstance=" + result(r)); return r;
}
static VKAPI_ATTR VkResult VKAPI_CALL traceEnumeratePhysicalDevices(VkInstance i, uint32_t* count, VkPhysicalDevice* devices) {
    auto fn = proc<PFN_vkEnumeratePhysicalDevices>(wrapperGipa, i, "vkEnumeratePhysicalDevices");
    VkResult r = fn(i, count, devices);
    if (devices && (r == VK_SUCCESS || r == VK_INCOMPLETE)) {
        for (uint32_t n = 0; n < *count; n++) recordPhysicalDevice(i, devices[n]);
    }
    if (r != VK_SUCCESS && r != VK_INCOMPLETE) logLine("Wine/wrapper vkEnumeratePhysicalDevices=" + result(r));
    return r;
}
static VKAPI_ATTR VkResult VKAPI_CALL traceEnumeratePhysicalDeviceGroups(VkInstance i, uint32_t* count, VkPhysicalDeviceGroupProperties* groups) {
    auto fn = proc<PFN_vkEnumeratePhysicalDeviceGroups>(wrapperGipa, i, "vkEnumeratePhysicalDeviceGroups");
    if (!fn) fn = proc<PFN_vkEnumeratePhysicalDeviceGroups>(wrapperGipa, i, "vkEnumeratePhysicalDeviceGroupsKHR");
    if (!fn) return VK_ERROR_EXTENSION_NOT_PRESENT;
    VkResult r = fn(i, count, groups);
    if (groups && (r == VK_SUCCESS || r == VK_INCOMPLETE))
        for (uint32_t n = 0; n < *count; n++)
            for (uint32_t d = 0; d < groups[n].physicalDeviceCount; d++) recordPhysicalDevice(i, groups[n].physicalDevices[d]);
    return r;
}
static VKAPI_ATTR void VKAPI_CALL traceDestroyInstance(VkInstance i, const VkAllocationCallbacks* a) {
    {
        std::lock_guard<std::mutex> lock(instanceLock);
        for (auto it = physicalInstances.begin(); it != physicalInstances.end();) {
            if (it->second == i) it = physicalInstances.erase(it); else ++it;
        }
    }
    proc<PFN_vkDestroyInstance>(wrapperGipa, i, "vkDestroyInstance")(i, a);
}
static VKAPI_ATTR VkResult VKAPI_CALL traceCreateDevice(VkPhysicalDevice p, const VkDeviceCreateInfo* ci, const VkAllocationCallbacks* a, VkDevice* d) {
    VkInstance i;
    {
        std::lock_guard<std::mutex> lock(instanceLock);
        auto it = physicalInstances.find(p);
        if (it == physicalInstances.end()) { logLine("ERROR: physical device was not enumerated through tracing ICD"); return VK_ERROR_INITIALIZATION_FAILED; }
        i = it->second;
    }
    uint32_t count = 0;
    auto ee = proc<PFN_vkEnumerateDeviceExtensionProperties>(wrapperGipa, i, "vkEnumerateDeviceExtensionProperties");
    VkResult er = ee(p, nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> extensions(count);
    if (er == VK_SUCCESS) er = ee(p, nullptr, &count, extensions.data());
    for (uint32_t n = 0; n < ci->enabledExtensionCount; n++) {
        const char* name = ci->ppEnabledExtensionNames[n];
        logLine(std::string("requested deviceExtension=") + name);
        if (er == VK_SUCCESS && std::none_of(extensions.begin(), extensions.begin() + count,
                [&](const VkExtensionProperties& e) { return !strcmp(e.extensionName, name); }))
            logLine(std::string("ERROR: required extension not exposed by wrapper: ") + name);
    }
    // Log the exact request without changing feature bits, including extension feature chains.
    for (auto next = static_cast<const VkBaseInStructure*>(ci->pNext); next; next = next->pNext)
        logLine("requested device feature structure sType=" + std::to_string(next->sType));
    VkResult r = proc<PFN_vkCreateDevice>(wrapperGipa, i, "vkCreateDevice")(p, ci, a, d);
    logLine("Wine/DXVK wrapper vkCreateDevice=" + result(r)); return r;
}
EXPORT PFN_vkVoidFunction VKAPI_CALL vk_icdGetInstanceProcAddr(VkInstance i, const char* name) {
    std::call_once(wrapperOnce, loadWrapper);
    if (!wrapperGipa || !name) return nullptr;
    if (!strcmp(name, "vkCreateInstance")) return reinterpret_cast<PFN_vkVoidFunction>(traceCreateInstance);
    if (!strcmp(name, "vkEnumeratePhysicalDevices")) return reinterpret_cast<PFN_vkVoidFunction>(traceEnumeratePhysicalDevices);
    if ((!strcmp(name, "vkEnumeratePhysicalDeviceGroups") || !strcmp(name, "vkEnumeratePhysicalDeviceGroupsKHR")) && wrapperGipa(i, name))
        return reinterpret_cast<PFN_vkVoidFunction>(traceEnumeratePhysicalDeviceGroups);
    if (!strcmp(name, "vkDestroyInstance")) return reinterpret_cast<PFN_vkVoidFunction>(traceDestroyInstance);
    if (!strcmp(name, "vkCreateDevice")) return reinterpret_cast<PFN_vkVoidFunction>(traceCreateDevice);
    if (!strcmp(name, "vkGetInstanceProcAddr")) return reinterpret_cast<PFN_vkVoidFunction>(vk_icdGetInstanceProcAddr);
    return wrapperGipa(i, name);
}
EXPORT VkResult VKAPI_CALL vk_icdNegotiateLoaderICDInterfaceVersion(uint32_t* v) {
    std::call_once(wrapperOnce, loadWrapper);
    if (!wrapperGipa || !wrapperNegotiate || !v) return VK_ERROR_INCOMPATIBLE_DRIVER;
    // v3 transfers surface ownership to the ICD, v4 adds physical-device dispatch,
    // v5 is required for Vulkan >= 1.1. Negotiate with the wrapped ICD too: Mesa's
    // WSI behavior depends on the version agreed with its loader.
    uint32_t supported = std::min(*v, 5u);
    VkResult r = wrapperNegotiate(&supported);
    *v = std::min(supported, 5u);
    logLine("ICD loader interface negotiated=" + std::to_string(*v) + "; result=" + result(r));
    return r;
}
EXPORT PFN_vkVoidFunction VKAPI_CALL vk_icdGetPhysicalDeviceProcAddr(VkInstance i, const char* name) {
    std::call_once(wrapperOnce, loadWrapper);
    if (!wrapperPhysicalProcAddr || !name) return nullptr;
    auto fn = wrapperPhysicalProcAddr(i, name);
    if (fn && !strcmp(name, "vkCreateDevice")) return reinterpret_cast<PFN_vkVoidFunction>(traceCreateDevice);
    return fn;
}
