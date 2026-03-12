#include <Metal/Metal.h>
#include <jni.h>

extern "C" JNIEXPORT void JNICALL
Java_com_pebbles_1boon_metalrender_nativebridge_MeshShaderNative_dispatchTerrain(
    JNIEnv *, jclass, jlong handle, jint visibleRegionCount,
    jlong indirectBufferAddr) {
  (void)handle;
  (void)visibleRegionCount;
  (void)indirectBufferAddr;
}
