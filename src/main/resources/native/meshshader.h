#pragma once

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jlong JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_initMeshDevice(JNIEnv*, jclass, jlong,
                                                                                          jboolean);
JNIEXPORT jboolean JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_supportsMeshShaders(JNIEnv*, jclass,
                                                                                                  jlong);
JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_initMeshPipeline(JNIEnv*, jclass, jlong);
JNIEXPORT jlong JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_createNativeChunkMesh(JNIEnv*, jclass, jlong,
                                                                                                 jobject, jint, jint,
                                                                                                 jobject, jint, jint);
JNIEXPORT jlong JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_updateNativeChunkMesh(JNIEnv*, jclass, jlong,
                                                                                                 jlong, jobject, jint,
                                                                                                 jint, jobject, jint,
                                                                                                 jint);
JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_destroyNativeChunkMesh(JNIEnv*, jclass,
                                                                                                 jlong);
JNIEXPORT jboolean JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_startMeshFrame(JNIEnv*, jclass, jlong);
JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_drawNativeChunkMesh(JNIEnv*, jclass, jlong,
                                                                                              jlong, jint);
JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_endMeshFrame(JNIEnv*, jclass, jlong);
JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MeshShaderNative_destroyMeshDevice(JNIEnv*, jclass, jlong);

#ifdef __cplusplus
}
#endif