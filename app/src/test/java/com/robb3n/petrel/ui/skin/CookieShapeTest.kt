package com.robb3n.petrel.ui.skin

import com.robb3n.petrel.ui.skin.tonal.CookieShape
import com.robb3n.petrel.ui.skin.tonal.COOKIE_STEP_DEGREES
import com.robb3n.petrel.ui.skin.tonal.cookieRadius
import com.robb3n.petrel.ui.skin.tonal.settledAngle
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

class CookieShapeTest {
    private val lobe = 2 * PI / 9

    @Test fun cookiePeaksAtTopAndEveryLobe() {
        for (k in 0 until 9) assertEquals(100f, cookieRadius(CookieShape.Cookie, k * lobe), 1e-3f)
    }

    @Test fun cookieDipsBetweenLobes() {
        for (k in 0 until 9) assertEquals(82f, cookieRadius(CookieShape.Cookie, (k + 0.5) * lobe), 1e-3f)
    }

    @Test fun circleIsConstant() {
        for (i in 0 until 36) assertEquals(94f, cookieRadius(CookieShape.Circle, i * PI / 18), 1e-4f)
    }

    @Test fun matchesPaintedFirstSamples() {
        // 画稿 COOKIE 路径第二个点 (104.3, 0.8)：θ = atan2(4.3, 99.2)
        val theta = Math.atan2(4.3, 99.2)
        val r = cookieRadius(CookieShape.Cookie, theta)
        assertEquals(104.3, 100 + r * Math.sin(theta), 0.1)
        assertEquals(0.8, 100 - r * Math.cos(theta), 0.1)
    }

    @Test fun stepIsOneLobe() {
        assertEquals(40f, COOKIE_STEP_DEGREES, 1e-4f)
    }

    @Test fun settledAngleIsTheNearestWholeLobe() {
        assertEquals(0f, settledAngle(0f), 1e-4f)
        assertEquals(0f, settledAngle(19.9f), 1e-4f)
        assertEquals(40f, settledAngle(20.1f), 1e-4f)
        assertEquals(120f, settledAngle(100f), 1e-4f)
        assertEquals(360f, settledAngle(350f), 1e-4f)
        // 每次旋转落点与形状的周期一致：转过去的角度不超过半个瓣
        for (a in listOf(3f, 77f, 181f, 359.9f)) assert(Math.abs(settledAngle(a) - a) <= COOKIE_STEP_DEGREES / 2 + 1e-3f)
    }
}
