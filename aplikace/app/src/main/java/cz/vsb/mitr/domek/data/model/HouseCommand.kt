package cz.vsb.mitr.domek.data.model

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class LightAction(val json: String, val label: String) {
    ON("zapnout", "zapnout světlo"),
    OFF("vypnout", "vypnout světlo"),
    TOGGLE("prepnout", "přepnout světlo"),
    AUTO("auto", "vrátit světlo do automatiky"),
}

sealed interface HouseCommand {
    data class Light(val action: LightAction) : HouseCommand

    data class LightLook(val color: Rgb? = null, val brightness: Int? = null) : HouseCommand {
        init {
            require(color != null || brightness != null) { "LightLook potřebuje barvu nebo jas" }
        }
    }

    data class SetClock(val time: LocalDateTime) : HouseCommand
}

fun HouseCommand.toJsonObject(): JsonObject = when (this) {
    is HouseCommand.Light -> buildJsonObject { put("svetlo", action.json) }
    is HouseCommand.LightLook -> buildJsonObject {
        put("svetlo", buildJsonObject {
            color?.let { rgb -> put("barva", buildJsonArray { add(rgb.r); add(rgb.g); add(rgb.b) }) }
            brightness?.let { put("jas", it) }
        })
    }
    is HouseCommand.SetClock -> buildJsonObject { put("cas", formatPiTime(time)) }
}

fun HouseCommand.label(): String = when (this) {
    is HouseCommand.Light -> action.label
    is HouseCommand.LightLook -> "nastavit barvu a jas"
    is HouseCommand.SetClock -> "nastavit čas"
}

// STRICT, aby se nesmyslné datum (30. 2.) odmítlo, a ne tiše posunulo na jiný den.
private val piTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)

fun formatPiTime(time: LocalDateTime): String = piTimeFormat.format(time)

fun parsePiTime(text: String): LocalDateTime? =
    try {
        LocalDateTime.parse(text, piTimeFormat)
    } catch (e: DateTimeParseException) {
        null
    }
