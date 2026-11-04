package wd.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Простейшее оформление для MVP. На этапе 7 заменится пиксельным стилем.

object Palette {
    val bg = Color(0xFF212135)
    val panel = Color(0xFF1A1A2E)
    val card = Color(0xFF1E1E35)
    val border = Color(0xFF2A2A4A)
    val text = Color(0xFFDCE1F0)
    val dim = Color(0xFF5A6080)
    val accent = Color(0xFFE94560)
    val blue = Color(0xFF4FC3F7)
    val green = Color(0xFF4CAF50)
    val yellow = Color(0xFFFFD54F)
    val red = Color(0xFFEF5350)
    val dead = Color(0xFF444444)
    val button = Color(0xFF2A2A4A)
}

private val font = FontFamily.Monospace

@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.text,
    size: TextUnit = 14.sp,
    bold: Boolean = false,
    strike: Boolean = false,
) {
    BasicText(
        text,
        modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontFamily = font,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            textDecoration = if (strike) TextDecoration.LineThrough else null,
        ),
    )
}

@Composable
fun Btn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Palette.button,
    textColor: Color = Palette.text,
    size: TextUnit = 14.sp,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg = when {
        !enabled -> Palette.panel
        hovered -> color.copy(alpha = 0.75f)
        else -> color
    }
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(1.dp, if (enabled) Palette.border else Palette.panel, RoundedCornerShape(4.dp))
            .hoverable(source, enabled)
            .clickable(source, indication = null, enabled = enabled, onClick = onClick)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, color = if (enabled) textColor else Palette.dim, size = size, bold = true)
    }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    numeric: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = TextStyle(color = Palette.text, fontSize = 14.sp, fontFamily = font),
        cursorBrush = SolidColor(Palette.text),
        modifier = modifier
            .background(Palette.panel, RoundedCornerShape(4.dp))
            .border(1.dp, Palette.border, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .background(Palette.card, RoundedCornerShape(8.dp))
            .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
            .padding(10.dp),
        content = content,
    )
}

/** Полоска прогресса 0..1. */
@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(12.dp).background(Palette.panel, RoundedCornerShape(3.dp))) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(12.dp)
                .background(color, RoundedCornerShape(3.dp)),
        )
    }
}

fun ecologyColor(value: Int): Color = when {
    value > 60 -> Palette.green
    value > 30 -> Palette.yellow
    else -> Palette.red
}
