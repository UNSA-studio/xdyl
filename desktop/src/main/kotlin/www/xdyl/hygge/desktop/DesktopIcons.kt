package www.xdyl.hygge.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object DesktopIcons {
    private val iconColor = Color(0xFFA0C4FF)

    val Folder: ImageVector by lazy {
        ImageVector.Builder("Folder", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(iconColor), fillAlpha = 1f, pathFillType = PathFillType.NonZero) {
                moveTo(10f, 4f)
                horizontalLineTo(4f)
                curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
                lineTo(2f, 18f)
                curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
                horizontalLineTo(20f)
                curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
                verticalLineTo(8f)
                curveTo(22f, 6.9f, 21.1f, 6f, 20f, 6f)
                horizontalLineTo(12f)
                lineTo(10f, 4f)
                close()
            }
        }.build()
    }

    val Thread: ImageVector by lazy {
        ImageVector.Builder("Thread", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(iconColor), fillAlpha = 1f, pathFillType = PathFillType.NonZero) {
                moveTo(6f, 13f)
                horizontalLineTo(18f)
                verticalLineTo(11f)
                horizontalLineTo(6f)
                moveTo(6f, 17f)
                horizontalLineTo(18f)
                verticalLineTo(15f)
                horizontalLineTo(6f)
                moveTo(6f, 9f)
                horizontalLineTo(11f)
                verticalLineTo(7f)
                horizontalLineTo(6f)
                verticalLineTo(9f)
                close()
            }
        }.build()
    }

    val Export: ImageVector by lazy {
        ImageVector.Builder("Export", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(iconColor), fillAlpha = 1f, pathFillType = PathFillType.NonZero) {
                moveTo(19f, 9f)
                horizontalLineTo(15f)
                verticalLineTo(3f)
                horizontalLineTo(9f)
                verticalLineTo(9f)
                horizontalLineTo(5f)
                lineTo(12f, 16f)
                lineTo(19f, 9f)
                close()
                moveTo(5f, 18f)
                verticalLineTo(20f)
                horizontalLineTo(19f)
                verticalLineTo(18f)
                horizontalLineTo(5f)
                close()
            }
        }.build()
    }

    val Extension: ImageVector by lazy {
        ImageVector.Builder("Extension", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(iconColor), fillAlpha = 1f, pathFillType = PathFillType.NonZero) {
                moveTo(20.5f, 11f)
                horizontalLineTo(19f)
                verticalLineTo(7f)
                curveTo(19f, 5.9f, 18.1f, 5f, 17f, 5f)
                horizontalLineTo(13f)
                verticalLineTo(3.5f)
                curveTo(13f, 2.12f, 11.88f, 1f, 10.5f, 1f)
                reflectiveCurveTo(8f, 2.12f, 8f, 3.5f)
                verticalLineTo(5f)
                horizontalLineTo(4f)
                curveTo(2.9f, 5f, 2f, 5.9f, 2f, 7f)
                verticalLineToRelative(3.8f)
                horizontalLineToRelative(1.5f)
                curveToRelative(1.5f, 0f, 2.7f, 1.2f, 2.7f, 2.7f)
                reflectiveCurveToRelative(-1.2f, 2.7f, -2.7f, 2.7f)
                horizontalLineTo(2f)
                verticalLineTo(19f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(3.8f)
                verticalLineToRelative(-1.5f)
                curveToRelative(0f, -1.5f, 1.2f, -2.7f, 2.7f, -2.7f)
                reflectiveCurveToRelative(2.7f, 1.2f, 2.7f, 2.7f)
                verticalLineTo(21f)
                horizontalLineTo(17f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineToRelative(-3.8f)
                horizontalLineToRelative(1.5f)
                curveToRelative(1.5f, 0f, 2.7f, -1.2f, 2.7f, -2.7f)
                reflectiveCurveToRelative(-1.2f, -2.7f, -2.7f, -2.7f)
                close()
            }
        }.build()
    }

    val Info: ImageVector by lazy {
        ImageVector.Builder("Info", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(iconColor), fillAlpha = 1f, pathFillType = PathFillType.NonZero) {
                moveTo(11f, 18f)
                horizontalLineTo(13f)
                verticalLineTo(16f)
                horizontalLineTo(11f)
                verticalLineTo(18f)
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                reflectiveCurveToRelative(4.48f, 10f, 10f, 10f)
                reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
                reflectiveCurveTo(17.52f, 2f, 12f, 2f)
                close()
                moveTo(12f, 20f)
                curveToRelative(-4.41f, 0f, -8f, -3.59f, -8f, -8f)
                reflectiveCurveToRelative(3.59f, -8f, 8f, -8f)
                reflectiveCurveToRelative(8f, 3.59f, 8f, 8f)
                reflectiveCurveToRelative(-3.59f, 8f, -8f, 8f)
                close()
                moveTo(11f, 6f)
                horizontalLineTo(13f)
                verticalLineTo(14f)
                horizontalLineTo(11f)
                verticalLineTo(6f)
                close()
            }
        }.build()
    }
    val Ping: ImageVector by lazy {
        ImageVector.Builder("Ping", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(iconColor), fillAlpha = 1f, pathFillType = PathFillType.NonZero) {
                moveTo(4f, 12f)
                horizontalLineTo(7f)
                verticalLineTo(16f)
                horizontalLineTo(4f)
                verticalLineTo(12f)
                close()
                moveTo(8f, 7f)
                horizontalLineTo(11f)
                verticalLineTo(16f)
                horizontalLineTo(8f)
                verticalLineTo(7f)
                close()
                moveTo(12f, 3f)
                horizontalLineTo(15f)
                verticalLineTo(16f)
                horizontalLineTo(12f)
                verticalLineTo(3f)
                close()
                moveTo(16f, 11f)
                horizontalLineTo(20f)
                verticalLineTo(16f)
                horizontalLineTo(16f)
                verticalLineTo(11f)
                close()
            }
        }.build()
    }
    val ArrowBack: ImageVector by lazy {
        ImageVector.Builder("ArrowBack", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(iconColor), fillAlpha = 1f, pathFillType = PathFillType.NonZero) {
                moveTo(20f, 11f)
                horizontalLineTo(7.83f)
                lineToRelative(5.59f, -5.59f)
                lineTo(12f, 4f)
                lineToRelative(-8f, 8f)
                lineToRelative(8f, 8f)
                lineToRelative(1.41f, -1.41f)
                lineTo(7.83f, 13f)
                horizontalLineTo(20f)
                verticalLineToRelative(-2f)
                close()
            }
        }.build()
    }
    val Unlock: ImageVector by lazy {
        ImageVector.Builder("Unlock", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(iconColor), fillAlpha = 1f, pathFillType = PathFillType.NonZero) {
                moveTo(12f, 17f)
                arcTo(2f, 2f, 0f, false, false, 14f, 15f)
                curveTo(14f, 13.89f, 13.1f, 13f, 12f, 13f)
                arcTo(2f, 2f, 0f, false, false, 10f, 15f)
                arcTo(2f, 2f, 0f, false, false, 12f, 17f)
                moveTo(18f, 8f)
                arcTo(2f, 2f, 0f, false, true, 20f, 10f)
                verticalLineTo(20f)
                arcTo(2f, 2f, 0f, false, true, 18f, 22f)
                horizontalLineTo(6f)
                arcTo(2f, 2f, 0f, false, true, 4f, 20f)
                verticalLineTo(10f)
                curveTo(4f, 8.89f, 4.9f, 8f, 6f, 8f)
                horizontalLineTo(7f)
                verticalLineTo(6f)
                arcTo(5f, 5f, 0f, false, true, 12f, 1f)
                arcTo(5f, 5f, 0f, false, true, 17f, 6f)
                verticalLineTo(8f)
                horizontalLineTo(18f)
                moveTo(12f, 3f)
                arcTo(3f, 3f, 0f, false, false, 9f, 6f)
                verticalLineTo(8f)
                horizontalLineTo(15f)
                verticalLineTo(6f)
                arcTo(3f, 3f, 0f, false, false, 12f, 3f)
                close()
            }
        }.build()
    }
    val Check: ImageVector by lazy {
        ImageVector.Builder("Check", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(iconColor), fillAlpha = 1f, pathFillType = PathFillType.NonZero) {
                moveTo(21f, 7f)
                lineTo(9f, 19f)
                lineTo(3.5f, 13.5f)
                lineTo(4.91f, 12.09f)
                lineTo(9f, 16.17f)
                lineTo(19.59f, 5.59f)
                lineTo(21f, 7f)
                close()
            }
        }.build()
    }
    val Clean: ImageVector by lazy {
        ImageVector.Builder("Clean", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(iconColor), fillAlpha = 1f, pathFillType = PathFillType.NonZero) {
                moveTo(19f, 4f)
                horizontalLineTo(15.5f)
                lineTo(14.5f, 3f)
                horizontalLineTo(9.5f)
                lineTo(8.5f, 4f)
                horizontalLineTo(5f)
                verticalLineTo(6f)
                horizontalLineTo(19f)
                moveTo(6f, 19f)
                arcTo(2f, 2f, 0f, false, false, 8f, 21f)
                horizontalLineTo(16f)
                arcTo(2f, 2f, 0f, false, false, 18f, 19f)
                verticalLineTo(7f)
                horizontalLineTo(6f)
                verticalLineTo(19f)
                close()
            }
        }.build()
    }
    val Csv: ImageVector by lazy {
        ImageVector.Builder("Csv", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(iconColor), fillAlpha = 1f, pathFillType = PathFillType.NonZero) {
                moveTo(14f, 2f)
                horizontalLineTo(6f)
                arcTo(2f, 2f, 0f, false, false, 4f, 4f)
                verticalLineTo(20f)
                arcTo(2f, 2f, 0f, false, false, 6f, 22f)
                horizontalLineTo(18f)
                arcTo(2f, 2f, 0f, false, false, 20f, 20f)
                verticalLineTo(8f)
                lineTo(14f, 2f)
                moveTo(18f, 20f)
                horizontalLineTo(6f)
                verticalLineTo(4f)
                horizontalLineTo(13f)
                verticalLineTo(9f)
                horizontalLineTo(18f)
                verticalLineTo(20f)
                close()
            }
        }.build()
    }
}