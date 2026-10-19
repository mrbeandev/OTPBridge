package dev.otpbridge.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small bundled vector set: no text glyphs, icon font or network resources. */
object BridgeIcons {
    val Bug = ImageVector.Builder("Bug",24.dp,24.dp,24f,24f).apply { path(fill=SolidColor(Color.Black),pathFillType=PathFillType.EvenOdd) {
        moveTo(8f,7f);lineTo(16f,7f);lineTo(17f,9f);lineTo(17f,16f);curveTo(17f,19f,15f,21f,12f,21f);curveTo(9f,21f,7f,19f,7f,16f);lineTo(7f,9f);close()
        moveTo(9f,9.5f);lineTo(15f,9.5f);lineTo(15f,16f);curveTo(15f,17.7f,13.7f,19f,12f,19f);curveTo(10.3f,19f,9f,17.7f,9f,16f);close()
        moveTo(11f,11f);lineTo(13f,11f);lineTo(13f,17f);lineTo(11f,17f);close()
        moveTo(9f,3f);lineTo(10.4f,2.3f);lineTo(11.6f,5f);lineTo(12.4f,5f);lineTo(13.6f,2.3f);lineTo(15f,3f);lineTo(14f,6f);lineTo(10f,6f);close()
        moveTo(2f,10f);lineTo(6.5f,10f);lineTo(6.5f,12f);lineTo(2f,12f);close();moveTo(17.5f,10f);lineTo(22f,10f);lineTo(22f,12f);lineTo(17.5f,12f);close()
        moveTo(2.5f,17f);lineTo(6.5f,14.5f);lineTo(6.8f,16.6f);lineTo(3.5f,18.7f);close();moveTo(21.5f,17f);lineTo(17.5f,14.5f);lineTo(17.2f,16.6f);lineTo(20.5f,18.7f);close()
    } }.build()
    val Eye = ImageVector.Builder("Eye",24.dp,24.dp,24f,24f).apply { path(fill=SolidColor(Color.Black),pathFillType=PathFillType.EvenOdd) { moveTo(1f,12f);curveTo(5f,3f,19f,3f,23f,12f);curveTo(19f,21f,5f,21f,1f,12f);close();moveTo(3.5f,12f);curveTo(7f,5.5f,17f,5.5f,20.5f,12f);curveTo(17f,18.5f,7f,18.5f,3.5f,12f);close() };path(fill=SolidColor(Color.Black)){moveTo(15f,12f);curveTo(15f,16f,9f,16f,9f,12f);curveTo(9f,8f,15f,8f,15f,12f);close()} }.build()
    val EyeOff = ImageVector.Builder("EyeOff",24.dp,24.dp,24f,24f).apply { path(fill=SolidColor(Color.Black)) { moveTo(3f,2f);lineTo(22f,21f);lineTo(20.5f,22.5f);lineTo(1.5f,3.5f);close();moveTo(7f,5f);curveTo(14f,2f,21f,6f,23f,12f);lineTo(21f,15f);lineTo(19.5f,13.5f);lineTo(20.5f,12f);curveTo(18f,7f,13f,5f,9f,7f);close();moveTo(2f,9f);lineTo(4f,10.5f);lineTo(3.5f,12f);curveTo(6f,17f,11f,19f,15f,17f);lineTo(17f,19f);curveTo(10f,22f,3f,18f,1f,12f);close() } }.build()
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
