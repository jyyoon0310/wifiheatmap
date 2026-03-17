#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <memory>

namespace {

struct Params {
    uint32_t nx;
    uint32_t ny;
    uint32_t nym1;
    uint32_t hxCount;
    uint32_t hyCount;
    uint32_t cellCount;
    float invDx;
    float alpha;
    float beta;
};

static constexpr int kSourceStencilN = 9;
static constexpr int kSourceOx[kSourceStencilN] = {0, 1, -1, 0, 0, 1, 1, -1, -1};
static constexpr int kSourceOy[kSourceStencilN] = {0, 0, 0, 1, -1, 1, -1, 1, -1};
static constexpr float kSourceW[kSourceStencilN] = {0.36f, 0.12f, 0.12f, 0.12f, 0.12f, 0.04f, 0.04f, 0.04f, 0.04f};

static const char* kMetalSource = R"METAL(
#include <metal_stdlib>
using namespace metal;

struct Params {
    uint nx;
    uint ny;
    uint nym1;
    uint hxCount;
    uint hyCount;
    uint cellCount;
    float invDx;
    float alpha;
    float beta;
};

kernel void updateH(
    const device float* ez [[buffer(0)]],
    device float* hx [[buffer(1)]],
    device float* hy [[buffer(2)]],
    const device float* bHx [[buffer(3)]],
    const device float* cHx [[buffer(4)]],
    const device float* bHy [[buffer(5)]],
    const device float* cHy [[buffer(6)]],
    constant Params& p [[buffer(7)]],
    uint gid [[thread_position_in_grid]]
) {
    if (gid < p.hxCount) {
        uint i = gid / p.nym1;
        uint j = gid - i * p.nym1;
        uint id = i * p.ny + j;
        float dEdy = (ez[id + 1] - ez[id]) * p.invDx;
        hx[gid] = bHx[gid] * hx[gid] - cHx[gid] * dEdy;
    }
    if (gid < p.hyCount) {
        uint i = gid / p.ny;
        uint j = gid - i * p.ny;
        // CPU 코드: for (int i = 0; i < nx - 1; i++) — i < nx-1 필수.
        // 이 검사 없으면 i == nx-1 일 때 ez[id + p.ny] = ez[(nx-1)*ny+j + ny] = ez[nx*ny+j] → 배열 경계 초과 → garbage → 폭발.
        if (i >= p.nx - 1) return;
        uint id = i * p.ny + j;
        float dEdx = (ez[id + p.ny] - ez[id]) * p.invDx;
        hy[gid] = bHy[gid] * hy[gid] + cHy[gid] * dEdx;
    }
}

kernel void updateE(
    device float* ez [[buffer(0)]],
    device float* ezx [[buffer(1)]],
    device float* ezy [[buffer(2)]],
    const device float* hx [[buffer(3)]],
    const device float* hy [[buffer(4)]],
    const device float* cEx1 [[buffer(5)]],
    const device float* cEx2 [[buffer(6)]],
    const device float* cEy1 [[buffer(7)]],
    const device float* cEy2 [[buffer(8)]],
    constant Params& p [[buffer(9)]],
    uint gid [[thread_position_in_grid]]
) {
    if (gid >= p.cellCount) return;
    uint i = gid / p.ny;
    uint j = gid - i * p.ny;
    if (i == 0 || i >= (p.nx - 1) || j == 0 || j >= (p.ny - 1)) return;

    uint hyId = i * p.ny + j;
    uint hyPrev = (i - 1) * p.ny + j;
    uint hxRow = i * p.nym1;
    uint hxId = hxRow + j;
    uint hxPrev = hxRow + (j - 1);

    float curlHy = (hy[hyId] - hy[hyPrev]) * p.invDx;
    float curlHx = (hx[hxId] - hx[hxPrev]) * p.invDx;

    float ex = cEx1[gid] * ezx[gid] + cEx2[gid] * curlHy;
    float ey = cEy1[gid] * ezy[gid] - cEy2[gid] * curlHx;
    ezx[gid] = ex;
    ezy[gid] = ey;
    ez[gid] = ex + ey;
}

kernel void updatePower(
    const device float* ez [[buffer(0)]],
    device float* power [[buffer(1)]],
    constant Params& p [[buffer(2)]],
    uint gid [[thread_position_in_grid]]
) {
    if (gid >= p.cellCount) return;
    uint i = gid / p.ny;
    uint j = gid - i * p.ny;
    if (i == 0 || i >= (p.nx - 1) || j == 0 || j >= (p.ny - 1)) return;

    float v = ez[gid];
    float e2 = v * v;
    power[gid] = p.alpha * power[gid] + p.beta * e2;
}
)METAL";

