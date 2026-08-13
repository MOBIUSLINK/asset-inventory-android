package cn.assetinventory.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPayloadTest {
    @Test fun acceptsKnownAssetFormats() {
        assertEquals(QrPayload.Asset("BJB030"), QrPayload.parse("BJB030"))
        assertEquals(QrPayload.Asset("DP0001"), QrPayload.parse("ASSET:DP0001"))
        assertEquals(QrPayload.Asset("ABCD1234"), QrPayload.parse("abcd1234"))
        assertEquals(QrPayload.Asset("NM-003"), QrPayload.parse("NM-003"))
        assertEquals(QrPayload.Asset("DP042-1"), QrPayload.parse("ASSET:DP042-1"))
        assertEquals(QrPayload.Asset("ZJ0061-1"), QrPayload.parse("ZJ0061-1"))
    }

    @Test fun rejectsMalformedAssetFormats() {
        assertTrue(QrPayload.parse("BJB30") is QrPayload.Invalid)
        assertTrue(QrPayload.parse("ASSET:BJB 030") is QrPayload.Invalid)
        assertTrue(QrPayload.parse("DP0122（未贴标签）") is QrPayload.Invalid)
    }
}
