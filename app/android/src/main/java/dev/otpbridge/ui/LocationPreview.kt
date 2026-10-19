package dev.otpbridge.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.otpbridge.Telemetry
import kotlinx.coroutines.delay
import org.json.JSONObject

private class MapLoadBridge(private val done: (Boolean) -> Unit) {
    @JavascriptInterface fun complete(ok: Boolean) = done(ok)
}

/** Tiles disclose the viewport to OSM only after an explicit Load tap. */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable fun LocationPreview(location: JSONObject) {
    val context = LocalContext.current
    val lat = location.getDouble("latitude")
    val lon = location.getDouble("longitude")
    val accuracy = location.getDouble("accuracyMeters")
    var load by remember(lat, lon) { mutableStateOf(false) }
    var attempt by remember(lat, lon) { mutableIntStateOf(0) }
    var loading by remember(lat, lon) { mutableStateOf(false) }
    var error by remember(lat, lon) { mutableStateOf<String?>(null) }
    var routingMessage by remember(lat, lon) { mutableStateOf<String?>(null) }
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val html = remember(lat, lon, accuracy) { MapPreviewDocument.html(lat, lon, accuracy) }
    val open: () -> Unit = {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(Requesting%20device)")))
            routingMessage = null
        } catch (_: android.content.ActivityNotFoundException) {
            routingMessage = "No compatible maps app found. Opening OpenStreetMap in your browser."
            android.widget.Toast.makeText(context, routingMessage, android.widget.Toast.LENGTH_LONG).show()
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=16/$lat/$lon"))) }
                .onFailure { routingMessage = "No maps app or browser is available." }
        } catch (_: SecurityException) { routingMessage = "Android blocked opening a maps app." }
    }
    fun beginLoad() { error = null; loading = true; attempt++; load = true; Telemetry.track("map.preview.start") }
    LaunchedEffect(load, attempt) {
        if (load && loading) {
            delay(12000)
            if (loading) {
                loading = false; error = "Map tiles did not load. Check your connection or open a maps app."
                Telemetry.track("map.preview.error", "reason" to "timeout")
            }
        }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Location", style = MaterialTheme.typography.labelLarge)
            val accuracyText = if (accuracy >= 1000) "±%.1f km".format(java.util.Locale.US, accuracy / 1000) else "±%.0f m".format(java.util.Locale.US, accuracy)
            Text("%.5f, %.5f · %s".format(java.util.Locale.US, lat, lon, accuracyText), style = MaterialTheme.typography.bodySmall)
            if (load && error == null) {
                key(attempt) {
                    Box(Modifier.fillMaxWidth().height(160.dp)) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    setBackgroundColor(android.graphics.Color.rgb(222, 222, 216))
                                    settings.javaScriptEnabled = true
                                    settings.allowFileAccess = false; settings.allowContentAccess = false
                                    settings.domStorageEnabled = false
                                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    // Only bundled HTML executes scripts. No remote page/script can load.
                                    addJavascriptInterface(MapLoadBridge { ok -> handler.post {
                                        loading = false
                                        if (!ok) error = "Map tiles are unavailable. Retry or open a maps app."
                                        Telemetry.track(if (ok) "map.preview.loaded" else "map.preview.error", "reason" to if (ok) "tiles" else "tile-failed")
                                    } }, "OTPBridgeMap")
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                            // Subframes/redirects are never treated as user clicks.
                                            if (request?.isForMainFrame == true && request.hasGesture()) open()
                                            return true
                                        }
                                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, failure: WebResourceError?) {
                                            if (request?.isForMainFrame == true) {
                                                loading = false; error = "Map preview could not load."
                                                Telemetry.track("map.preview.error", "reason" to "webview", "code" to failure?.errorCode)
                                            }
                                        }
                                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                                            Telemetry.track("map.tile.http-error", "status" to response?.statusCode)
                                        }
                                        override fun onReceivedSslError(view: WebView?, sslHandler: SslErrorHandler?, sslError: android.net.http.SslError?) {
                                            sslHandler?.cancel() // Never bypass TLS validation.
                                            loading = false; error = "Secure map connection failed."
                                            Telemetry.track("map.preview.error", "reason" to "tls")
                                        }
                                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                            val uri = request?.url
                                            if (uri != null && uri.scheme == "https" && uri.host == "tile.openstreetmap.org" && uri.path?.matches(Regex("/[0-9]+/[0-9]+/[0-9]+\\.png")) == true) {
                                                val connection = java.net.URL(uri.toString()).openConnection() as java.net.HttpURLConnection
                                                connection.connectTimeout = 5000; connection.readTimeout = 5000; connection.instanceFollowRedirects = false
                                                connection.setRequestProperty("User-Agent", "OTPBridge/${dev.otpbridge.BuildConfig.VERSION_NAME} (Android map preview)")
                                                try {
                                                    val status = connection.responseCode
                                                    if (connection.getHeaderField("x-blocked") != null || status !in 200..299) {
                                                        handler.post { loading = false; error = "Map provider declined the preview. Open a maps app instead." }
                                                        Telemetry.track("map.preview.error", "reason" to "provider-denied", "status" to status)
                                                        return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                                                    }
                                                    val bytes = connection.inputStream.use { input ->
                                                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                                                        while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 1024 * 1024); output.write(buffer, 0, count) }
                                                        output.toByteArray()
                                                    }
                                                    return WebResourceResponse("image/png", null, java.io.ByteArrayInputStream(bytes))
                                                } catch (_: Exception) {
                                                    return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                                                } finally { connection.disconnect() }
                                            }
                                            return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                                        }
                                    }
                                    loadDataWithBaseURL("https://otpbridge-map.invalid/", html, "text/html", "UTF-8", null)
                                }
                            },
                            modifier = Modifier.matchParentSize(),
                            onRelease = { it.stopLoading(); it.removeJavascriptInterface("OTPBridgeMap"); it.destroy() },
                        )
                        if (loading) Surface(modifier = Modifier.matchParentSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                Text("Loading map…", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            } else if (!load) {
                Text("Map preview contacts OpenStreetMap and shares this viewport.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = ::beginLoad) { Text("Load map preview") }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall); TextButton(onClick = ::beginLoad) { Text("Retry map") } }
            if (load && error == null && !loading) Text("Map © OpenStreetMap contributors · approximate position", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = open) { Text("Open in maps app ↗") }
            Text("Uses your Android maps handler. Without one, opens OpenStreetMap in the browser.", style = MaterialTheme.typography.labelSmall)
            routingMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
