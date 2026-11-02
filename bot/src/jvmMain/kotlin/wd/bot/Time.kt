package wd.bot

import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val format = DateTimeFormatter.ofPattern("HH:mm:ss")

actual fun timestamp(): String = LocalTime.now().format(format)
