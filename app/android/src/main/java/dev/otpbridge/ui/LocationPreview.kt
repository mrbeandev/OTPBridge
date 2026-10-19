package dev.otpbridge.ui

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/** Map tiles disclose the viewport to OSM; load them only after explicit preview consent. */
@Composable fun LocationPreview(location: JSONObject) {
 val context=LocalContext.current;val lat=location.getDouble("latitude");val lon=location.getDouble("longitude")
 var load by remember(lat,lon){mutableStateOf(false)}
 var routingMessage by remember(lat,lon){mutableStateOf<String?>(null)}
 val open: () -> Unit = {
  try { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("geo:$lat,$lon?q=$lat,$lon(Requesting%20device)"))); routingMessage=null }
  catch (_: android.content.ActivityNotFoundException) {
   routingMessage="No compatible maps app found. Opening OpenStreetMap in your browser."
   android.widget.Toast.makeText(context,routingMessage,android.widget.Toast.LENGTH_LONG).show()
   runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=16/$lat/$lon")))}.onFailure{routingMessage="No maps app or browser is available."}
  } catch (_: SecurityException) { routingMessage="Android blocked opening a maps app." }
 }
 Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text("Location",style=MaterialTheme.typography.labelLarge)
  Text("%.5f, %.5f · ±%.0f m".format(java.util.Locale.US,lat,lon,location.getDouble("accuracyMeters")),style=MaterialTheme.typography.bodySmall)
  if(load){
   val west=(lon-.01).coerceAtLeast(-180.0);val east=(lon+.01).coerceAtMost(180.0);val south=(lat-.006).coerceAtLeast(-90.0);val north=(lat+.006).coerceAtMost(90.0)
   val url="https://www.openstreetmap.org/export/embed.html?bbox=$west%2C$south%2C$east%2C$north&layer=mapnik&marker=$lat%2C$lon"
   AndroidView(factory={WebView(it).apply{settings.javaScriptEnabled=true;settings.allowFileAccess=false;settings.allowContentAccess=false;settings.domStorageEnabled=false;webViewClient=object:WebViewClient(){override fun shouldOverrideUrlLoading(view:WebView?,request:android.webkit.WebResourceRequest?):Boolean {open();return true}};loadUrl(url)}},modifier=Modifier.fillMaxWidth().height(140.dp),onRelease={it.stopLoading();it.destroy()})
   Text("Map © OpenStreetMap contributors",style=MaterialTheme.typography.labelSmall)
  }else{Text("Map preview contacts OpenStreetMap and shares this viewport. Coordinates stay local until you load or open the map.",style=MaterialTheme.typography.bodySmall);TextButton(onClick={load=true}){Text("Load map preview")}}
  TextButton(onClick=open){Text("Open in maps app ↗")}
   Text("Uses your Android maps handler. Without one, opens OpenStreetMap in the browser.",style=MaterialTheme.typography.labelSmall)
   routingMessage?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
 }}
}
