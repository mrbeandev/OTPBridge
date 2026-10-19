package dev.otpbridge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Bundled monochrome browser glyphs; no network assets or icon font. */
@Composable fun BrowserMark(browser: String, modifier: Modifier = Modifier) {
    Canvas(modifier.size(56.dp).semantics { contentDescription = "$browser browser icon" }) {
        val u = size.minDimension / 24f
        val ink = Color(0xff888888)
        val center = Offset(12*u,12*u)
        when (browser) {
            "Chrome" -> {
                drawCircle(ink,10*u,center,style=Stroke(1.7f*u))
                drawCircle(ink,4*u,center,style=Stroke(1.7f*u))
                drawLine(ink,Offset(12*u,8*u),Offset(21*u,8*u),1.7f*u)
                drawLine(ink,Offset(8.5f*u,14*u),Offset(4*u,6*u),1.7f*u)
                drawLine(ink,Offset(15.5f*u,14*u),Offset(11*u,22*u),1.7f*u)
            }
            "Safari" -> {
                drawCircle(ink,10*u,center,style=Stroke(1.5f*u))
                val needle=Path().apply { moveTo(16*u,6*u);lineTo(14*u,14*u);lineTo(6*u,18*u);lineTo(10*u,10*u);close() }
                drawPath(needle,ink)
            }
            "Edge" -> {
                drawArc(ink,35f,300f,false,Offset(2*u,2*u),Size(20*u,20*u),style=Stroke(3*u))
                drawLine(ink,Offset(5*u,12*u),Offset(19*u,12*u),2*u)
                drawCircle(ink,2.5f*u,Offset(8*u,16*u))
            }
            "Firefox" -> {
                drawCircle(ink,8*u,Offset(12*u,13*u),style=Stroke(2*u))
                val ears=Path().apply { moveTo(4*u,12*u);lineTo(5*u,3*u);lineTo(10*u,7*u);moveTo(14*u,7*u);lineTo(19*u,3*u);lineTo(20*u,12*u) }
                drawPath(ears,ink,style=Stroke(2*u))
                drawArc(ink,0f,240f,false,Offset(7*u,8*u),Size(10*u,10*u),style=Stroke(2*u))
            }
            "Opera" -> drawOval(ink,Offset(4*u,2*u),Size(16*u,20*u),style=Stroke(3*u))
            else -> {
                drawRect(ink,Offset(2*u,3*u),Size(20*u,18*u),style=Stroke(1.5f*u))
                drawLine(ink,Offset(2*u,8*u),Offset(22*u,8*u),1.5f*u)
                for(x in listOf(5f,8f,11f))drawCircle(ink,.7f*u,Offset(x*u,5.5f*u))
            }
        }
    }
}
