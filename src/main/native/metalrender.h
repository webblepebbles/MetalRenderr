#include <jni.h>
JNIEXPORT jlong JNICALL Java_com_metalrender_nativebridge_MetalBackend_init(JNIEnv*, jclass, jlong, jboolean);
JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_resize(JNIEnv*, jclass, jlong, jint, jint);
JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_render(JNIEnv*, jclass, jlong, jfloat);
JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_destroy(JNIEnv*, jclass, jlong);
JNIEXPORT jboolean JNICALL Java_com_metalrender_nativebridge_MetalBackend_supportsMeshShaders(JNIEnv*, jclass);
JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_uploadStaticMesh(JNIEnv*, jclass, jlong, jobject, jint, jint);
JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_MetalBackend_setCamera(JNIEnv*, jclass, jlong, jfloatArray);