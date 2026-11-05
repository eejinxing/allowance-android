package com.allowance.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Only the icons this app uses, drawn as native vectors. No full icon pack in the APK. */
object AppIcons {
    private fun icon(name: String, path: String, stroke: Float = 2f): ImageVector = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
        autoMirror = name == "ChevronLeft" || name == "ChevronRight",
    ).addPath(pathData = PathParser().parsePathString(path).toNodes(), fill = null,
        stroke = SolidColor(Color.Black), strokeLineWidth = stroke,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()

    val Add = icon("Add", "M12 5V19M5 12H19")
    /** Heavier plus for the primary add action, so it reads at a glance. */
    val AddBold = icon("AddBold", "M12 4.5V19.5M4.5 12H19.5", stroke = 2.8f)
    val TrendUp = icon("TrendUp", "M4 16L9.5 10.5L13 14L20 7M15 7H20V12", stroke = 2.4f)
    val Check = icon("Check", "M5 12L10 17L20 7")
    val Eye = icon("Eye", "M2 12Q6 5 12 5Q18 5 22 12Q18 19 12 19Q6 19 2 12ZM15 12A3 3 0 1 1 9 12A3 3 0 1 1 15 12", 1.7f)
    val EyeOff = icon("EyeOff", "M2 12Q6 5 12 5Q18 5 22 12Q18 19 12 19Q6 19 2 12ZM15 12A3 3 0 1 1 9 12A3 3 0 1 1 15 12M3 3L21 21", 1.7f)
    val Coin = icon("Coin", "M21 12A9 9 0 1 1 3 12A9 9 0 1 1 21 12M14.5 9.5Q14.5 8 12 8Q9.5 8 9.5 10Q9.5 12 12 12Q14.5 12 14.5 14Q14.5 16 12 16Q9.5 16 9.5 14.5M12 6.5V8M12 16V17.5", 2.2f)
    val ChevronLeft = icon("ChevronLeft", "M15 6L9 12L15 18")
    val ChevronRight = icon("ChevronRight", "M9 6L15 12L9 18")
    val WbSunny = icon("WbSunny", "M16 12A4 4 0 1 1 8 12A4 4 0 1 1 16 12M12 2V4M12 20V22M2 12H4M20 12H22M5 5L6.5 6.5M17.5 17.5L19 19M5 19L6.5 17.5M17.5 6.5L19 5")
    val ReceiptLong = icon("ReceiptLong", "M6 3L8 5L10 3L12 5L14 3L16 5L18 3V21L16 19L14 21L12 19L10 21L8 19L6 21ZM9 9H15M9 13H15M9 17H13")
    val BarChart = icon("BarChart", "M5 20V11M12 20V4M19 20V14")
    val AccountBalanceWallet = icon("AccountBalanceWallet", "M5 6H19Q21 6 21 8V19Q21 21 19 21H5Q3 21 3 19V8Q3 6 5 6ZM3 10H21M16 15H18M5 6V3H18V6")
    val Flag = icon("Flag", "M5 21V3M5 4H12L14 6H21V16H14L12 14H5")
    val AutoAwesome = icon("AutoAwesome", "M12 3L14 8L19 10L14 12L12 17L10 12L5 10L10 8ZM19 16L20 18L22 19L20 20L19 22L18 20L16 19L18 18Z")
    val Tune = icon("Tune", "M3 6H8M12 6H21M3 12H14M18 12H21M3 18H6M10 18H21M8 3V9M14 9V15M6 15V21")
    val Restaurant = icon("Restaurant", "M7 3V21M4.5 3V8Q4.5 10.5 7 10.5Q9.5 10.5 9.5 8V3M17 21V3Q13.5 3 13.5 9Q13.5 13 17 13")
    val DirectionsBus = icon("DirectionsBus", "M6 4H18Q20 4 20 7V17Q20 19 18 19H6Q4 19 4 17V7Q4 4 6 4ZM4 11H20M8 15H8.1M16 15H16.1M7 19V21M17 19V21")
    val ShoppingBag = icon("ShoppingBag", "M5.5 8H18.5L17.5 21H6.5ZM9 8V6A3 3 0 0 1 15 6V8")
    val Celebration = icon("Celebration", "M12 3L14.5 8L20 9L16 13L17 19L12 16L7 19L8 13L4 9L9.5 8Z")
    val Home = icon("Home", "M3.5 11.5L12 4L20.5 11.5M6 10V21H18V10M10 21V15H14V21")
    val Favorite = icon("Favorite", "M12 21S4 16 4 10A4.5 4.5 0 0 1 12 7A4.5 4.5 0 0 1 20 10C20 16 12 21 12 21Z")
    val MoreHoriz = icon("MoreHoriz", "M5 12H5.1M12 12H12.1M19 12H19.1")
    val Savings = icon("Savings", "M8 4H16M7 7H17L16 21H8ZM10 13H14M12 11V15")
    val SouthWest = icon("SouthWest", "M18 6L6 18M6 8V18H16")
    val SwapHoriz = icon("SwapHoriz", "M3 8H21M17 4L21 8L17 12M21 16H3M7 12L3 16L7 20")
    val CalendarMonth = icon("CalendarMonth", "M5 5H19Q21 5 21 7V20Q21 22 19 22H5Q3 22 3 20V7Q3 5 5 5ZM3 10H21M7 2V7M17 2V7M7 14H7.1M12 14H12.1M17 14H17.1M7 18H7.1M12 18H12.1")
    val Clock = icon("Clock", "M21 12A9 9 0 1 1 3 12A9 9 0 1 1 21 12M12 7V12L15.5 14")
    val Person = icon("Person", "M15.5 8A3.5 3.5 0 1 1 8.5 8A3.5 3.5 0 1 1 15.5 8M5 21Q5 15 12 15Q19 15 19 21")
    val Target = icon("Target", "M21 12A9 9 0 1 1 3 12A9 9 0 1 1 21 12M16 12A4 4 0 1 1 8 12A4 4 0 1 1 16 12M12 12H12.1")
    val ArrowUp = icon("ArrowUp", "M12 19V5M6 11L12 5L18 11")
    val Download = icon("Download", "M12 4V15M7 10L12 15L17 10M5 20H19")
    val Cookie = icon("Cookie", "M21 12A9 9 0 1 1 12 3Q12 7 16 7Q16 11 21 12M9 10H9.1M14 14H14.1M9 15H9.1")
    val Edit = icon("Edit", "M4 20H8L19 9L15 5L4 16ZM13 7L17 11")
    val ArrowRight = icon("ArrowRight", "M5 12H19M13 6L19 12L13 18")
    val Piggy = icon("Piggy", "M5 12.5C5 9.5 8 7.5 12 7.5C16 7.5 19 9.5 19 12.5C19 14.2 18.1 15.6 16.7 16.5V19H14V17.8C13.4 17.9 12.7 18 12 18C11.3 18 10.6 17.9 10 17.8V19H7.3V16.5C5.9 15.6 5 14.2 5 12.5ZM8.5 8.3L8 5.8L10.6 7.7M11 4.5H14M15.4 11.6H15.5M19 12H21")
    val ChevronDown = icon("ChevronDown", "M6 9L12 15L18 9")
    val ArrowUpRight = icon("ArrowUpRight", "M7 17L17 7M8 7H17V16")
    val Plane = icon("Plane", "M21 16V14L13 9V4.5C13 3.7 12.3 3 11.5 3C10.7 3 10 3.7 10 4.5V9L2 14V16L10 13.5V18L8 19.5V21L11.5 20L15 21V19.5L13 18V13.5Z")
    val Bicycle = icon("Bicycle", "M9 17A3.5 3.5 0 1 1 2 17A3.5 3.5 0 1 1 9 17M22 17A3.5 3.5 0 1 1 15 17A3.5 3.5 0 1 1 22 17M5.5 17L9 9H14L18.5 17M9 9L12 17M14 9L13 6H11")
    val Car = icon("Car", "M5 16V11L7 6H17L19 11V16M3 16H21V19H3ZM7 13H7.1M17 13H17.1M5 11H19")
    val Laptop = icon("Laptop", "M5 17V7C5 6.4 5.4 6 6 6H18C18.6 6 19 6.4 19 7V17M3 17H21L20 19H4Z")
    val Bread = icon("Bread", "M5 10Q3 10 3 8Q3 5 7 5H17Q21 5 21 8Q21 10 19 10V18Q19 19 18 19H6Q5 19 5 18ZM9 13V16M12 13V16M15 13V16")
    val Train = icon("Train", "M7 3H17Q19 3 19 5V15Q19 18 16 18H8Q5 18 5 15V5Q5 3 7 3ZM5 11H19M9 14.5H9.1M15 14.5H15.1M8 21L10 18M16 21L14 18")
    val Bottle = icon("Bottle", "M10 3H14V6L15.5 8V20Q15.5 21 14.5 21H9.5Q8.5 21 8.5 20V8L10 6ZM8.5 12H15.5")
    val Cup = icon("Cup", "M5 8H16V15Q16 19 12 19H9Q5 19 5 15ZM16 10H18Q20 10 20 12Q20 14 18 14H16M8 3V5M12 3V5")
    val Lunchbox = icon("Lunchbox", "M4 9H20V18Q20 19 19 19H5Q4 19 4 18ZM8 9V7Q8 5 10 5H14Q16 5 16 7V9M4 13H20M12 11V15")
    val Settings = icon("Settings", gearPath())
    val People = icon("People", "M12.5 7.5A3.5 3.5 0 1 1 5.5 7.5A3.5 3.5 0 1 1 12.5 7.5M2.5 20Q2.5 14.5 9 14.5Q15.5 14.5 15.5 20M15.5 4.3A3.5 3.5 0 0 1 15.5 10.7M18 14.8Q21.5 15.8 21.5 20")
    val Camera = icon("Camera", "M4 8H7.5L9 5.5H15L16.5 8H20V19H4ZM15.5 13A3.5 3.5 0 1 1 8.5 13A3.5 3.5 0 1 1 15.5 13")
    val Waveform = icon("Waveform", "M4 10V14M8 7V17M12 4V20M16 7V17M20 10V14")
    val ChatHistory = icon("ChatHistory", "M21 12A9 9 0 0 1 7.5 19.8L3 21L4.3 16.6A9 9 0 1 1 21 12ZM12 7.5V12L15 13.8")
}

/** Eight-tooth gear outline plus a centre hole, in the 24-unit icon grid. */
private fun gearPath(): String = buildString {
    val teeth = 8
    val step = 2 * Math.PI / teeth
    for (i in 0 until teeth) {
        val base = i * step
        listOf(-.5 to 7.6, -.27 to 10.2, .27 to 10.2, .5 to 7.6).forEachIndexed { k, (offset, radius) ->
            val angle = base + offset * step
            append(if (i == 0 && k == 0) "M" else "L")
            append(String.format(java.util.Locale.US, "%.2f %.2f", 12 + radius * Math.cos(angle), 12 + radius * Math.sin(angle)))
        }
    }
    append("ZM15 12A3 3 0 1 1 9 12A3 3 0 1 1 15 12")
}
