#include <jni.h>
#include <Metal/Metal.h>

extern "C" JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_dispatchTerrain
	(JNIEnv*, jclass, jlong handle, jint visibleRegionCount, jlong indirectBufferAddr) {
		(void)handle; (void)visibleRegionCount; (void)indirectBufferAddr;
		// Minimal no-op to keep pipeline alive during Phase 4 scaffolding.
}
