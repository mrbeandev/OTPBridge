package dev.otpbridge.ui

import java.util.Locale
import kotlin.math.*

/** Self-contained raster map, without remote scripts/iframe navigation. */
object MapPreviewDocument {
    data class Viewport(val zoom: Int, val tileX: Int, val tileY: Int, val offsetX: Double, val offsetY: Double)
    fun viewport(latitude: Double, longitude: Double, accuracyMeters: Double): Viewport {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(accuracyMeters.isFinite() && accuracyMeters >= 0)
        val lat = latitude.coerceIn(-85.05112878, 85.05112878)
        // A coarse location must not look like a precise street-level fix.
        val zoom = floor(log2(156543.03392 * cos(Math.toRadians(lat)) * 180.0 / max(accuracyMeters * 2, 500.0))).toInt().coerceIn(2, 16)
        val count = 1 shl zoom
        val x = (longitude + 180.0) / 360.0 * count
        val rad = Math.toRadians(lat)
        val y = (1 - ln(tan(rad) + 1 / cos(rad)) / PI) / 2 * count
        val tx = floor(x).toInt().coerceIn(0, count - 1)
        val ty = floor(y).toInt().coerceIn(0, count - 1)
        return Viewport(zoom, tx, ty, (x - floor(x)) * 256, (y - floor(y)) * 256)
    }
    fun html(latitude: Double, longitude: Double, accuracyMeters: Double): String {
        val v = viewport(latitude, longitude, accuracyMeters)
        val count = 1 shl v.zoom
        val tiles = buildString {
            for (dy in -1..1) for (dx in -1..1) {
                val y = v.tileY + dy
                if (y !in 0 until count) continue
                val x = ((v.tileX + dx) % count + count) % count
                val left = 256.0 * (dx + 1)
                val top = 256.0 * (dy + 1)
                append("<img alt=\"\" draggable=\"false\" referrerpolicy=\"no-referrer\" src=\"https://tile.openstreetmap.org/${v.zoom}/$x/$y.png\" style=\"left:${left}px;top:${top}px\">")
            }
        }
        val offsetX = String.format(Locale.US, "%.4f", 256 + v.offsetX)
        val offsetY = String.format(Locale.US, "%.4f", 256 + v.offsetY)
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"><meta name="referrer" content="no-referrer"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https://tile.openstreetmap.org; style-src 'unsafe-inline'; script-src 'unsafe-inline'"><style>
html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#deded8}#tiles{position:absolute;left:calc(50% - ${offsetX}px);top:calc(50% - ${offsetY}px);width:768px;height:768px}img{position:absolute;width:256px;height:256px}.pin{position:absolute;left:50%;top:50%;width:14px;height:14px;margin:-9px;border:3px solid white;border-radius:50%;background:#252525;box-shadow:0 0 0 14px #2222,0 1px 4px #555}.label{position:absolute;bottom:6px;right:6px;padding:3px 6px;font:11px sans-serif;background:#ffffffe8;color:#252525;border-radius:3px}
</style></head><body><div id="tiles">$tiles</div><div class="pin"></div><span class="label">Map data © OpenStreetMap</span><script>
const images=[...document.images];let remaining=images.length;let centerLoaded=false;
function done(img,ok){if(ok&&img.src.includes('/${v.tileX}/${v.tileY}.png'))centerLoaded=true;if(--remaining===0)OTPBridgeMap.complete(centerLoaded);}
images.forEach(img=>{if(img.complete)done(img,img.naturalWidth>0);else{img.onload=()=>done(img,true);img.onerror=()=>done(img,false);}});
</script></body></html>"""
    }
}
