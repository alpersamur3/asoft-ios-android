package com.asoft.ios;

/**
 * Pure aspect-preserving fit, normalized touch mapping, and watch-case geometry helpers
 * (GeometrySafetyTest). Guards against zero/negative dimensions during Android Split-Screen
 * divider drags and transient surface layout passes.
 */
final class ScreenGeometry {
    static final class FitBox {
        final float left;
        final float top;
        final float width;
        final float height;

        FitBox(float left, float top, float width, float height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }
    }

    static final class WatchLayout {
        final float caseW;
        final float caseH;
        final float screenW;
        final float screenH;

        WatchLayout(float caseW, float caseH, float screenW, float screenH) {
            this.caseW = caseW;
            this.caseH = caseH;
            this.screenW = screenW;
            this.screenH = screenH;
        }
    }

    private ScreenGeometry() {}

    /**
     * Aspect-matched guest rectangle inside a view of size (@viewW x @viewH).
     * Never divides by zero and always returns positive finite width/height (>= 1f).
     */
    static FitBox fit(float viewW, float viewH, int fbW, int fbH) {
        if (viewW <= 0f || viewH <= 0f || fbW <= 0 || fbH <= 0) {
            float safeW = Math.max(1f, viewW);
            float safeH = Math.max(1f, viewH);
            return new FitBox(0f, 0f, safeW, safeH);
        }
        float g = (float) fbW / fbH;
        float w = viewW, h = viewH;
        if (viewW / viewH > g) {
            w = viewH * g;
        } else {
            h = viewW / g;
        }
        w = Math.max(1f, w);
        h = Math.max(1f, h);
        return new FitBox((viewW - w) / 2f, (viewH - h) / 2f, w, h);
    }

    /**
     * Maps view touch (@x, @y) into guest normalized coordinates ([0, 1] inside guest picture).
     * Guarantees finite coordinates even when the view is collapsed or mid-resize.
     */
    static float[] touchNorm(float x, float y, float viewW, float viewH, int fbW, int fbH) {
        FitBox box = fit(viewW, viewH, fbW, fbH);
        float nx = (x - box.left) / box.width;
        float ny = (y - box.top) / box.height;
        if (!Float.isFinite(nx)) {
            nx = 0f;
        }
        if (!Float.isFinite(ny)) {
            ny = 0f;
        }
        return new float[] {nx, ny};
    }

    /**
     * Clamps WatchCase and inner screen dimensions so shallow split-screen windows
     * never produce negative MeasureSpec sizes.
     */
    static WatchLayout watchBox(float w, float h, float crownRoom, float left, float bezel, float band,
            int fbW, int fbH) {
        float aspectW = fbW > 0 ? fbW : 1f;
        float aspectH = fbH > 0 ? fbH : 1f;
        float safeW = Math.max(1f, w);
        float safeH = Math.max(1f, h);

        float caseW = Math.max(2f * bezel + 1f, safeW - left - crownRoom);
        float screenW = Math.max(1f, caseW - 2f * bezel);
        float screenH = Math.max(1f, screenW * aspectH / aspectW);
        float caseH = screenH + 2f * bezel;

        float maxCaseH = Math.max(2f * bezel + 1f, safeH - 2f * band);
        if (caseH > maxCaseH) {
            caseH = maxCaseH;
            screenH = Math.max(1f, caseH - 2f * bezel);
            screenW = Math.max(1f, screenH * aspectW / aspectH);
            caseW = screenW + 2f * bezel;
        }
        return new WatchLayout(caseW, caseH, screenW, screenH);
    }
}
