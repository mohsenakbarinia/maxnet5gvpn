@file:Suppress("UnusedReceiverParameter")

package androidx.compose.material.icons.filled

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

val Icons.Filled.Apps: ImageVector
    get() = _apps ?: materialIcon(name = "Filled.Apps") {
        materialPath {
            moveTo(4f, 8f); horizontalLineTo(8f); verticalLineTo(4f); horizontalLineTo(4f); close()
            moveTo(10f, 20f); horizontalLineTo(14f); verticalLineTo(16f); horizontalLineTo(10f); close()
            moveTo(4f, 20f); horizontalLineTo(8f); verticalLineTo(16f); horizontalLineTo(4f); close()
            moveTo(4f, 14f); horizontalLineTo(8f); verticalLineTo(10f); horizontalLineTo(4f); close()
            moveTo(10f, 14f); horizontalLineTo(14f); verticalLineTo(10f); horizontalLineTo(10f); close()
            moveTo(16f, 4f); verticalLineTo(8f); horizontalLineTo(20f); verticalLineTo(4f); close()
            moveTo(10f, 8f); horizontalLineTo(14f); verticalLineTo(4f); horizontalLineTo(10f); close()
            moveTo(16f, 14f); horizontalLineTo(20f); verticalLineTo(10f); horizontalLineTo(16f); close()
            moveTo(16f, 20f); horizontalLineTo(20f); verticalLineTo(16f); horizontalLineTo(16f); close()
        }
    }.also { _apps = it }
private var _apps: ImageVector? = null

val Icons.Filled.Bolt: ImageVector
    get() = _bolt ?: materialIcon(name = "Filled.Bolt") {
        materialPath {
            moveTo(11f, 21f); horizontalLineTo(10f); lineTo(11f, 14f); horizontalLineTo(7.5f)
            lineTo(13f, 3f); horizontalLineTo(14f); lineTo(13f, 10f); horizontalLineTo(16.5f); close()
        }
    }.also { _bolt = it }
private var _bolt: ImageVector? = null

val Icons.Filled.BugReport: ImageVector
    get() = _bugReport ?: materialIcon(name = "Filled.BugReport") {
        materialPath {
            moveTo(20f, 8f); horizontalLineToRelative(-2.81f)
            curveToRelative(-0.45f, -0.78f, -1.07f, -1.45f, -1.82f, -1.96f)
            lineTo(17f, 4.41f); lineTo(15.59f, 3f); lineToRelative(-2.17f, 2.17f)
            curveTo(12.96f, 5.06f, 12.49f, 5f, 12f, 5f); reflectiveCurveToRelative(-0.96f, 0.06f, -1.41f, 0.17f)
            lineTo(8.41f, 3f); lineTo(7f, 4.41f); lineToRelative(1.62f, 1.63f)
            curveTo(7.88f, 6.55f, 7.26f, 7.22f, 6.81f, 8f); horizontalLineTo(4f); verticalLineToRelative(2f)
            horizontalLineToRelative(2.09f); curveToRelative(-0.05f, 0.33f, -0.09f, 0.66f, -0.09f, 1f)
            verticalLineToRelative(1f); horizontalLineTo(4f); verticalLineToRelative(2f); horizontalLineToRelative(2f)
            verticalLineToRelative(1f); curveToRelative(0f, 0.34f, 0.04f, 0.67f, 0.09f, 1f)
            horizontalLineTo(4f); verticalLineToRelative(2f); horizontalLineToRelative(2.81f)
            curveToRelative(1.04f, 1.79f, 2.97f, 3f, 5.19f, 3f); reflectiveCurveToRelative(4.15f, -1.21f, 5.19f, -3f)
            horizontalLineTo(20f); verticalLineToRelative(-2f); horizontalLineToRelative(-2.09f)
            curveToRelative(0.05f, -0.33f, 0.09f, -0.66f, 0.09f, -1f); verticalLineToRelative(-1f)
            horizontalLineToRelative(2f); verticalLineToRelative(-2f); horizontalLineToRelative(-2f)
            verticalLineToRelative(-1f); curveToRelative(0f, -0.34f, -0.04f, -0.67f, -0.09f, -1f)
            horizontalLineTo(20f); verticalLineTo(8f); close()
            moveTo(14f, 16f); horizontalLineToRelative(-4f); verticalLineToRelative(-2f); horizontalLineToRelative(4f); verticalLineToRelative(2f); close()
            moveTo(14f, 12f); horizontalLineToRelative(-4f); verticalLineToRelative(-2f); horizontalLineToRelative(4f); verticalLineToRelative(2f); close()
        }
    }.also { _bugReport = it }
