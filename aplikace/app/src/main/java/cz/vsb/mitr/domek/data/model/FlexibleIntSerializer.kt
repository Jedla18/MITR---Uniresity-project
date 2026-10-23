package cz.vsb.mitr.domek.data.model

import kotlin.math.roundToInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive

// Raspberry je v Pythonu a celé číslo může poslat jako 50.0; obyčejný Int by kvůli tomu odmítl celý Stav.
object FlexibleIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int =
        if (decoder is JsonDecoder) {
            decoder.decodeJsonElement().jsonPrimitive.double.roundToInt()
        } else {
            decoder.decodeInt()
        }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}
