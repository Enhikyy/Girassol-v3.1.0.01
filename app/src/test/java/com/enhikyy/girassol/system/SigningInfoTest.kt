package com.enhikyy.girassol.system

import org.junit.Assert.assertEquals
import org.junit.Test

class SigningInfoTest {
    @Test
    fun classifiesDebugAndRelease() {
        assertEquals(SigningKind.DEBUG, SigningInfo.classify("CN=Android Debug,O=Android,C=US"))
        assertEquals(SigningKind.RELEASE, SigningInfo.classify("CN=Enhikyy Girassol,O=Enhikyy,C=BR"))
        assertEquals(SigningKind.UNKNOWN, SigningInfo.classify(""))
    }

    @Test
    fun fingerprintIsColonSeparatedSha256() {
        val fp = SigningInfo.fingerprint("abc".toByteArray())
        assertEquals("BA:78:16:BF", fp.take(11))
        assertEquals(32 * 3 - 1, fp.length)
    }
}
