package com.cruisetune.player.ui

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class QuarkQrRasterTest {
    @Test fun extractedSvgRemainsScannableWithQuietZone() {
        val payload="https://pan.quark.cn/test-qr-roundtrip"
        val matrix=MultiFormatWriter().encode(payload,BarcodeFormat.QR_CODE,1,1,mapOf(EncodeHintType.MARGIN to 0))
        val path=buildString { for(y in 0 until matrix.height) for(x in 0 until matrix.width) if(matrix[x,y]) append("M $x $y l 1 0 0 1 -1 0 Z ") }
        val bitmap=QuarkQrRaster.render(matrix.width,path)
        val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        assertEquals(payload,MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels)))).text)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsInvalidCoordinates() {QuarkQrRaster.render(21,"M 99 0 l 1 0 0 1 -1 0 Z ".repeat(21))}
}