struct Session {
    id<MTLDevice> device = nil;
    id<MTLCommandQueue> queue = nil;
    id<MTLComputePipelineState> psoUpdateH = nil;
    id<MTLComputePipelineState> psoUpdateE = nil;
    id<MTLComputePipelineState> psoUpdatePower = nil;

    id<MTLBuffer> bufEz = nil;
    id<MTLBuffer> bufEzx = nil;
    id<MTLBuffer> bufEzy = nil;
    id<MTLBuffer> bufHx = nil;
    id<MTLBuffer> bufHy = nil;
    id<MTLBuffer> bufPower = nil;
    id<MTLBuffer> bufCEx1 = nil;
    id<MTLBuffer> bufCEx2 = nil;
    id<MTLBuffer> bufCEy1 = nil;
    id<MTLBuffer> bufCEy2 = nil;
    id<MTLBuffer> bufBHx = nil;
    id<MTLBuffer> bufCHx = nil;
    id<MTLBuffer> bufBHy = nil;
    id<MTLBuffer> bufCHy = nil;
    id<MTLBuffer> bufParams = nil;

    Params params{};
};

static inline Session* fromHandle(jlong handle) {
    return reinterpret_cast<Session*>(static_cast<intptr_t>(handle));
}

static bool checkArrayLength(JNIEnv* env, jarray arr, jsize expected) {
    if (arr == nullptr) return false;
    return env->GetArrayLength(arr) == expected;
}

static id<MTLBuffer> newSharedBuffer(id<MTLDevice> device, size_t bytes) {
    return [device newBufferWithLength:bytes options:MTLResourceStorageModeShared];
}

static void encodeDispatch1D(id<MTLComputeCommandEncoder> enc,
                             id<MTLComputePipelineState> pso,
                             NSUInteger count) {
    if (count == 0) return;
    NSUInteger width = pso.maxTotalThreadsPerThreadgroup;
    width = std::max<NSUInteger>(1, std::min<NSUInteger>(256, width));
    MTLSize tg = MTLSizeMake(width, 1, 1);
    NSUInteger groups = (count + width - 1) / width;
    MTLSize grid = MTLSizeMake(groups, 1, 1);
    [enc dispatchThreadgroups:grid threadsPerThreadgroup:tg];
}

static bool waitComplete(id<MTLCommandBuffer> cb) {
    [cb commit];
    [cb waitUntilCompleted];
    NSError* err = cb.error;
    return (err == nil);
}

static bool runUpdateHE(Session* s) {
    id<MTLCommandBuffer> cb = [s->queue commandBuffer];
    if (cb == nil) return false;

    id<MTLComputeCommandEncoder> encH = [cb computeCommandEncoder];
    if (encH == nil) return false;
    [encH setComputePipelineState:s->psoUpdateH];
    [encH setBuffer:s->bufEz offset:0 atIndex:0];
    [encH setBuffer:s->bufHx offset:0 atIndex:1];
    [encH setBuffer:s->bufHy offset:0 atIndex:2];
    [encH setBuffer:s->bufBHx offset:0 atIndex:3];
    [encH setBuffer:s->bufCHx offset:0 atIndex:4];
    [encH setBuffer:s->bufBHy offset:0 atIndex:5];
    [encH setBuffer:s->bufCHy offset:0 atIndex:6];
    [encH setBuffer:s->bufParams offset:0 atIndex:7];
    encodeDispatch1D(encH, s->psoUpdateH, std::max(s->params.hxCount, s->params.hyCount));
    [encH endEncoding];

    id<MTLComputeCommandEncoder> encE = [cb computeCommandEncoder];
    if (encE == nil) return false;
    [encE setComputePipelineState:s->psoUpdateE];
    [encE setBuffer:s->bufEz offset:0 atIndex:0];
    [encE setBuffer:s->bufEzx offset:0 atIndex:1];
    [encE setBuffer:s->bufEzy offset:0 atIndex:2];
    [encE setBuffer:s->bufHx offset:0 atIndex:3];
    [encE setBuffer:s->bufHy offset:0 atIndex:4];
    [encE setBuffer:s->bufCEx1 offset:0 atIndex:5];
    [encE setBuffer:s->bufCEx2 offset:0 atIndex:6];
    [encE setBuffer:s->bufCEy1 offset:0 atIndex:7];
    [encE setBuffer:s->bufCEy2 offset:0 atIndex:8];
    [encE setBuffer:s->bufParams offset:0 atIndex:9];
    encodeDispatch1D(encE, s->psoUpdateE, s->params.cellCount);
    [encE endEncoding];

    return waitComplete(cb);
}

