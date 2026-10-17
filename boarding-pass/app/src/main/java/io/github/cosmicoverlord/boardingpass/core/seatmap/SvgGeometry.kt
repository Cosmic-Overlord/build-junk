package io.github.cosmicoverlord.boardingpass.core.seatmap

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** 2D affine transform in SVG's `matrix(a b c d e f)` form. */
internal data class Affine(
    val a: Double = 1.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    val d: Double = 1.0,
    val e: Double = 0.0,
    val f: Double = 0.0,
) {
    fun apply(x: Double, y: Double): Pair<Double, Double> = (a * x + c * y + e) to (b * x + d * y + f)

    /** `this` after [o]: points go through [o] first. */
    operator fun times(o: Affine) = Affine(
        a * o.a + c * o.b, b * o.a + d * o.b,
        a * o.c + c * o.d, b * o.c + d * o.d,
        a * o.e + c * o.f + e, b * o.e + d * o.f + f,
    )

    companion object {
        private val fn = Regex("(matrix|translate|scale|rotate|skewX|skewY)\\s*\\(([^)]*)\\)")
        private val num = Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")

        /** SVG transform list; functions apply right to left, as in the spec. */
        fun parse(t: String?): Affine {
            if (t.isNullOrBlank()) return Affine()
            var m = Affine()
            fn.findAll(t).forEach { call ->
                val v = num.findAll(call.groupValues[2]).map { it.value.toDouble() }.toList()
                val next = when (call.groupValues[1]) {
                    "matrix" -> if (v.size >= 6) Affine(v[0], v[1], v[2], v[3], v[4], v[5]) else Affine()
                    "translate" -> Affine(e = v.getOrElse(0) { 0.0 }, f = v.getOrElse(1) { 0.0 })
                    "scale" -> v.getOrElse(0) { 1.0 }.let { sx -> Affine(a = sx, d = v.getOrElse(1) { sx }) }
                    "rotate" -> {
                        val r = Math.toRadians(v.getOrElse(0) { 0.0 })
                        val rot = Affine(cos(r), sin(r), -sin(r), cos(r))
                        if (v.size >= 3) Affine(e = v[1], f = v[2]) * rot * Affine(e = -v[1], f = -v[2]) else rot
                    }
                    "skewX" -> Affine(c = tan(Math.toRadians(v.getOrElse(0) { 0.0 })))
                    "skewY" -> Affine(b = tan(Math.toRadians(v.getOrElse(0) { 0.0 })))
                    else -> Affine()
                }
                m *= next
            }
            return m
        }
    }
}

/** Flattens SVG path data to polygons (curves sampled, arcs approximated by their chord). */
internal object SvgPath {
    private val token = Regex("[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
    private val argCount = mapOf('M' to 2, 'L' to 2, 'H' to 1, 'V' to 1, 'C' to 6, 'S' to 4, 'Q' to 4, 'T' to 2, 'A' to 7, 'Z' to 0)

    fun flatten(d: String, steps: Int = 12): List<List<Pair<Double, Double>>> {
        val t = token.findAll(d).map { it.value }.toList()
        val out = mutableListOf<List<Pair<Double, Double>>>()
        var pts = mutableListOf<Pair<Double, Double>>()
        var i = 0
        var x = 0.0
        var y = 0.0
        var sx = 0.0
        var sy = 0.0
        var cmd = 'M'
        var lastCubic: Pair<Double, Double>? = null
        var lastQuad: Pair<Double, Double>? = null

        fun cubic(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) {
            for (k in 1..steps) {
                val s = k.toDouble() / steps
                val m = 1 - s
                pts += (m * m * m * x + 3 * m * m * s * x1 + 3 * m * s * s * x2 + s * s * s * x3) to
                    (m * m * m * y + 3 * m * m * s * y1 + 3 * m * s * s * y2 + s * s * s * y3)
            }
            x = x3
            y = y3
        }

        while (i < t.size) {
            if (t[i][0].isLetter()) cmd = t[i++][0]
            val c = cmd.uppercaseChar()
            val rel = cmd.isLowerCase()
            if (c == 'Z') {
                if (pts.isNotEmpty()) {
                    out += pts
                    pts = mutableListOf()
                }
                x = sx
                y = sy
                lastCubic = null
                lastQuad = null
                continue
            }
            val n = argCount[c] ?: return out
            if (i + n > t.size) break
            val v = (0 until n).map { t[i + it].toDoubleOrNull() ?: 0.0 }
            i += n
            val ox = if (rel) x else 0.0
            val oy = if (rel) y else 0.0
            when (c) {
                'M' -> {
                    if (pts.size > 1) out += pts
                    x = ox + v[0]
                    y = oy + v[1]
                    sx = x
                    sy = y
                    pts = mutableListOf(x to y)
                    cmd = if (rel) 'l' else 'L'
                }
                'L' -> { x = ox + v[0]; y = oy + v[1]; pts += x to y }
                'H' -> { x = (if (rel) x else 0.0) + v[0]; pts += x to y }
                'V' -> { y = (if (rel) y else 0.0) + v[0]; pts += x to y }
                'C' -> {
                    lastCubic = (ox + v[2]) to (oy + v[3])
                    cubic(ox + v[0], oy + v[1], ox + v[2], oy + v[3], ox + v[4], oy + v[5])
                }
                'S' -> {
                    val c1 = lastCubic?.let { (2 * x - it.first) to (2 * y - it.second) } ?: (x to y)
                    lastCubic = (ox + v[0]) to (oy + v[1])
                    cubic(c1.first, c1.second, ox + v[0], oy + v[1], ox + v[2], oy + v[3])
                }
                'Q', 'T' -> {
                    val q = if (c == 'Q') (ox + v[0]) to (oy + v[1]) else lastQuad?.let { (2 * x - it.first) to (2 * y - it.second) } ?: (x to y)
                    val ex = ox + v[n - 2]
                    val ey = oy + v[n - 1]
                    lastQuad = q
                    cubic(x + 2.0 / 3 * (q.first - x), y + 2.0 / 3 * (q.second - y), ex + 2.0 / 3 * (q.first - ex), ey + 2.0 / 3 * (q.second - ey), ex, ey)
                }
                'A' -> { x = ox + v[5]; y = oy + v[6]; pts += x to y }
            }
            if (c != 'C' && c != 'S') lastCubic = null
            if (c != 'Q' && c != 'T') lastQuad = null
        }
        if (pts.size > 1) out += pts
        return out
    }

    /** Even-odd point-in-polygon over all rings. */
    fun contains(rings: List<List<Float>>, x: Float, y: Float): Boolean {
        var inside = false
        rings.forEach { r ->
            val n = r.size / 2
            var j = n - 1
            for (k in 0 until n) {
                val xi = r[2 * k]
                val yi = r[2 * k + 1]
                val xj = r[2 * j]
                val yj = r[2 * j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = k
            }
        }
        return inside
    }
}