private var _bugReport: ImageVector? = null

val Icons.Filled.CameraAlt: ImageVector
    get() = _cameraAlt ?: materialIcon(name = "Filled.CameraAlt") {
        materialPath {
            moveTo(9f, 2f); lineTo(7.17f, 4f); horizontalLineTo(4f)
            curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f); verticalLineToRelative(12f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(16f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineTo(6f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); horizontalLineToRelative(-3.17f)
            lineTo(15f, 2f); horizontalLineTo(9f); close()
            moveTo(12f, 17f); curveToRelative(-2.76f, 0f, -5f, -2.24f, -5f, -5f)
            reflectiveCurveToRelative(2.24f, -5f, 5f, -5f); reflectiveCurveToRelative(5f, 2.24f, 5f, 5f)
            reflectiveCurveToRelative(-2.24f, 5f, -5f, 5f); close()
        }
    }.also { _cameraAlt = it }
private var _cameraAlt: ImageVector? = null

val Icons.Filled.CheckBox: ImageVector
    get() = _checkBox ?: materialIcon(name = "Filled.CheckBox") {
        materialPath {
            moveTo(19f, 3f); horizontalLineTo(5f); curveTo(3.89f, 3f, 3f, 3.9f, 3f, 5f)
            verticalLineToRelative(14f); curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
            horizontalLineToRelative(14f); curveToRelative(1.11f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(5f); curveToRelative(0f, -1.1f, -0.89f, -2f, -2f, -2f); close()
            moveTo(10f, 17f); lineToRelative(-5f, -5f); lineToRelative(1.41f, -1.41f)
            lineTo(10f, 14.17f); lineToRelative(7.59f, -7.59f); lineTo(19f, 8f); lineToRelative(-9f, 9f); close()
        }
    }.also { _checkBox = it }
private var _checkBox: ImageVector? = null

val Icons.Filled.CheckBoxOutlineBlank: ImageVector
    get() = _checkBoxOutlineBlank ?: materialIcon(name = "Filled.CheckBoxOutlineBlank") {
        materialPath {
            moveTo(19f, 5f); verticalLineToRelative(14f); horizontalLineTo(5f); verticalLineTo(5f); horizontalLineToRelative(14f)
            moveTo(19f, 3f); horizontalLineTo(5f); curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f); curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(14f); curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(5f); curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); close()
        }
    }.also { _checkBoxOutlineBlank = it }
private var _checkBoxOutlineBlank: ImageVector? = null

val Icons.Filled.CloudSync: ImageVector
    get() = _cloudSync ?: materialIcon(name = "Filled.CloudSync") {
        materialPath {
            moveTo(19.35f, 10.04f); curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
            curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f); curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f)
            curveToRelative(0f, 3.31f, 2.69f, 6f, 6f, 6f); horizontalLineToRelative(13f)
            curveToRelative(2.76f, 0f, 5f, -2.24f, 5f, -5f)
            curveToRelative(0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f); close()
            moveTo(14f, 13f); verticalLineToRelative(4f); horizontalLineToRelative(-4f); verticalLineToRelative(-4f)
            horizontalLineTo(7f); lineToRelative(5f, -5f); lineToRelative(5f, 5f); horizontalLineToRelative(-3f); close()
        }
    }.also { _cloudSync = it }
private var _cloudSync: ImageVector? = null

val Icons.Filled.Code: ImageVector
    get() = _code ?: materialIcon(name = "Filled.Code") {
        materialPath {
            moveTo(9.4f, 16.6f); lineTo(4.8f, 12f); lineToRelative(4.6f, -4.6f)
            lineTo(8f, 6f); lineToRelative(-6f, 6f); lineToRelative(6f, 6f); lineToRelative(1.4f, -1.4f); close()
            moveTo(14.6f, 16.6f); lineToRelative(4.6f, -4.6f); lineToRelative(-4.6f, -4.6f)
            lineTo(16f, 6f); lineToRelative(6f, 6f); lineToRelative(-6f, 6f); lineToRelative(-1.4f, -1.4f); close()
        }
    }.also { _code = it }
private var _code: ImageVector? = null

