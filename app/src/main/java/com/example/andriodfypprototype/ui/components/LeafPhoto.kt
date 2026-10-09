package com.example.andriodfypprototype.ui.components

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.example.andriodfypprototype.data.Noise
import com.example.andriodfypprototype.ui.theme.Wr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Close-range frame at ~30 cm, standing in for a CameraX capture. Seeds map to the scan
 * outcome sequence so the photo on the result screen matches the species the model named.
 * Shallow depth of field is faked by painting the background on a small canvas and
 * upscaling, which is also what makes these cheap enough to render per scan.
 */
object LeafPhoto {

    enum class Kind { PHALARIS, AVENA, CHENOPODIUM, CONVOLVULUS, WHEAT }

    fun kindFor(seed: Int): Kind = when (seed) {
        11, 47 -> Kind.PHALARIS
        23 -> Kind.AVENA
        31 -> Kind.CHENOPODIUM
        53 -> Kind.CONVOLVULUS
        67 -> Kind.WHEAT
        else -> Kind.entries[(seed % Kind.entries.size + Kind.entries.size) % Kind.entries.size]
    }

    private val cache = HashMap<String, ImageBitmap>()

    suspend fun load(seed: Int, w: Int = 720, h: Int = 960): ImageBitmap {
        val key = "$seed/$w/$h"
        synchronized(cache) { cache[key] }?.let { return it }
        return withContext(Dispatchers.Default) {
            render(seed, w, h).asImageBitmap().also { synchronized(cache) { cache[key] = it } }
        }
    }

    fun render(seed: Int, w: Int, h: Int): Bitmap {
        val kind = kindFor(seed)
        val rng = Random(seed * 7919)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // ---- background: soil and out-of-focus crop, painted small then scaled up
        val bw = w / 8
        val bh = h / 8
        val bg = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val bc = Canvas(bg)
        p.shader = LinearGradient(0f, 0f, 0f, bh.toFloat(), intArrayOf(0xFF4F6B3A.toInt(), 0xFF3B5230.toInt(), 0xFF5A4B36.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        bc.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), p)
        p.shader = null
        repeat(26) {
            val x = rng.nextFloat() * bw
            val y = rng.nextFloat() * bh
            val len = bh * (0.4f + rng.nextFloat() * 0.6f)
            val a = -1.2f + rng.nextFloat() * 0.5f
            p.strokeWidth = 1.5f + rng.nextFloat() * 2.5f
            p.style = Paint.Style.STROKE
            val g = 90 + rng.nextInt(60)
            p.color = (0xFF shl 24) or ((g * 0.62f).toInt() shl 16) or (g shl 8) or (g * 0.42f).toInt()
            bc.drawLine(x, y, x + cos(a) * len * 0.3f, y - sin(-a) * len, p)
        }
        p.style = Paint.Style.FILL
        // specular bokeh from the morning sun through the canopy
        repeat(9) {
            val x = rng.nextFloat() * bw
            val y = rng.nextFloat() * bh * 0.6f
            p.color = 0x30FFF4D0
            bc.drawCircle(x, y, 2f + rng.nextFloat() * 4f, p)
        }
        val bgScaled = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(bg, bw / 2, bh / 2, true), w, h, true)
        c.drawBitmap(bgScaled, 0f, 0f, null)

