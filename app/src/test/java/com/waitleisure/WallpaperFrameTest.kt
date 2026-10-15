package com.waitleisure

import com.waitleisure.data.computeWallpaperFrame
import com.waitleisure.data.wallpaperMaxOffsetFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 壁纸取景的几何计算。
 *
 * 这块踩过三个坑，每个都对应下面一组用例：
 *
 *   1. 1.0× 完全拖不动  —— 边界算成了 (scale-1)/2，忘了 Crop 的「覆盖富余」
 *   2. 划到边缘还能继续划 —— 只在渲染层 clamp，手势累加的值没有上限
 *   3. 图片被拉伸变形    —— Image 的布局尺寸被父约束夹住了
 *
 * 第 3 个在这里也能验：frame.widthPx / heightPx 必须严格等于图片的宽高比，
 * 一旦比例不对，外面 FillBounds 就会拉变形。
 */
class WallpaperFrameTest {

    private val screenW = 1080f
    private val screenH = 2400f

    /** 横图放进竖屏：宽度方向溢出，纵向刚好贴合。 */
    @Test
    fun `横图只有横向可平移`() {
        val (maxX, maxY) = wallpaperMaxOffsetFraction(
            scale = 1f,
            imageWidth = 3000, imageHeight = 900,
            containerWidth = screenW, containerHeight = screenH
        )
        // crop = max(1080/3000, 2400/900) = 2.6667 → 画出来 8000 x 2400
        // maxX = (8000 - 1080) / 2 / 1080
        assertEquals(3.2037f, maxX, 0.001f)
        assertEquals(0f, maxY, 0.001f)
    }

    /** 方图放进竖屏：同样是纵向贴合、横向可拖。 */
    @Test
    fun `方图横向可平移纵向不可`() {
        val (maxX, maxY) = wallpaperMaxOffsetFraction(
            scale = 1f,
            imageWidth = 1000, imageHeight = 1000,
            containerWidth = screenW, containerHeight = screenH
        )
        // crop = 2.4 → 2400 x 2400
        // maxX = (2400 - 1080) / 2 / 1080
        assertEquals(0.6111f, maxX, 0.001f)
        assertEquals(0f, maxY, 0.001f)
    }

    /** 图和屏幕同比例时两个方向都没得拖 —— 这是数学事实，不是 bug。 */
    @Test
    fun `同比例图完全不可平移`() {
        val (maxX, maxY) = wallpaperMaxOffsetFraction(
            scale = 1f,
            imageWidth = 1080, imageHeight = 2400,
            containerWidth = screenW, containerHeight = screenH
        )
        assertEquals(0f, maxX, 0.001f)
        assertEquals(0f, maxY, 0.001f)
    }

    /** 放大之后可平移范围按比例变大。 */
    @Test
    fun `放大后边界变大`() {
        val (base, _) = wallpaperMaxOffsetFraction(1f, 1000, 1000, screenW, screenH)
        val (twice, _) = wallpaperMaxOffsetFraction(2f, 1000, 1000, screenW, screenH)
        // 画出来从 2400 变 4800，多出来的那一半正好翻倍还多一点（容器占比在变）
        assertTrue("放大后边界必须更大：base=$base twice=$twice", twice > base)
        assertEquals(1.7222f, twice, 0.001f)
    }

    /** 缩小到 1× 以下不该把边界算成负数。 */
    @Test
    fun `缩放小于一时按一算`() {
        val (a, b) = wallpaperMaxOffsetFraction(0.3f, 1000, 1000, screenW, screenH)
        val (c, d) = wallpaperMaxOffsetFraction(1f, 1000, 1000, screenW, screenH)
        assertEquals(c, a, 0.0001f)
        assertEquals(d, b, 0.0001f)
        assertTrue(a >= 0f && b >= 0f)
    }

    /** 越界的偏移必须被夹到边界上（这就是「边缘过量滑动」的那个 bug）。 */
    @Test
    fun `越界偏移被夹到边界`() {
        val (maxX, _) = wallpaperMaxOffsetFraction(1f, 1000, 1000, screenW, screenH)

        // 传一个远超边界的值，出来的位移必须正好等于边界对应的像素
        val far = computeWallpaperFrame(1000, 1000, screenW, screenH, 1f, 999f, 0f)
        val atEdge = computeWallpaperFrame(1000, 1000, screenW, screenH, 1f, maxX, 0f)
        assertEquals(atEdge.txPx, far.txPx, 0.01f)

        // 反过来也一样
        val farNeg = computeWallpaperFrame(1000, 1000, screenW, screenH, 1f, -999f, 0f)
        val atEdgeNeg = computeWallpaperFrame(1000, 1000, screenW, screenH, 1f, -maxX, 0f)
        assertEquals(atEdgeNeg.txPx, farNeg.txPx, 0.01f)
    }

    /**
     * 画出来的尺寸比例必须和原图一致 —— 否则外面用 FillBounds 就成了拉伸变形。
     * 这是「图片比屏幕大时被拉伸」那个 bug 的回归测试。
     */
    @Test
    fun `画出来的尺寸保持原图宽高比`() {
        for ((iw, ih) in listOf(3000 to 900, 1000 to 1000, 1080 to 2400, 4000 to 3000)) {
            for (scale in listOf(1f, 1.7f, 3f)) {
                val f = computeWallpaperFrame(iw, ih, screenW, screenH, scale, 0f, 0f)
                val imageRatio = iw.toFloat() / ih
                val drawnRatio = f.widthPx / f.heightPx
                assertEquals(
                    "原图 ${iw}x$ih 缩放 $scale 后比例变了",
                    imageRatio, drawnRatio, 0.0001f
                )
            }
        }
    }

    /** 图片必须始终盖满容器 —— 盖不住就会露黑边。 */
    @Test
    fun `任何缩放下图片都盖满容器`() {
        for ((iw, ih) in listOf(3000 to 900, 1000 to 1000, 1080 to 2400, 500 to 4000)) {
            for (scale in listOf(1f, 2.5f)) {
                val f = computeWallpaperFrame(iw, ih, screenW, screenH, scale, 0f, 0f)
                assertTrue("${iw}x$ih @${scale} 宽度盖不住", f.widthPx >= screenW - 0.01f)
                assertTrue("${iw}x$ih @${scale} 高度盖不住", f.heightPx >= screenH - 0.01f)
            }
        }
    }

    /** 尺寸非法时不能崩，也不能返回 NaN。 */
    @Test
    fun `非法尺寸安全退化`() {
        val (a, b) = wallpaperMaxOffsetFraction(1f, 0, 0, screenW, screenH)
        assertEquals(0f, a, 0f)
        assertEquals(0f, b, 0f)

        val f = computeWallpaperFrame(0, 0, screenW, screenH, 1f, 0f, 0f)
        assertTrue(f.widthPx.isFinite() && f.heightPx.isFinite())
        assertEquals(0f, f.txPx, 0f)
    }
}
