package com.enhikyy.girassol.render3d

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.util.Log

/** GL_RENDERER / GL_VENDOR / GL_VERSION via um contexto EGL pbuffer 1×1 descartável. */
data class GlDriverInfo(val renderer: String, val vendor: String, val version: String, val glsl: String) {
    val isSoftware: Boolean
        get() = renderer.contains("SwiftShader", true) || renderer.contains("llvmpipe", true) ||
            renderer.contains("softpipe", true) || renderer.contains("Android Emulator", true)
    val family: String
        get() = when {
            renderer.contains("Adreno", true) -> "Adreno"
            renderer.contains("Mali", true) -> "Mali"
            renderer.contains("PowerVR", true) || renderer.contains("IMG", false) -> "PowerVR"
            renderer.contains("Xclipse", true) -> "Xclipse"
            isSoftware -> "software"
            else -> "outra"
        }
    fun cacheKey(): String = "$renderer|$version"
}

object GlInfo {
    private const val EGL_OPENGL_ES3_BIT_KHR = 0x40

    fun query(): GlDriverInfo? = runCatching {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(dpy, ver, 0, ver, 1)) { "eglInitialize" }
        val attrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE)
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig" }
        val ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        try {
            check(EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) { "eglMakeCurrent" }
            GlDriverInfo(
                GLES30.glGetString(GLES30.GL_RENDERER) ?: "?",
                GLES30.glGetString(GLES30.GL_VENDOR) ?: "?",
                GLES30.glGetString(GLES30.GL_VERSION) ?: "?",
                GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION) ?: "?"
            )
        } finally {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf)
            EGL14.eglDestroyContext(dpy, ctx)
            // Sem eglTerminate: o display padrão é compartilhado com o Filament no mesmo processo.
        }
    }.onFailure { Log.w("GirassolGpu", "consulta GL falhou", it) }.getOrNull()
}
