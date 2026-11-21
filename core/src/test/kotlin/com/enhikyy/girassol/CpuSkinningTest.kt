package com.enhikyy.girassol

import com.enhikyy.girassol.core.render.CitizenRig
import com.enhikyy.girassol.core.render.CpuSkinning
import com.enhikyy.girassol.core.render.Mat4
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CpuSkinningTest {
    @Test fun matchesMat4TransformPerBone() {
        val pos = floatArrayOf(0.1f, 0.2f, 0.3f, -0.4f, 1.1f, 0.05f)
        val boneOf = shortArrayOf(0, (CitizenRig.BONES - 1).toShort())
        val bones = FloatArray(CitizenRig.BONES * 16)
        val a = Mat4.mul(Mat4.translate(1f, 2f, 3f), Mat4.rotY(0.7f))
        val b = Mat4.mul(Mat4.rotX(-0.3f), Mat4.scale(0.5f, 0.6f, 0.7f))
        System.arraycopy(a, 0, bones, 0, 16)
        System.arraycopy(b, 0, bones, (CitizenRig.BONES - 1) * 16, 16)
        val out = FloatArray(6)
        CpuSkinning.skin(pos, boneOf, bones, out)
        assertArrayEquals(Mat4.transform(a, 0.1f, 0.2f, 0.3f), out.copyOfRange(0, 3), 1e-5f)
        assertArrayEquals(Mat4.transform(b, -0.4f, 1.1f, 0.05f), out.copyOfRange(3, 6), 1e-5f)
    }
}
