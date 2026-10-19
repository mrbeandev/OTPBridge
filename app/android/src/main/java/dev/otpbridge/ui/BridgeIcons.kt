package dev.otpbridge.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small bundled vector set: no text glyphs, icon font or network resources. */
object BridgeIcons {
    val Back = ImageVector.Builder("Back",24.dp,24.dp,24f,24f).apply { path(fill=SolidColor(Color.Black)) { moveTo(20f,11f);lineTo(7.83f,11f);lineTo(13.42f,5.41f);lineTo(12f,4f);lineTo(4f,12f);lineTo(12f,20f);lineTo(13.42f,18.59f);lineTo(7.83f,13f);lineTo(20f,13f);close() } }.build()
    val Accounts = ImageVector.Builder("Accounts",24.dp,24.dp,24f,24f).apply {
        path(fill=SolidColor(Color.Black)) { moveTo(3f,3f); lineTo(10f,3f); lineTo(10f,10f); lineTo(3f,10f); close(); moveTo(14f,3f);lineTo(21f,3f);lineTo(21f,10f);lineTo(14f,10f);close();moveTo(3f,14f);lineTo(10f,14f);lineTo(10f,21f);lineTo(3f,21f);close();moveTo(14f,14f);lineTo(21f,14f);lineTo(21f,21f);lineTo(14f,21f);close() }
    }.build()
    val Requests = ImageVector.Builder("Requests",24.dp,24.dp,24f,24f).apply {
        path(fill=SolidColor(Color.Black)) { moveTo(15f,3f);lineTo(21f,9f);lineTo(15f,15f);lineTo(15f,11f);lineTo(3f,11f);lineTo(3f,7f);lineTo(15f,7f);close();moveTo(9f,12f);lineTo(9f,15f);lineTo(21f,15f);lineTo(21f,19f);lineTo(9f,19f);lineTo(9f,22f);lineTo(3f,17f);close() }
    }.build()
    val Browser = ImageVector.Builder("Browser",24.dp,24.dp,24f,24f).apply {
        path(fill=SolidColor(Color.Black),pathFillType=PathFillType.EvenOdd) { moveTo(2f,3f);lineTo(22f,3f);lineTo(22f,19f);lineTo(13f,19f);lineTo(13f,21f);lineTo(17f,21f);lineTo(17f,23f);lineTo(7f,23f);lineTo(7f,21f);lineTo(11f,21f);lineTo(11f,19f);lineTo(2f,19f);close();moveTo(4f,5f);lineTo(20f,5f);lineTo(20f,17f);lineTo(4f,17f);close() }
    }.build()
    val Add = ImageVector.Builder("Add",24.dp,24.dp,24f,24f).apply { path(fill=SolidColor(Color.Black)) { moveTo(11f,4f);lineTo(13f,4f);lineTo(13f,11f);lineTo(20f,11f);lineTo(20f,13f);lineTo(13f,13f);lineTo(13f,20f);lineTo(11f,20f);lineTo(11f,13f);lineTo(4f,13f);lineTo(4f,11f);lineTo(11f,11f);close() } }.build()
    val More = ImageVector.Builder("More",24.dp,24.dp,24f,24f).apply { path(fill=SolidColor(Color.Black)) { for(y in listOf(4f,11f,18f)){moveTo(10f,y);lineTo(14f,y);lineTo(14f,y+3f);lineTo(10f,y+3f);close()} } }.build()
}
