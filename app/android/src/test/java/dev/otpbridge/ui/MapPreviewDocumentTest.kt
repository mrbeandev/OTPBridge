package dev.otpbridge.ui

import org.junit.Assert.*
import org.junit.Test

class MapPreviewDocumentTest {
    @Test fun usesBundledDocumentAndRasterTilesNotExternalIframeScripts() {
        val html = MapPreviewDocument.html(11.282, 75.7727, 124893.0)
        assertTrue(html.contains("https://tile.openstreetmap.org/"))
        assertTrue(html.contains("OTPBridgeMap.complete(centerLoaded)"))
        assertFalse(html.contains("<iframe"))
        assertFalse(html.contains("script src="))
        assertFalse(html.contains("export/embed.html"))
        assertEquals(9, Regex("<img ").findAll(html).count())
    }
    @Test fun coarseAccuracyZoomsOutInsteadOfPretendingStreetPrecision() {
        val coarse = MapPreviewDocument.viewport(11.282, 75.77, 124893.0)
        val fine = MapPreviewDocument.viewport(11.282, 75.77, 30.0)
        assertTrue(coarse.zoom < fine.zoom)
        assertTrue(coarse.zoom <= 8)
        assertTrue(fine.offsetX in 0.0..256.0)
        assertTrue(fine.offsetY in 0.0..256.0)
    }
    @Test fun boundsPolesAndWrapsDatelineTiles() {
        val html = MapPreviewDocument.html(90.0, 180.0, 10.0)
        assertFalse(html.contains("NaN")); assertFalse(html.contains("Infinity"))
        assertThrows(IllegalArgumentException::class.java) { MapPreviewDocument.viewport(Double.NaN, 0.0, 1.0) }
    }
}