        // soft vignette of shadow from the operator's own hand and phone
        p.shader = RadialGradient(w * 0.55f, h * 0.45f, h * 0.75f, intArrayOf(0x00000000, 0x00000000, 0x88000000.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        // ---- mid-ground: slightly soft crop blades
        p.maskFilter = BlurMaskFilter(w * 0.008f, BlurMaskFilter.Blur.NORMAL)
        repeat(7) {
            val x0 = rng.nextFloat() * w
            blade(c, p, x0, h * 1.05f, x0 + (rng.nextFloat() - 0.5f) * w * 0.5f, h * (0.1f + rng.nextFloat() * 0.3f), w * 0.03f, 0xFF52763D.toInt(), 0xFF3F5E30.toInt())
        }
        p.maskFilter = null

        // ---- subject
        when (kind) {
            Kind.PHALARIS -> grassSubject(c, p, w, h, rng, 0xFF8DB86A.toInt(), 0xFF6E9C50.toInt(), ligule = true, hairs = false)
            Kind.AVENA -> grassSubject(c, p, w, h, rng, 0xFF84AE78.toInt(), 0xFF5F8C59.toInt(), ligule = false, hairs = true)
            Kind.WHEAT -> grassSubject(c, p, w, h, rng, 0xFF6F9E4C.toInt(), 0xFF4F7C38.toInt(), ligule = false, hairs = false, auricle = true)
            Kind.CHENOPODIUM -> chenopodium(c, p, w, h, rng)
            Kind.CONVOLVULUS -> convolvulus(c, p, w, h, rng)
        }

        // ---- sensor: warm grade, grain
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val v = px[i]
            val n = (Noise.white(x / 2, y / 2, seed) - 0.5f) * 12f
            val r = (((v shr 16) and 0xFF) * 1.02f + 6 + n).toInt().coerceIn(0, 255)
            val g = (((v shr 8) and 0xFF) * 1.0f + 3 + n).toInt().coerceIn(0, 255)
            val b = ((v and 0xFF) * 0.94f + n).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun blade(c: Canvas, p: Paint, bx: Float, by: Float, tx: Float, ty: Float, width: Float, light: Int, dark: Int) {
        val mx = (bx + tx) / 2 + (tx - bx) * 0.15f
        val my = (by + ty) / 2
        val path = Path().apply {
            moveTo(bx - width, by)
            quadTo(mx - width, my, tx, ty)
            quadTo(mx + width, my, bx + width, by)
            close()
        }
        p.shader = LinearGradient(bx - width, 0f, bx + width, 0f, intArrayOf(dark, light, dark), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
    }

    private fun grassSubject(
        c: Canvas, p: Paint, w: Int, h: Int, rng: Random, light: Int, dark: Int,
        ligule: Boolean, hairs: Boolean, auricle: Boolean = false
    ) {
        val baseX = w * (0.42f + rng.nextFloat() * 0.12f)
        val baseY = h * 0.98f
        val blades = listOf(-0.55f, -0.18f, 0.2f, 0.52f)
        // stem
        p.color = dark
        p.strokeWidth = w * 0.035f
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        c.drawLine(baseX, baseY, baseX + w * 0.02f, h * 0.58f, p)
        p.style = Paint.Style.FILL
        blades.forEachIndexed { i, a ->
            val len = h * (0.55f + rng.nextFloat() * 0.25f)
            val ox = baseX + w * 0.01f * i
            val oy = h * (0.62f + i * 0.06f)
            val tx = ox + sin(a) * len * 0.9f
            val ty = oy - cos(a) * len * 0.72f
            val width = w * (if (hairs) 0.05f else 0.042f)
            blade(c, p, ox, oy, tx, ty, width, light, dark)
            // midrib and parallel venation
            p.style = Paint.Style.STROKE
            p.strokeWidth = w * 0.004f
            p.color = 0x66E8F5D0
            c.drawLine(ox, oy, tx, ty, p)
            p.strokeWidth = w * 0.0018f
            p.color = 0x33203A18
            for (k in -2..2) if (k != 0) c.drawLine(ox + k * width * 0.3f, oy, tx, ty, p)
            if (hairs) {
                p.color = 0x88F4F7EC.toInt()
                p.strokeWidth = w * 0.0015f
                repeat(40) { j ->
                    val t = j / 40f
                    val ex = ox + (tx - ox) * t - width * (1f - t) * 0.9f
                    val ey = oy + (ty - oy) * t
                    c.drawLine(ex, ey, ex - w * 0.012f, ey - w * 0.004f, p)
                }
            }
            p.style = Paint.Style.FILL
        }
        if (ligule) {
            // membranous, translucent ligule at the collar: the diagnostic feature
            p.color = 0xCCF3F1E4.toInt()
            val lx = baseX + w * 0.02f
            val ly = h * 0.6f
            val path = Path().apply {
                moveTo(lx - w * 0.05f, ly)
                quadTo(lx - w * 0.03f, ly - h * 0.05f, lx, ly - h * 0.06f)
                quadTo(lx + w * 0.035f, ly - h * 0.05f, lx + w * 0.05f, ly)
                close()
            }
            c.drawPath(path, p)
            p.color = 0x55FFFFFF
            p.style = Paint.Style.STROKE
            p.strokeWidth = w * 0.003f
            c.drawPath(path, p)
            p.style = Paint.Style.FILL
        }
        if (auricle) {
            p.color = 0xFF9CC07C.toInt()
            c.drawOval(baseX - w * 0.05f, h * 0.575f, baseX + w * 0.0f, h * 0.595f, p)
            c.drawOval(baseX + w * 0.03f, h * 0.575f, baseX + w * 0.08f, h * 0.595f, p)
        }
    }

    private fun chenopodium(c: Canvas, p: Paint, w: Int, h: Int, rng: Random) {
        val cx = w * 0.5f
        val cy = h * 0.55f
        // stem
        p.color = 0xFF6D8C4A.toInt()
        p.style = Paint.Style.STROKE
        p.strokeWidth = w * 0.03f
        c.drawLine(cx, h * 1.0f, cx, cy - h * 0.12f, p)
        p.style = Paint.Style.FILL
        val leaves = 7
        for (i in 0 until leaves) {
            val a = (i / leaves.toFloat()) * 6.28f + rng.nextFloat() * 0.3f
            val r = w * (0.2f + (i % 3) * 0.06f)
            val lx = cx + cos(a) * r * 0.6f
            val ly = cy + sin(a) * r * 0.5f - (if (i > 4) h * 0.1f else 0f)
            goosefoot(c, p, lx, ly, a, w * (0.24f - i * 0.015f), young = i > 4)
        }
    }

    private fun goosefoot(c: Canvas, p: Paint, x: Float, y: Float, angle: Float, size: Float, young: Boolean) {
        c.save()
        c.rotate(Math.toDegrees(angle.toDouble()).toFloat() + 90f, x, y)
        val path = Path().apply {
            moveTo(x, y + size * 0.9f)
            lineTo(x - size * 0.42f, y + size * 0.15f)
            lineTo(x - size * 0.34f, y - size * 0.1f)
            lineTo(x - size * 0.2f, y - size * 0.55f)
            lineTo(x, y - size)
            lineTo(x + size * 0.2f, y - size * 0.55f)
            lineTo(x + size * 0.34f, y - size * 0.1f)
            lineTo(x + size * 0.42f, y + size * 0.15f)
            close()
        }
        val base = if (young) 0xFF9DB58A.toInt() else 0xFF6E9A55.toInt()
        p.shader = LinearGradient(x - size, y, x + size, y, intArrayOf(0xFF557A42.toInt(), base, 0xFF557A42.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        if (young) {
            // mealy white bladder hairs on the young leaves
            p.maskFilter = BlurMaskFilter(size * 0.08f, BlurMaskFilter.Blur.NORMAL)
            p.color = 0x99E7ECE4.toInt()
            c.drawOval(x - size * 0.25f, y - size * 0.6f, x + size * 0.25f, y + size * 0.3f, p)
            p.maskFilter = null
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = size * 0.02f
        p.color = 0x55E8F0D8
        c.drawLine(x, y + size * 0.85f, x, y - size * 0.9f, p)
        for (k in 1..3) {
            val yy = y + size * 0.5f - k * size * 0.3f
            c.drawLine(x, yy + size * 0.1f, x - size * 0.3f, yy - size * 0.1f, p)
            c.drawLine(x, yy + size * 0.1f, x + size * 0.3f, yy - size * 0.1f, p)
        }
        p.style = Paint.Style.FILL
        c.restore()
    }

    private fun convolvulus(c: Canvas, p: Paint, w: Int, h: Int, rng: Random) {
        // twining stem climbing a wheat culm
        p.color = 0xFF5E8A45.toInt()
        p.style = Paint.Style.STROKE
        p.strokeWidth = w * 0.018f
        c.drawLine(w * 0.46f, h.toFloat(), w * 0.5f, 0f, p)
        p.color = 0xFF7FA55B.toInt()
        p.strokeWidth = w * 0.01f
        val vine = Path().apply {
            moveTo(w * 0.4f, h.toFloat())
            var y = h.toFloat()
            var side = 1
            while (y > h * 0.1f) {
                quadTo(w * (0.48f + side * 0.1f), y - h * 0.06f, w * 0.48f, y - h * 0.12f)
                y -= h * 0.12f
                side = -side
            }
        }
        c.drawPath(vine, p)
        p.style = Paint.Style.FILL
        listOf(0.78f to -1, 0.56f to 1, 0.34f to -1, 0.18f to 1).forEach { (fy, side) ->
            arrowLeaf(c, p, w * (0.48f + side * 0.1f), h * fy, side, w * (0.2f + rng.nextFloat() * 0.05f))
        }
        // a single white trumpet flower
        p.color = 0xFFF6F1F3.toInt()
        c.drawCircle(w * 0.7f, h * 0.3f, w * 0.07f, p)
        p.color = 0xFFF1D9E2.toInt()
        c.drawCircle(w * 0.7f, h * 0.3f, w * 0.035f, p)
        p.color = 0xFFE9D27A.toInt()
        c.drawCircle(w * 0.7f, h * 0.3f, w * 0.012f, p)
    }

    private fun arrowLeaf(c: Canvas, p: Paint, x: Float, y: Float, side: Int, size: Float) {
        c.save()
        c.rotate(side * 58f, x, y)
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x - size * 0.22f, y - size * 0.05f)
            lineTo(x - size * 0.34f, y + size * 0.08f)
            quadTo(x - size * 0.2f, y - size * 0.5f, x, y - size)
            quadTo(x + size * 0.2f, y - size * 0.5f, x + size * 0.34f, y + size * 0.08f)
            lineTo(x + size * 0.22f, y - size * 0.05f)
            close()
        }
        p.shader = LinearGradient(x - size * 0.3f, y, x + size * 0.3f, y, intArrayOf(0xFF4F7A3C.toInt(), 0xFF7BA861.toInt(), 0xFF4F7A3C.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = size * 0.02f
        p.color = 0x55EAF3DA
        c.drawLine(x, y, x, y - size * 0.95f, p)
        p.style = Paint.Style.FILL
        c.restore()
    }
}

@Composable
fun LeafImage(seed: Int, modifier: Modifier = Modifier, captured: android.graphics.Bitmap? = null) {
    if (captured != null) {
        Image(captured.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
        return
    }
    val img = produceState<ImageBitmap?>(null, seed) { value = LeafPhoto.load(seed) }.value
    Box(modifier.background(Wr.Night2)) {
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize().shimmer())
    }
}
