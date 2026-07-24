package cn.zju.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptchaCoordinateMapperTest {
    @Test
    fun mapsScaledTapToOriginalImageCoordinates() {
        assertEquals(
            CaptchaPoint(100, 50),
            CaptchaCoordinateMapper.toImagePoint(
                tapX = 200f,
                tapY = 100f,
                displayedWidth = 400f,
                displayedHeight = 200f,
                imageWidth = 200,
                imageHeight = 100,
            ),
        )
    }

    @Test
    fun rejectsTapsOutsideTheBitmap() {
        assertNull(
            CaptchaCoordinateMapper.toImagePoint(
                tapX = 401f,
                tapY = 1f,
                displayedWidth = 400f,
                displayedHeight = 200f,
                imageWidth = 200,
                imageHeight = 100,
            ),
        )
    }
}