static bool runPower(Session* s) {
    id<MTLCommandBuffer> cb = [s->queue commandBuffer];
    if (cb == nil) return false;
    id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
    if (enc == nil) return false;
    [enc setComputePipelineState:s->psoUpdatePower];
    [enc setBuffer:s->bufEz offset:0 atIndex:0];
    [enc setBuffer:s->bufPower offset:0 atIndex:1];
    [enc setBuffer:s->bufParams offset:0 atIndex:2];
    encodeDispatch1D(enc, s->psoUpdatePower, s->params.cellCount);
    [enc endEncoding];
    return waitComplete(cb);
}

static void injectSources(Session* s, const jint* srcX, const jint* srcY, const jfloat* srcV, jint sourceCount) {
    if (sourceCount <= 0) return;
    float* ez = static_cast<float*>([s->bufEz contents]);
    float* ezx = static_cast<float*>([s->bufEzx contents]);
    float* ezy = static_cast<float*>([s->bufEzy contents]);
    if (ez == nullptr || ezx == nullptr || ezy == nullptr) return;

    const int nx = static_cast<int>(s->params.nx);
    const int ny = static_cast<int>(s->params.ny);
    for (jint i = 0; i < sourceCount; i++) {
        int gx = srcX[i];
        int gy = srcY[i];
        float src = srcV[i];
        for (int k = 0; k < kSourceStencilN; k++) {
            int x = gx + kSourceOx[k];
            int y = gy + kSourceOy[k];
            if (x <= 0 || x >= nx - 1 || y <= 0 || y >= ny - 1) continue;
            int id = x * ny + y;
            float v = src * kSourceW[k];
            ezx[id] += 0.5f * v;              // Soft source: += (Hard source = 는 Maxwell 방정식 위반으로 폭발)
            ezy[id] += 0.5f * v;
            ez[id] = ezx[id] + ezy[id];       // split-field 재계산
        }
    }
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_app_solver_v2_metal_MetalNativeBridge_nIsAvailable(JNIEnv*, jclass) {
    @autoreleasepool {
        id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
        return (dev != nil) ? JNI_TRUE : JNI_FALSE;
    }
}

JNIEXPORT jlong JNICALL
Java_app_solver_v2_metal_MetalNativeBridge_nCreate(
        JNIEnv* env, jclass,
        jint nx, jint ny, jfloat invDx, jfloat alpha, jfloat beta,
        jfloatArray cEx1, jfloatArray cEx2, jfloatArray cEy1, jfloatArray cEy2,
        jfloatArray bHx, jfloatArray cHx, jfloatArray bHy, jfloatArray cHy) {
    @autoreleasepool {
        if (nx <= 2 || ny <= 2) return 0L;
        const jsize cellCount = nx * ny;
        const jsize hxCount = nx * std::max(1, ny - 1);
        const jsize hyCount = std::max(1, nx - 1) * ny;
        if (!checkArrayLength(env, cEx1, cellCount) ||
            !checkArrayLength(env, cEx2, cellCount) ||
            !checkArrayLength(env, cEy1, cellCount) ||
            !checkArrayLength(env, cEy2, cellCount) ||
            !checkArrayLength(env, bHx, hxCount) ||
            !checkArrayLength(env, cHx, hxCount) ||
            !checkArrayLength(env, bHy, hyCount) ||
            !checkArrayLength(env, cHy, hyCount)) {
            return 0L;
        }

        std::unique_ptr<Session> s = std::make_unique<Session>();
        s->device = MTLCreateSystemDefaultDevice();
        if (s->device == nil) return 0L;
        s->queue = [s->device newCommandQueue];
        if (s->queue == nil) return 0L;

        NSError* libErr = nil;
        NSString* src = [NSString stringWithUTF8String:kMetalSource];
        id<MTLLibrary> lib = [s->device newLibraryWithSource:src options:nil error:&libErr];
        if (lib == nil) return 0L;

        NSError* psoErr = nil;
        id<MTLFunction> fnH = [lib newFunctionWithName:@"updateH"];
        id<MTLFunction> fnE = [lib newFunctionWithName:@"updateE"];
        id<MTLFunction> fnP = [lib newFunctionWithName:@"updatePower"];
        if (fnH == nil || fnE == nil || fnP == nil) return 0L;
        s->psoUpdateH = [s->device newComputePipelineStateWithFunction:fnH error:&psoErr];
        if (s->psoUpdateH == nil) return 0L;
        psoErr = nil;
        s->psoUpdateE = [s->device newComputePipelineStateWithFunction:fnE error:&psoErr];
        if (s->psoUpdateE == nil) return 0L;
        psoErr = nil;
        s->psoUpdatePower = [s->device newComputePipelineStateWithFunction:fnP error:&psoErr];
        if (s->psoUpdatePower == nil) return 0L;

        const size_t cellBytes = static_cast<size_t>(cellCount) * sizeof(float);
        const size_t hxBytes = static_cast<size_t>(hxCount) * sizeof(float);
        const size_t hyBytes = static_cast<size_t>(hyCount) * sizeof(float);

        s->bufEz = newSharedBuffer(s->device, cellBytes);
        s->bufEzx = newSharedBuffer(s->device, cellBytes);
        s->bufEzy = newSharedBuffer(s->device, cellBytes);
        s->bufPower = newSharedBuffer(s->device, cellBytes);
        s->bufHx = newSharedBuffer(s->device, hxBytes);
        s->bufHy = newSharedBuffer(s->device, hyBytes);
        s->bufCEx1 = newSharedBuffer(s->device, cellBytes);
        s->bufCEx2 = newSharedBuffer(s->device, cellBytes);
        s->bufCEy1 = newSharedBuffer(s->device, cellBytes);
        s->bufCEy2 = newSharedBuffer(s->device, cellBytes);
        s->bufBHx = newSharedBuffer(s->device, hxBytes);
        s->bufCHx = newSharedBuffer(s->device, hxBytes);
        s->bufBHy = newSharedBuffer(s->device, hyBytes);
        s->bufCHy = newSharedBuffer(s->device, hyBytes);
        if (s->bufEz == nil || s->bufEzx == nil || s->bufEzy == nil || s->bufPower == nil ||
            s->bufHx == nil || s->bufHy == nil ||
            s->bufCEx1 == nil || s->bufCEx2 == nil || s->bufCEy1 == nil || s->bufCEy2 == nil ||
            s->bufBHx == nil || s->bufCHx == nil || s->bufBHy == nil || s->bufCHy == nil) {
            return 0L;
        }

        std::memset([s->bufEz contents], 0, cellBytes);
        std::memset([s->bufEzx contents], 0, cellBytes);
        std::memset([s->bufEzy contents], 0, cellBytes);
        std::memset([s->bufPower contents], 0, cellBytes);
        std::memset([s->bufHx contents], 0, hxBytes);
        std::memset([s->bufHy contents], 0, hyBytes);

        env->GetFloatArrayRegion(cEx1, 0, cellCount, static_cast<jfloat*>([s->bufCEx1 contents]));
        env->GetFloatArrayRegion(cEx2, 0, cellCount, static_cast<jfloat*>([s->bufCEx2 contents]));
        env->GetFloatArrayRegion(cEy1, 0, cellCount, static_cast<jfloat*>([s->bufCEy1 contents]));
        env->GetFloatArrayRegion(cEy2, 0, cellCount, static_cast<jfloat*>([s->bufCEy2 contents]));
        env->GetFloatArrayRegion(bHx, 0, hxCount, static_cast<jfloat*>([s->bufBHx contents]));
        env->GetFloatArrayRegion(cHx, 0, hxCount, static_cast<jfloat*>([s->bufCHx contents]));
        env->GetFloatArrayRegion(bHy, 0, hyCount, static_cast<jfloat*>([s->bufBHy contents]));
        env->GetFloatArrayRegion(cHy, 0, hyCount, static_cast<jfloat*>([s->bufCHy contents]));
        if (env->ExceptionCheck()) {
            return 0L;
        }

        s->params.nx = static_cast<uint32_t>(nx);
        s->params.ny = static_cast<uint32_t>(ny);
        s->params.nym1 = static_cast<uint32_t>(std::max(1, ny - 1));
        s->params.hxCount = static_cast<uint32_t>(hxCount);
        s->params.hyCount = static_cast<uint32_t>(hyCount);
        s->params.cellCount = static_cast<uint32_t>(cellCount);
        s->params.invDx = invDx;
        s->params.alpha = alpha;
        s->params.beta = beta;

        s->bufParams = [s->device newBufferWithBytes:&s->params
                                              length:sizeof(Params)
                                             options:MTLResourceStorageModeShared];
        if (s->bufParams == nil) return 0L;

        return static_cast<jlong>(reinterpret_cast<intptr_t>(s.release()));
    }
}

JNIEXPORT void JNICALL
Java_app_solver_v2_metal_MetalNativeBridge_nDestroy(JNIEnv*, jclass, jlong handle) {
    @autoreleasepool {
        Session* s = fromHandle(handle);
        if (s == nullptr) return;
        delete s;
    }
}

JNIEXPORT void JNICALL
Java_app_solver_v2_metal_MetalNativeBridge_nReset(JNIEnv*, jclass, jlong handle) {
    @autoreleasepool {
        Session* s = fromHandle(handle);
        if (s == nullptr) return;
        const size_t cellBytes = static_cast<size_t>(s->params.cellCount) * sizeof(float);
        const size_t hxBytes = static_cast<size_t>(s->params.hxCount) * sizeof(float);
        const size_t hyBytes = static_cast<size_t>(s->params.hyCount) * sizeof(float);
        std::memset([s->bufEz contents], 0, cellBytes);
        std::memset([s->bufEzx contents], 0, cellBytes);
        std::memset([s->bufEzy contents], 0, cellBytes);
        std::memset([s->bufPower contents], 0, cellBytes);
        std::memset([s->bufHx contents], 0, hxBytes);
        std::memset([s->bufHy contents], 0, hyBytes);
    }
}

JNIEXPORT void JNICALL
Java_app_solver_v2_metal_MetalNativeBridge_nStep(
        JNIEnv* env, jclass, jlong handle,
        jintArray sourceX, jintArray sourceY, jfloatArray sourceValue, jint sourceCount) {
    @autoreleasepool {
        Session* s = fromHandle(handle);
        if (s == nullptr) return;
        if (!runUpdateHE(s)) return;

        jint* sx = nullptr;
        jint* sy = nullptr;
        jfloat* sv = nullptr;
        if (sourceCount > 0 && sourceX != nullptr && sourceY != nullptr && sourceValue != nullptr) {
            sx = env->GetIntArrayElements(sourceX, nullptr);
            sy = env->GetIntArrayElements(sourceY, nullptr);
            sv = env->GetFloatArrayElements(sourceValue, nullptr);
            if (sx != nullptr && sy != nullptr && sv != nullptr) {
                injectSources(s, sx, sy, sv, sourceCount);
            }
        }
        if (sx != nullptr) env->ReleaseIntArrayElements(sourceX, sx, JNI_ABORT);
        if (sy != nullptr) env->ReleaseIntArrayElements(sourceY, sy, JNI_ABORT);
        if (sv != nullptr) env->ReleaseFloatArrayElements(sourceValue, sv, JNI_ABORT);

        runPower(s);
    }
}

JNIEXPORT void JNICALL
Java_app_solver_v2_metal_MetalNativeBridge_nReadPower(JNIEnv* env, jclass, jlong handle, jfloatArray outPower) {
    @autoreleasepool {
        Session* s = fromHandle(handle);
        if (s == nullptr || outPower == nullptr) return;
        jsize outLen = env->GetArrayLength(outPower);
        jsize n = std::min<jsize>(outLen, static_cast<jsize>(s->params.cellCount));
        env->SetFloatArrayRegion(outPower, 0, n, static_cast<jfloat*>([s->bufPower contents]));
    }
}

} // extern "C"
