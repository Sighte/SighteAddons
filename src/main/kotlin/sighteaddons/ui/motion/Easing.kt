package sighteaddons.ui.motion

/**
 * An easing curve: progress in, eased progress out, both nominally `0f..1f`.
 *
 * A `fun interface` so the named curves below are singletons — an easing is evaluated once per
 * animated property per frame, and none of that may allocate.
 */
internal fun interface Easing {

    fun ease(t: Float): Float

    companion object {

        /**
         * Standard and emphasized decelerate — `cubic-bezier(0.2, 0, 0, 1)`.
         *
         * The default for anything that moves between two resting states.
         */
        val STANDARD: Easing = CubicBezier(0.2f, 0f, 0f, 1f)

        /** Entrance — `cubic-bezier(0.05, 0.7, 0.1, 1)`. Fast off the mark, long settle. */
        val ENTRANCE: Easing = CubicBezier(0.05f, 0.7f, 0.1f, 1f)

        /** Exit — `cubic-bezier(0.3, 0, 0.8, 0.15)`. Accelerates away; nothing waits for it. */
        val EXIT: Easing = CubicBezier(0.3f, 0f, 0.8f, 0.15f)

        /**
         * No easing.
         *
         * Legitimate for exactly two things — a continuous rotation and a shimmer sweep — because
         * both are travelling through, not arriving. Anything that arrives with linear easing looks
         * like it hit a wall.
         */
        val LINEAR: Easing = Easing { t -> if (t <= 0f) 0f else if (t >= 1f) 1f else t }

        /** Ambient breathing. A sine over the full period, so the loop has no seam. */
        val SINE: Easing = Easing { t ->
            (0.5 - 0.5 * Math.cos(t.toDouble() * 2.0 * Math.PI)).toFloat()
        }
    }
}

/**
 * A CSS-style cubic Bézier through `(0,0)` and `(1,1)` with two control points.
 *
 * The curve is parametric — `x` and `y` are both functions of an internal parameter `t` that is not
 * the input — so evaluating it means first solving `x(t) = input` for `t`, then returning `y(t)`.
 * Newton-Raphson does that in a handful of iterations for every curve used here; the bisection
 * fallback exists for the flat stretches where the derivative approaches zero and Newton would step
 * off the interval.
 */
internal class CubicBezier(x1: Float, y1: Float, x2: Float, y2: Float) : Easing {

    // Polynomial coefficients, from expanding the Bézier with P0 = (0,0) and P3 = (1,1).
    private val ax = 1f - 3f * x2 + 3f * x1
    private val bx = 3f * x2 - 6f * x1
    private val cx = 3f * x1

    private val ay = 1f - 3f * y2 + 3f * y1
    private val by = 3f * y2 - 6f * y1
    private val cy = 3f * y1

    override fun ease(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        return sampleY(solveT(t))
    }

    private fun sampleX(t: Float): Float = ((ax * t + bx) * t + cx) * t

    private fun sampleY(t: Float): Float = ((ay * t + by) * t + cy) * t

    private fun slopeX(t: Float): Float = (3f * ax * t + 2f * bx) * t + cx

    /** Solve `sampleX(t) == x` for `t`. */
    private fun solveT(x: Float): Float {
        var t = x
        for (i in 0 until NEWTON_STEPS) {
            val slope = slopeX(t)
            if (Math.abs(slope) < EPSILON) break
            val error = sampleX(t) - x
            if (Math.abs(error) < EPSILON) return t
            t -= error / slope
        }

        // Newton stalled or wandered out of range. Bisection cannot fail here: x(t) is monotonic on
        // [0,1] for every curve this class is constructed with.
        var low = 0f
        var high = 1f
        t = x
        while (high - low > EPSILON) {
            if (sampleX(t) < x) low = t else high = t
            t = (low + high) * 0.5f
        }
        return t
    }

    private companion object {
        const val NEWTON_STEPS = 8
        const val EPSILON = 1e-5f
    }
}