val Icons.Filled.ContentCopy: ImageVector
    get() = _contentCopy ?: materialIcon(name = "Filled.ContentCopy") {
        materialPath {
            moveTo(16f, 1f); horizontalLineTo(4f); curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f); horizontalLineToRelative(2f); verticalLineTo(3f); horizontalLineToRelative(12f); verticalLineTo(1f); close()
            moveTo(19f, 5f); horizontalLineTo(8f); curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f); curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(11f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineTo(7f); curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); close()
            moveTo(19f, 21f); horizontalLineTo(8f); verticalLineTo(7f); horizontalLineToRelative(11f); verticalLineToRelative(14f); close()
        }
    }.also { _contentCopy = it }
private var _contentCopy: ImageVector? = null

val Icons.Filled.ContentPaste: ImageVector
    get() = _contentPaste ?: materialIcon(name = "Filled.ContentPaste") {
        materialPath {
            moveTo(19f, 2f); horizontalLineToRelative(-4.18f)
            curveTo(14.4f, 0.84f, 13.3f, 0f, 12f, 0f); curveToRelative(-1.3f, 0f, -2.4f, 0.84f, -2.82f, 2f)
            horizontalLineTo(5f); curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(16f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(14f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineTo(4f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); close()
            moveTo(12f, 2f); curveToRelative(0.55f, 0f, 1f, 0.45f, 1f, 1f); reflectiveCurveToRelative(-0.45f, 1f, -1f, 1f)
            reflectiveCurveToRelative(-1f, -0.45f, -1f, -1f); reflectiveCurveToRelative(0.45f, -1f, 1f, -1f); close()
            moveTo(19f, 20f); horizontalLineTo(5f); verticalLineTo(4f); horizontalLineToRelative(2f)
            verticalLineToRelative(3f); horizontalLineToRelative(10f); verticalLineTo(4f); horizontalLineToRelative(2f); verticalLineToRelative(16f); close()
        }
    }.also { _contentPaste = it }
private var _contentPaste: ImageVector? = null

val Icons.Filled.DeleteSweep: ImageVector
    get() = _deleteSweep ?: materialIcon(name = "Filled.DeleteSweep") {
        materialPath {
            moveTo(15f, 16f); horizontalLineToRelative(4f); verticalLineToRelative(2f); horizontalLineToRelative(-4f); close()
            moveTo(15f, 8f); horizontalLineToRelative(7f); verticalLineToRelative(2f); horizontalLineToRelative(-7f); close()
            moveTo(15f, 12f); horizontalLineToRelative(6f); verticalLineToRelative(2f); horizontalLineToRelative(-6f); close()
            moveTo(3f, 18f); curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(6f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineTo(8f); horizontalLineTo(3f); verticalLineToRelative(10f); close()
            moveTo(14f, 5f); horizontalLineToRelative(-3f); lineToRelative(-1f, -1f); horizontalLineTo(6f)
            lineTo(5f, 5f); horizontalLineTo(2f); verticalLineToRelative(2f); horizontalLineToRelative(12f); close()
        }
    }.also { _deleteSweep = it }
private var _deleteSweep: ImageVector? = null

val Icons.Filled.Dns: ImageVector
    get() = _dns ?: materialIcon(name = "Filled.Dns") {
        materialPath {
            moveTo(20f, 13f); horizontalLineTo(4f); curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
            verticalLineToRelative(6f); curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f); horizontalLineToRelative(16f)
            curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f); verticalLineToRelative(-6f)
            curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f); close()
            moveTo(7f, 19f); curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f); reflectiveCurveToRelative(0.9f, -2f, 2f, -2f)
            reflectiveCurveToRelative(2f, 0.9f, 2f, 2f); reflectiveCurveToRelative(-0.9f, 2f, -2f, 2f); close()
            moveTo(20f, 3f); horizontalLineTo(4f); curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
            verticalLineToRelative(6f); curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f); horizontalLineToRelative(16f)
            curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f); verticalLineTo(4f)
            curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f); close()
            moveTo(7f, 9f); curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f); reflectiveCurveToRelative(0.9f, -2f, 2f, -2f)
            reflectiveCurveToRelative(2f, 0.9f, 2f, 2f); reflectiveCurveToRelative(-0.9f, 2f, -2f, 2f); close()
        }
    }.also { _dns = it }
private var _dns: ImageVector? = null

