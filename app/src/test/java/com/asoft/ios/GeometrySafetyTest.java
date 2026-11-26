package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GeometrySafetyTest {
    @Test
    public void zeroOrNegativeDimensionsNeverProduceNanOrZeroFitBox() {
        for (float[] vwvh : new float[][] {{0f, 0f}, {0f, 1000f}, {1000f, 0f}, {-50f, 200f}}) {
            ScreenGeometry.FitBox box = ScreenGeometry.fit(vwvh[0], vwvh[1], 1179, 2556);
            assertTrue(Float.isFinite(box.left));
            assertTrue(Float.isFinite(box.top));
            assertTrue(Float.isFinite(box.width) && box.width >= 1f);
            assertTrue(Float.isFinite(box.height) && box.height >= 1f);

            float[] pt = ScreenGeometry.touchNorm(120f, 240f, vwvh[0], vwvh[1], 0, 0);
            assertTrue(Float.isFinite(pt[0]));
            assertTrue(Float.isFinite(pt[1]));
        }
    }

    @Test
    public void landscapeSplitScreenPreservesAspectAndCentersTouchCoordinates() {
        // 1600x800 pane holding an iPhone 1179x2556 portrait frame
        ScreenGeometry.FitBox box = ScreenGeometry.fit(1600f, 800f, 1179, 2556);
        assertEquals(800f, box.height, 0.01f);
        float expectedW = 800f * (1179f / 2556f);
        assertEquals(expectedW, box.width, 0.1f);
        assertEquals((1600f - expectedW) / 2f, box.left, 0.1f);
        assertEquals(0f, box.top, 0.01f);

        float[] center = ScreenGeometry.touchNorm(800f, 400f, 1600f, 800f, 1179, 2556);
        assertEquals(0.5f, center[0], 0.001f);
        assertEquals(0.5f, center[1], 0.001f);
    }

    @Test
    public void shallowSplitScreenWatchBoxAlwaysRemainsPositive() {
        // Simulates very short pane (h = 120) where h - 2*band would otherwise go negative
        ScreenGeometry.WatchLayout layout = ScreenGeometry.watchBox(800f, 120f, 108f, 36f, 22f, 144f, 410, 502);
        assertTrue(layout.caseW >= 1f);
        assertTrue(layout.caseH >= 1f);
        assertTrue(layout.screenW >= 1f);
        assertTrue(layout.screenH >= 1f);
        assertTrue(Float.isFinite(layout.screenW) && Float.isFinite(layout.screenH));
    }
}
