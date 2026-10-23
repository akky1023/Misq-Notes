package jp.handwriting.note.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Small vector assets keep the toolbar crisp at every display density.
internal object ToolbarIcons {
    val FreeLasso = icon("FreeLasso") {
        moveTo(7f, 5f)
        curveTo(7.618366f, 4.175511f, 8.449165f, 3.605941f, 9.387306f, 3.251004f)
        moveTo(12.035035f, 2.785214f)
        curveTo(13.037762f, 2.776444f, 14.054366f, 2.932538f, 14.99217f, 3.21797f)
        moveTo(17.417033f, 4.3833f)
        curveTo(18.204573f, 4.951625f, 18.776742f, 5.640922f, 19f, 6.4f)
        curveTo(19.059493f, 6.522953f, 19.109548f, 6.642603f, 19.150988f, 6.759192f)
        moveTo(18.778981f, 9.337114f)
        curveTo(18.2513f, 10.251533f, 17.569642f, 11.055239f, 17.90832f, 12.095186f)
        moveTo(19.519833f, 14.23956f)
        curveTo(20.255752f, 15.258791f, 20.448443f, 16.216166f, 20.228035f, 17.052108f)
        moveTo(18.547245f, 19.08277f)
        curveTo(17.786729f, 19.544785f, 16.820044f, 19.878296f, 15.72631f, 20.04708f)
        moveTo(13.031534f, 20.129264f)
        curveTo(12.692126f, 20.100799f, 12.347742f, 20.057957f, 12f, 20f)
        curveTo(11.333456f, 20f, 10.679252f, 19.950635f, 10.046987f, 19.856019f)
        moveTo(7.441386f, 19.162461f)
        curveTo(6.45333f, 18.768494f, 5.582491f, 18.246744f, 4.885287f, 17.62139f)
        moveTo(3.359086f, 15.425504f)
        curveTo(3.041328f, 14.528779f, 3.00608f, 13.535247f, 3.329731f, 12.477645f)
        moveTo(4.557942f, 10.080708f)
        curveTo(5.205055f, 8.891993f, 5.361642f, 8.05328f, 5.602971f, 7.276937f)
    }
    val RectangleLasso = icon("RectangleLasso") {
        moveTo(7.660869f, 6f)
        lineTo(10.660869f, 6f)
        moveTo(13.339131f, 6f)
        lineTo(16.339131f, 6f)
        moveTo(19.017394f, 6f)
        lineTo(19.339131f, 6f)
        curveTo(20.167558f, 6f, 20.839131f, 6.671573f, 20.839131f, 7.5f)
        lineTo(20.839131f, 7.821737f)
        moveTo(20.839131f, 10.5f)
        lineTo(20.839131f, 13.5f)
        moveTo(20.839131f, 16.178263f)
        lineTo(20.839131f, 16.5f)
        curveTo(20.839131f, 17.328427f, 20.167558f, 18f, 19.339131f, 18f)
        lineTo(19.017394f, 18f)
        moveTo(16.339131f, 18f)
        lineTo(13.339131f, 18f)
        moveTo(10.660869f, 18f)
        lineTo(7.660869f, 18f)
        moveTo(4.982606f, 18f)
        lineTo(4.660869f, 18f)
        curveTo(3.832442f, 18f, 3.160869f, 17.328427f, 3.160869f, 16.5f)
        lineTo(3.160869f, 16.178263f)
        moveTo(3.160869f, 13.5f)
        lineTo(3.160869f, 10.5f)
        moveTo(3.160869f, 7.821737f)
        lineTo(3.160869f, 7.5f)
        curveTo(3.160869f, 6.671573f, 3.832442f, 6f, 4.660869f, 6f)
        lineTo(4.982606f, 6f)
    }
    val Pdf = icon("Pdf") {
        // Three continuous loops: a tall upper loop, a lower-left loop and a right loop.
        moveTo(11f, 3f)
        curveTo(7.5f, -1f, 9f, 11f, 14.5f, 15f)
        curveTo(20f, 19f, 25f, 15f, 20f, 13.5f)
        curveTo(15f, 12f, 4f, 15.5f, 2.5f, 19.5f)
        curveTo(1f, 24f, 7f, 20.5f, 10f, 13f)
        curveTo(12f, 8f, 13f, 4f, 11f, 3f)
        close()
    }
    val Language = icon("Language") {
        moveTo(21f, 12f); curveTo(21f, 24f, 3f, 24f, 3f, 12f); curveTo(3f, 0f, 21f, 0f, 21f, 12f)
        moveTo(12f, 3f); curveTo(5f, 8f, 5f, 16f, 12f, 21f); curveTo(19f, 16f, 19f, 8f, 12f, 3f)
        moveTo(3f, 12f); lineTo(21f, 12f)
    }
    val Image = icon("Image") {
        moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 20f); lineTo(3f, 20f); close()
        moveTo(3f, 17f); lineTo(9f, 11f); lineTo(14f, 16f); lineTo(17f, 13f); lineTo(21f, 17f)
        moveTo(16f, 8f); lineTo(16.1f, 8f)
    }
    val More = icon("More") { for (y in listOf(5f, 12f, 19f)) { moveTo(12f, y); lineTo(12.1f, y) } }
    val Copy = icon("Copy") { moveTo(8f, 8f); lineTo(21f, 8f); lineTo(21f, 21f); lineTo(8f, 21f); close(); moveTo(16f, 4f); lineTo(4f, 4f); lineTo(4f, 16f) }
    val Paste = icon("Paste") { moveTo(8f, 5f); lineTo(4f, 5f); lineTo(4f, 21f); lineTo(20f, 21f); lineTo(20f, 5f); lineTo(16f, 5f); moveTo(8f, 3f); lineTo(16f, 3f); lineTo(16f, 8f); lineTo(8f, 8f); close() }
    val Capture = icon("Capture") { moveTo(3f, 7f); lineTo(7f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(17f, 7f); lineTo(21f, 7f); lineTo(21f, 20f); lineTo(3f, 20f); close(); moveTo(16f, 13f); curveTo(16f, 18.3f, 8f, 18.3f, 8f, 13f); curveTo(8f, 7.7f, 16f, 7.7f, 16f, 13f) }
    val Share = icon("Share") { moveTo(12f, 16f); lineTo(12f, 3f); moveTo(7f, 8f); lineTo(12f, 3f); lineTo(17f, 8f); moveTo(5f, 12f); lineTo(5f, 21f); lineTo(19f, 21f); lineTo(19f, 12f) }
    val Folder = icon("Folder") { moveTo(3f, 6f); lineTo(10f, 6f); lineTo(12f, 9f); lineTo(21f, 9f); lineTo(21f, 20f); lineTo(3f, 20f); close() }
    val Grid = icon("Grid") { for (x in listOf(4f, 14f)) for (y in listOf(4f, 14f)) { moveTo(x, y); lineTo(x + 6, y); lineTo(x + 6, y + 6); lineTo(x, y + 6); close() } }
    val List = icon("List") { for (y in listOf(5f, 12f, 19f)) { moveTo(4f, y); lineTo(6f, y); moveTo(10f, y); lineTo(21f, y) } }
    val Created = icon("Created") { moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 21f); lineTo(3f, 21f); close(); moveTo(7f, 3f); lineTo(7f, 8f); moveTo(17f, 3f); lineTo(17f, 8f); moveTo(8f, 14f); lineTo(16f, 14f); moveTo(12f, 10f); lineTo(12f, 18f) }
    val Updated = icon("Updated") { moveTo(21f, 12f); curveTo(21f, 24f, 3f, 24f, 3f, 12f); curveTo(3f, 0f, 21f, 0f, 21f, 12f); moveTo(12f, 7f); lineTo(12f, 12f); lineTo(16f, 15f) }
    val Paper = icon("Paper") { moveTo(5f, 3f); lineTo(19f, 3f); lineTo(19f, 21f); lineTo(5f, 21f); close(); for (y in listOf(8f, 12f, 16f)) { moveTo(8f, y); lineTo(16f, y) } }
    val Back = icon("Back") { moveTo(19f, 12f); lineTo(5f, 12f); moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) }
    val Settings = icon("Settings") {
        moveTo(10f, 2f); lineTo(14f, 2f); lineTo(14.5f, 5f); lineTo(17f, 6.5f)
        lineTo(19.8f, 5.5f); lineTo(21.8f, 9f); lineTo(19.5f, 11f); lineTo(19.5f, 13f)
        lineTo(21.8f, 15f); lineTo(19.8f, 18.5f); lineTo(17f, 17.5f); lineTo(14.5f, 19f)
        lineTo(14f, 22f); lineTo(10f, 22f); lineTo(9.5f, 19f); lineTo(7f, 17.5f)
        lineTo(4.2f, 18.5f); lineTo(2.2f, 15f); lineTo(4.5f, 13f); lineTo(4.5f, 11f)
        lineTo(2.2f, 9f); lineTo(4.2f, 5.5f); lineTo(7f, 6.5f); lineTo(9.5f, 5f); close()
        moveTo(16f, 12f); curveTo(16f, 17.3f, 8f, 17.3f, 8f, 12f)
        curveTo(8f, 6.7f, 16f, 6.7f, 16f, 12f); close()
    }
    val Highlighter = filledIcon("Highlighter", "M4.56681 3.09871C4.91328 2.93219 5.32452 2.979 5.6247 3.21914L10.4679 7.09367L17.2747 9.03849C17.4381 9.08517 17.5869 9.17273 17.7071 9.2929L20.7071 12.2929C21.0976 12.6834 21.0976 13.3166 20.7071 13.7071C20.3166 14.0976 19.6834 14.0976 19.2929 13.7071L17 11.4142L10.4142 18L12.7071 20.2929C13.0976 20.6834 13.0976 21.3166 12.7071 21.7071C12.3166 22.0977 11.6834 22.0977 11.2929 21.7071L8.29289 18.7071C8.17272 18.5869 8.08516 18.4381 8.03848 18.2747L6.10966 11.5239L4.2929 9.70713C4.10537 9.51959 4.00001 9.26524 4.00001 9.00002L4 4.00001C4 3.61561 4.22034 3.26523 4.56681 3.09871ZM8.12317 11.2911L9.49463 16.0912L15.0912 10.4946L10.291 9.12318L8.12317 11.2911ZM8.50287 8.08293L6 6.08064L6.00001 8.58581L7 9.5858L8.50287 8.08293Z")
    val Lasso = icon("Lasso") {
        moveTo(9f, 17f); curveTo(-3f, 11f, 5f, 1f, 15f, 4f)
        curveTo(27f, 8f, 20f, 20f, 9f, 17f); close()
        moveTo(9f, 17f); curveTo(5f, 17f, 5f, 23f, 12f, 22f)
    }
    val Pen = filledIcon("Pen", "M3.29185 3.294C3.58094 3.00403 4.0176 2.91959 4.39395 3.08088L10.6939 5.78088L18.3939 9.08088C18.5109 9.131 18.6172 9.20297 18.7071 9.29292L21.7071 12.2929C22.0977 12.6834 22.0977 13.3166 21.7071 13.7071C21.3166 14.0977 20.6834 14.0977 20.2929 13.7071L18.0063 11.4205L11.4839 18.0585L13.7089 20.2947C14.0985 20.6862 14.0969 21.3194 13.7054 21.7089C13.3139 22.0985 12.6807 22.0969 12.2911 21.7054L9.36877 18.7683C9.28036 18.6794 9.20944 18.5747 9.15971 18.4597L5.79793 10.6809L3.08209 4.39674C2.91965 4.02088 3.00276 3.58397 3.29185 3.294ZM10.406 16.302L16.2613 10.3428L10.5284 7.88587L7.90329 10.511L10.406 16.302ZM7.04978 8.53607L8.5485 7.03734L5.91414 5.90833L7.04978 8.53607Z")
    val Eraser = icon("Eraser") {
        moveTo(19f, 11.5f); lineTo(11.5f, 4f)
        quadTo(10f, 2.5f, 8.5f, 4f); lineTo(4f, 8.5f)
        quadTo(2.5f, 10f, 4f, 11.5f); lineTo(11.5f, 19f)
        moveTo(8.5f, 16f); lineTo(16f, 8.5f)
    }
    val Undo = icon("Undo") {
        moveTo(8f, 5f); lineTo(3f, 10f); lineTo(8f, 15f)
        moveTo(3f, 10f); lineTo(14f, 10f); curveTo(22f, 10f, 22f, 20f, 14f, 20f)
    }
    val Redo = icon("Redo") {
        moveTo(16f, 5f); lineTo(21f, 10f); lineTo(16f, 15f)
        moveTo(21f, 10f); lineTo(10f, 10f); curveTo(2f, 10f, 2f, 20f, 10f, 20f)
    }
    val Palette = icon("Palette") {
        moveTo(12f, 3f); curveTo(0f, 3f, 0f, 21f, 12f, 21f)
        curveTo(17f, 21f, 11f, 15f, 17f, 15f); curveTo(24f, 15f, 23f, 3f, 12f, 3f); close()
        moveTo(7f, 9f); lineTo(7.1f, 9f)
        moveTo(12f, 7f); lineTo(12.1f, 7f)
        moveTo(17f, 9f); lineTo(17.1f, 9f)
        moveTo(7f, 15f); lineTo(7.1f, 15f)
    }
    val Width = icon("Width") {
        moveTo(4f, 5f); lineTo(20f, 5f)
        moveTo(4f, 11f); lineTo(20f, 11f); moveTo(4f, 13f); lineTo(20f, 13f)
        moveTo(4f, 18f); lineTo(20f, 18f); moveTo(4f, 20f); lineTo(20f, 20f); moveTo(4f, 22f); lineTo(20f, 22f)
    }
    val Transparency = icon("Transparency") {
        moveTo(12f, 3f); curveTo(17f, 3f, 21f, 7f, 21f, 12f)
        curveTo(21f, 17f, 17f, 21f, 12f, 21f); curveTo(7f, 21f, 3f, 17f, 3f, 12f)
        curveTo(3f, 7f, 7f, 3f, 12f, 3f); close()
        moveTo(12f, 3f); lineTo(12f, 21f)
        moveTo(12f, 7f); lineTo(18f, 7f); moveTo(12f, 12f); lineTo(21f, 12f); moveTo(12f, 17f); lineTo(18f, 17f)
    }
    val Add = icon("Add") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
    val Close = icon("Close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val Save = icon("Save") {
        moveTo(4f, 3f); lineTo(17f, 3f); lineTo(21f, 7f); lineTo(21f, 21f); lineTo(4f, 21f); close()
        moveTo(8f, 3f); lineTo(8f, 9f); lineTo(16f, 9f); lineTo(16f, 3f)
        moveTo(8f, 21f); lineTo(8f, 14f); lineTo(17f, 14f); lineTo(17f, 21f)
    }
    val Delete = icon("Delete") {
        moveTo(4f, 6f); lineTo(20f, 6f); moveTo(9f, 3f); lineTo(15f, 3f)
        moveTo(6f, 6f); lineTo(7f, 21f); lineTo(17f, 21f); lineTo(18f, 6f)
        moveTo(10f, 10f); lineTo(10f, 17f); moveTo(14f, 10f); lineTo(14f, 17f)
    }
    val Collapse = icon("Collapse") { moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f) }
    val Expand = icon("Expand") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }
    val ChevronRight = icon("ChevronRight") { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
    val Check = icon("Check") { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(19f, 7f) }
    val Sort = icon("Sort") {
        moveTo(4f, 5f); lineTo(18f, 5f); moveTo(4f, 11f); lineTo(14f, 11f); moveTo(4f, 17f); lineTo(10f, 17f)
        moveTo(19f, 10f); lineTo(19f, 21f); moveTo(16f, 18f); lineTo(19f, 21f); lineTo(22f, 18f)
    }
    val SortAscending = icon("SortAscending") {
        moveTo(4f, 5f); lineTo(10f, 5f); moveTo(4f, 11f); lineTo(14f, 11f); moveTo(4f, 17f); lineTo(18f, 17f)
        moveTo(21f, 21f); lineTo(21f, 3f); moveTo(18f, 6f); lineTo(21f, 3f); lineTo(24f, 6f)
    }
    val ArrowUp = icon("ArrowUp") { moveTo(12f, 20f); lineTo(12f, 4f); moveTo(5f, 11f); lineTo(12f, 4f); lineTo(19f, 11f) }
    val ArrowDown = icon("ArrowDown") { moveTo(12f, 4f); lineTo(12f, 20f); moveTo(5f, 13f); lineTo(12f, 20f); lineTo(19f, 13f) }

    private fun icon(name: String, cap: StrokeCap = StrokeCap.Round,
        draw: PathBuilder.() -> Unit): ImageVector = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = cap, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
    }.build()

    // Preserve the supplied SVG outlines and their even-odd cutouts exactly.
    private fun filledIcon(name: String, data: String): ImageVector = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(pathData = PathParser().parsePathString(data).toNodes(),
        pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black)).build()
}