val Icons.Filled.Download: ImageVector
    get() = _download ?: materialIcon(name = "Filled.Download") {
        materialPath {
            moveTo(5f, 20f); horizontalLineToRelative(14f); verticalLineToRelative(-2f); horizontalLineTo(5f); verticalLineToRelative(2f); close()
            moveTo(19f, 9f); horizontalLineToRelative(-4f); verticalLineTo(3f); horizontalLineTo(9f)
            verticalLineToRelative(6f); horizontalLineTo(5f); lineToRelative(7f, 7f); lineToRelative(7f, -7f); close()
        }
    }.also { _download = it }
private var _download: ImageVector? = null

val Icons.Filled.FileOpen: ImageVector
    get() = _fileOpen ?: materialIcon(name = "Filled.FileOpen") {
        materialPath {
            moveTo(14f, 2f); horizontalLineTo(6f); curveTo(4.9f, 2f, 4f, 2.9f, 4f, 4f)
            verticalLineToRelative(16f); curveToRelative(0f, 1.1f, 0.89f, 2f, 1.99f, 2f)
            horizontalLineTo(15f); verticalLineToRelative(-8f); horizontalLineToRelative(5f); verticalLineTo(8f); lineTo(14f, 2f); close()
            moveTo(13f, 9f); verticalLineTo(3.5f); lineTo(18.5f, 9f); horizontalLineTo(13f); close()
            moveTo(17f, 21.66f); verticalLineTo(16f); horizontalLineToRelative(5.66f); verticalLineToRelative(2f)
            horizontalLineToRelative(-2.24f); lineToRelative(2.95f, 2.95f); lineToRelative(-1.41f, 1.41f); lineTo(19f, 19.41f)
            verticalLineToRelative(2.24f); horizontalLineTo(17f); close()
        }
    }.also { _fileOpen = it }
private var _fileOpen: ImageVector? = null

val Icons.Filled.Image: ImageVector
    get() = _image ?: materialIcon(name = "Filled.Image") {
        materialPath {
            moveTo(21f, 19f); verticalLineTo(5f); curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            horizontalLineTo(5f); curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(14f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(14f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); close()
            moveTo(8.5f, 13.5f); lineToRelative(2.5f, 3.01f); lineTo(14.5f, 12f); lineToRelative(4.5f, 6f)
            horizontalLineTo(5f); lineToRelative(3.5f, -4.5f); close()
        }
    }.also { _image = it }
private var _image: ImageVector? = null

val Icons.Filled.Language: ImageVector
    get() = _language ?: materialIcon(name = "Filled.Language") {
        materialPath {
            moveTo(12f, 2f); curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            reflectiveCurveToRelative(4.48f, 10f, 10f, 10f); reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
            reflectiveCurveTo(17.52f, 2f, 12f, 2f); close()
            moveTo(11f, 19.93f); curveToRelative(-3.95f, -0.49f, -7f, -3.85f, -7f, -7.93f)
            curveToRelative(0f, -0.62f, 0.08f, -1.21f, 0.21f, -1.79f); lineTo(9f, 15f); verticalLineToRelative(1f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); verticalLineToRelative(1.93f); close()
            moveTo(17.9f, 17.39f); curveToRelative(-0.26f, -0.81f, -1f, -1.39f, -1.9f, -1.39f)
            horizontalLineToRelative(-1f); verticalLineToRelative(-3f); curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
            horizontalLineTo(8f); verticalLineToRelative(-2f); horizontalLineToRelative(2f)
            curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f); verticalLineTo(7f); horizontalLineToRelative(2f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineToRelative(-0.41f)
            curveToRelative(2.93f, 1.19f, 5f, 4.06f, 5f, 7.41f)
            curveToRelative(0f, 2.08f, -0.8f, 3.97f, -2.1f, 5.39f); close()
        }
    }.also { _language = it }
private var _language: ImageVector? = null

val Icons.Filled.Memory: ImageVector
    get() = _memory ?: materialIcon(name = "Filled.Memory") {
        materialPath {
            moveTo(15f, 9f); horizontalLineTo(9f); verticalLineToRelative(6f); horizontalLineToRelative(6f); verticalLineTo(9f); close()
            moveTo(13f, 13f); horizontalLineToRelative(-2f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
            moveTo(21f, 11f); verticalLineTo(9f); horizontalLineToRelative(-2f); verticalLineTo(7f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); horizontalLineToRelative(-2f); verticalLineTo(3f)
            horizontalLineToRelative(-2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); verticalLineTo(3f)
            horizontalLineTo(9f); verticalLineToRelative(2f); horizontalLineTo(7f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(2f); horizontalLineTo(3f)
            verticalLineToRelative(2f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineTo(3f)
            verticalLineToRelative(2f); horizontalLineToRelative(2f); verticalLineToRelative(2f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(2f); verticalLineToRelative(2f)
            horizontalLineToRelative(2f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); verticalLineToRelative(2f)
            horizontalLineToRelative(2f); verticalLineToRelative(-2f); horizontalLineToRelative(2f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineToRelative(-2f); horizontalLineToRelative(2f)
            verticalLineToRelative(-2f); horizontalLineToRelative(-2f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); close()
            moveTo(17f, 17f); horizontalLineTo(7f); verticalLineTo(7f); horizontalLineToRelative(10f); verticalLineToRelative(10f); close()
        }
    }.also { _memory = it }
private var _memory: ImageVector? = null

val Icons.Filled.Palette: ImageVector
    get() = _palette ?: materialIcon(name = "Filled.Palette") {
        materialPath {
            moveTo(12f, 3f); curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f)
            reflectiveCurveToRelative(4.03f, 9f, 9f, 9f); curveToRelative(0.83f, 0f, 1.5f, -0.67f, 1.5f, -1.5f)
            curveToRelative(0f, -0.39f, -0.15f, -0.74f, -0.39f, -1.01f); curveToRelative(-0.23f, -0.26f, -0.38f, -0.61f, -0.38f, -0.99f)
            curveToRelative(0f, -0.83f, 0.67f, -1.5f, 1.5f, -1.5f); horizontalLineTo(16f)
            curveToRelative(2.76f, 0f, 5f, -2.24f, 5f, -5f); curveToRelative(0f, -4.42f, -4.03f, -8f, -9f, -8f); close()
            moveTo(6.5f, 12f); curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f)
            reflectiveCurveTo(5.67f, 9f, 6.5f, 9f); reflectiveCurveTo(8f, 9.67f, 8f, 10.5f)
            reflectiveCurveTo(7.33f, 12f, 6.5f, 12f); close()
            moveTo(9.5f, 8f); curveTo(8.67f, 8f, 8f, 7.33f, 8f, 6.5f)
            reflectiveCurveTo(8.67f, 5f, 9.5f, 5f); reflectiveCurveTo(11f, 5.67f, 11f, 6.5f)
            reflectiveCurveTo(10.33f, 8f, 9.5f, 8f); close()
            moveTo(14.5f, 8f); curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f)
            reflectiveCurveTo(13.67f, 5f, 14.5f, 5f); reflectiveCurveTo(16f, 5.67f, 16f, 6.5f)
            reflectiveCurveTo(15.33f, 8f, 14.5f, 8f); close()
            moveTo(17.5f, 12f); curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f)
            reflectiveCurveTo(16.67f, 9f, 17.5f, 9f); reflectiveCurveTo(19f, 9.67f, 19f, 10.5f)
            reflectiveCurveTo(18.33f, 12f, 17.5f, 12f); close()
        }
    }.also { _palette = it }
private var _palette: ImageVector? = null

val Icons.Filled.PowerSettingsNew: ImageVector
    get() = _powerSettingsNew ?: materialIcon(name = "Filled.PowerSettingsNew") {
        materialPath {
            moveTo(13f, 3f); horizontalLineToRelative(-2f); verticalLineToRelative(10f); horizontalLineToRelative(2f); verticalLineTo(3f); close()
            moveTo(17.83f, 5.17f); lineToRelative(-1.42f, 1.42f); curveTo(17.99f, 7.86f, 19f, 9.81f, 19f, 12f)
            curveToRelative(0f, 3.87f, -3.13f, 7f, -7f, 7f); reflectiveCurveToRelative(-7f, -3.13f, -7f, -7f)
            curveToRelative(0f, -2.19f, 1.01f, -4.14f, 2.58f, -5.42f); lineTo(6.17f, 5.17f)
            curveTo(4.23f, 6.82f, 3f, 9.26f, 3f, 12f); curveToRelative(0f, 4.97f, 4.03f, 9f, 9f, 9f)
            reflectiveCurveToRelative(9f, -4.03f, 9f, -9f); curveToRelative(0f, -2.74f, -1.23f, -5.18f, -3.17f, -6.83f); close()
        }
    }.also { _powerSettingsNew = it }
private var _powerSettingsNew: ImageVector? = null

val Icons.Filled.Public: ImageVector
    get() = _public ?: materialIcon(name = "Filled.Public") {
        materialPath {
            moveTo(12f, 2f); curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            reflectiveCurveToRelative(4.48f, 10f, 10f, 10f); reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
            reflectiveCurveTo(17.52f, 2f, 12f, 2f); close()
            moveTo(11f, 19.93f); curveToRelative(-3.95f, -0.49f, -7f, -3.85f, -7f, -7.93f)
            curveToRelative(0f, -0.62f, 0.08f, -1.21f, 0.21f, -1.79f); lineTo(9f, 15f); verticalLineToRelative(1f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); verticalLineToRelative(1.93f); close()
            moveTo(17.9f, 17.39f); curveToRelative(-0.26f, -0.81f, -1f, -1.39f, -1.9f, -1.39f)
            horizontalLineToRelative(-1f); verticalLineToRelative(-3f); curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
            horizontalLineTo(8f); verticalLineToRelative(-2f); horizontalLineToRelative(2f)
            curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f); verticalLineTo(7f); horizontalLineToRelative(2f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineToRelative(-0.41f)
            curveToRelative(2.93f, 1.19f, 5f, 4.06f, 5f, 7.41f)
            curveToRelative(0f, 2.08f, -0.8f, 3.97f, -2.1f, 5.39f); close()
        }
    }.also { _public = it }
private var _public: ImageVector? = null

val Icons.Filled.PushPin: ImageVector
    get() = _pushPin ?: materialIcon(name = "Filled.PushPin") {
        materialPath {
            moveTo(16f, 9f); verticalLineTo(4f); horizontalLineToRelative(1f)
            curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f); reflectiveCurveToRelative(-0.45f, -1f, -1f, -1f)
            horizontalLineTo(7f); curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
            reflectiveCurveToRelative(0.45f, 1f, 1f, 1f); horizontalLineToRelative(1f); verticalLineToRelative(5f)
            curveToRelative(0f, 1.66f, -1.34f, 3f, -3f, 3f); verticalLineToRelative(2f); horizontalLineToRelative(5.97f)
            verticalLineToRelative(7f); lineToRelative(1f, 1f); lineToRelative(1f, -1f); verticalLineToRelative(-7f)
            horizontalLineTo(19f); verticalLineToRelative(-2f); curveToRelative(-1.66f, 0f, -3f, -1.34f, -3f, -3f); close()
        }
    }.also { _pushPin = it }
private var _pushPin: ImageVector? = null

val Icons.Filled.QrCode: ImageVector
    get() = _qrCode ?: materialIcon(name = "Filled.QrCode") {
        materialPath {
            moveTo(3f, 11f); horizontalLineToRelative(8f); verticalLineTo(3f); horizontalLineTo(3f); verticalLineToRelative(8f); close()
            moveTo(5f, 5f); horizontalLineToRelative(4f); verticalLineToRelative(4f); horizontalLineTo(5f); verticalLineTo(5f); close()
            moveTo(3f, 21f); horizontalLineToRelative(8f); verticalLineToRelative(-8f); horizontalLineTo(3f); verticalLineToRelative(8f); close()
            moveTo(5f, 15f); horizontalLineToRelative(4f); verticalLineToRelative(4f); horizontalLineTo(5f); verticalLineToRelative(-4f); close()
            moveTo(13f, 3f); verticalLineToRelative(8f); horizontalLineToRelative(8f); verticalLineTo(3f); horizontalLineToRelative(-8f); close()
            moveTo(19f, 9f); horizontalLineToRelative(-4f); verticalLineTo(5f); horizontalLineToRelative(4f); verticalLineToRelative(4f); close()
            moveTo(19f, 19f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
            moveTo(13f, 13f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
            moveTo(15f, 15f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
            moveTo(13f, 17f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
            moveTo(15f, 19f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
            moveTo(17f, 17f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
            moveTo(17f, 13f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
            moveTo(19f, 15f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
        }
    }.also { _qrCode = it }
private var _qrCode: ImageVector? = null

val Icons.Filled.Route: ImageVector
    get() = _route ?: materialIcon(name = "Filled.Route") {
        materialPath {
            moveTo(19f, 15.18f); verticalLineTo(7f); curveToRelative(0f, -2.21f, -1.79f, -4f, -4f, -4f)
            reflectiveCurveToRelative(-4f, 1.79f, -4f, 4f); verticalLineToRelative(10f)
            curveToRelative(0f, 1.1f, -0.9f, 2f, -2f, 2f); reflectiveCurveToRelative(-2f, -0.9f, -2f, -2f)
            verticalLineTo(8.82f); curveTo(8.16f, 8.4f, 9f, 7.3f, 9f, 6f)
            curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f); reflectiveCurveTo(3f, 4.34f, 3f, 6f)
            curveToRelative(0f, 1.3f, 0.84f, 2.4f, 2f, 2.82f); verticalLineTo(17f)
            curveToRelative(0f, 2.21f, 1.79f, 4f, 4f, 4f); reflectiveCurveToRelative(4f, -1.79f, 4f, -4f)
            verticalLineTo(7f); curveToRelative(0f, -1.1f, 0.9f, -2f, 2f, -2f)
            reflectiveCurveToRelative(2f, 0.9f, 2f, 2f); verticalLineToRelative(8.18f)
            curveToRelative(-1.16f, 0.41f, -2f, 1.51f, -2f, 2.82f); curveToRelative(0f, 1.66f, 1.34f, 3f, 3f, 3f)
            reflectiveCurveToRelative(3f, -1.34f, 3f, -3f); curveToRelative(0f, -1.3f, -0.84f, -2.4f, -2f, -2.82f); close()
        }
    }.also { _route = it }
private var _route: ImageVector? = null

val Icons.Filled.Schedule: ImageVector
    get() = _schedule ?: materialIcon(name = "Filled.Schedule") {
        materialPath {
            moveTo(11.99f, 2f); curveTo(6.47f, 2f, 2f, 6.48f, 2f, 12f)
            reflectiveCurveToRelative(4.47f, 10f, 9.99f, 10f); curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
            reflectiveCurveTo(17.52f, 2f, 11.99f, 2f); close()
            moveTo(12f, 20f); curveToRelative(-4.42f, 0f, -8f, -3.58f, -8f, -8f)
            reflectiveCurveToRelative(3.58f, -8f, 8f, -8f); reflectiveCurveToRelative(8f, 3.58f, 8f, 8f)
            reflectiveCurveToRelative(-3.58f, 8f, -8f, 8f); close()
            moveTo(12.5f, 7f); horizontalLineTo(11f); verticalLineToRelative(6f); lineToRelative(5.25f, 3.15f)
            lineToRelative(0.75f, -1.23f); lineToRelative(-4.5f, -2.67f); close()
        }
    }.also { _schedule = it }
private var _schedule: ImageVector? = null

val Icons.Filled.Security: ImageVector
    get() = _security ?: materialIcon(name = "Filled.Security") {
        materialPath {
            moveTo(12f, 1f); lineTo(3f, 5f); verticalLineToRelative(6f)
            curveToRelative(0f, 5.55f, 3.84f, 10.74f, 9f, 12f); curveToRelative(5.16f, -1.26f, 9f, -6.45f, 9f, -12f)
            verticalLineTo(5f); lineToRelative(-9f, -4f); close()
            moveTo(12f, 11.99f); horizontalLineToRelative(7f)
            curveToRelative(-0.53f, 4.12f, -3.28f, 7.79f, -7f, 8.94f); verticalLineTo(12f)
            horizontalLineTo(5f); verticalLineTo(6.3f); lineToRelative(7f, -3.11f); verticalLineToRelative(8.8f); close()
        }
    }.also { _security = it }
private var _security: ImageVector? = null

val Icons.Filled.Speed: ImageVector
    get() = _speed ?: materialIcon(name = "Filled.Speed") {
        materialPath {
            moveTo(20.38f, 8.57f); lineToRelative(-1.23f, 1.85f); curveToRelative(1.22f, 3.31f, 0.47f, 7.07f, -1.87f, 9.58f)
            horizontalLineTo(6.72f); curveTo(4.11f, 17.2f, 3.56f, 12.71f, 5.51f, 9.18f)
            curveToRelative(1.95f, -3.53f, 6.08f, -5.06f, 9.87f, -3.65f); lineToRelative(1.85f, -1.23f)
            curveTo(12.69f, 2.2f, 7.15f, 3.89f, 4.28f, 8.15f); curveToRelative(-2.87f, 4.26f, -2.31f, 9.99f, 1.32f, 13.62f)
            curveToRelative(0.37f, 0.37f, 0.88f, 0.58f, 1.41f, 0.58f); horizontalLineToRelative(9.98f)
            curveToRelative(0.53f, 0f, 1.04f, -0.21f, 1.41f, -0.58f)
            curveToRelative(3.63f, -3.63f, 4.19f, -9.36f, 1.98f, -13.2f); close()
            moveTo(10.59f, 15.41f); curveToRelative(0.78f, 0.78f, 2.05f, 0.78f, 2.83f, 0f)
            lineToRelative(5.66f, -8.49f); lineToRelative(-8.49f, 5.66f)
            curveToRelative(-0.78f, 0.78f, -0.78f, 2.05f, 0f, 2.83f); close()
        }
    }.also { _speed = it }
private var _speed: ImageVector? = null

val Icons.Filled.StarBorder: ImageVector
    get() = _starBorder ?: materialIcon(name = "Filled.StarBorder") {
        materialPath {
            moveTo(22f, 9.24f); lineToRelative(-7.19f, -0.62f); lineTo(12f, 2f); lineTo(9.19f, 8.63f)
            lineTo(2f, 9.24f); lineToRelative(5.46f, 4.73f); lineTo(5.82f, 21f); lineTo(12f, 17.27f)
            lineTo(18.18f, 21f); lineToRelative(-1.63f, -7.03f); lineTo(22f, 9.24f); close()
            moveTo(12f, 15.4f); lineToRelative(-3.76f, 2.27f); lineToRelative(1f, -4.28f)
            lineToRelative(-3.32f, -2.88f); lineToRelative(4.38f, -0.38f); lineTo(12f, 6.1f)
            lineToRelative(1.71f, 4.04f); lineToRelative(4.38f, 0.38f); lineToRelative(-3.32f, 2.88f)
            lineToRelative(1f, 4.28f); lineTo(12f, 15.4f); close()
        }
    }.also { _starBorder = it }
private var _starBorder: ImageVector? = null

val Icons.Filled.SwapVert: ImageVector
    get() = _swapVert ?: materialIcon(name = "Filled.SwapVert") {
        materialPath {
            moveTo(16f, 17.01f); verticalLineTo(10f); horizontalLineToRelative(-2f); verticalLineToRelative(7.01f)
            horizontalLineToRelative(-3f); lineTo(15f, 21f); lineToRelative(4f, -3.99f); horizontalLineToRelative(-3f); close()
            moveTo(9f, 3f); lineTo(5f, 6.99f); horizontalLineToRelative(3f); verticalLineTo(14f)
            horizontalLineToRelative(2f); verticalLineTo(6.99f); horizontalLineToRelative(3f); lineTo(9f, 3f); close()
        }
    }.also { _swapVert = it }
private var _swapVert: ImageVector? = null

val Icons.Filled.SystemUpdate: ImageVector
    get() = _systemUpdate ?: materialIcon(name = "Filled.SystemUpdate") {
        materialPath {
            moveTo(17f, 1.01f); lineTo(7f, 1f); curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(18f); curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(10f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineTo(3f); curveToRelative(0f, -1.1f, -0.9f, -1.99f, -2f, -1.99f); close()
            moveTo(17f, 19f); horizontalLineTo(7f); verticalLineTo(5f); horizontalLineToRelative(10f); verticalLineToRelative(14f); close()
            moveTo(16f, 13f); horizontalLineToRelative(-3f); verticalLineTo(8f); horizontalLineToRelative(-2f)
            verticalLineToRelative(5f); horizontalLineTo(8f); lineToRelative(4f, 4f); lineToRelative(4f, -4f); close()
        }
    }.also { _systemUpdate = it }
private var _systemUpdate: ImageVector? = null

val Icons.Filled.Upload: ImageVector
    get() = _upload ?: materialIcon(name = "Filled.Upload") {
        materialPath {
            moveTo(5f, 20f); horizontalLineToRelative(14f); verticalLineToRelative(-2f); horizontalLineTo(5f); verticalLineToRelative(2f); close()
            moveTo(5f, 10f); horizontalLineToRelative(4f); verticalLineToRelative(6f); horizontalLineToRelative(6f)
            verticalLineToRelative(-6f); horizontalLineToRelative(4f); lineToRelative(-7f, -7f); lineToRelative(-7f, 7f); close()
        }
    }.also { _upload = it }
private var _upload: ImageVector? = null
