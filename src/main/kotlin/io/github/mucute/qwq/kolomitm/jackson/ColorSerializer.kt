package io.github.mucute.qwq.kolomitm.jackson

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import java.awt.Color
import java.io.IOException

class ColorSerializer : StdSerializer<Color?>(Color::class.java) {
    @Throws(IOException::class)
    override fun serialize(color: Color?, jsonGenerator: JsonGenerator, serializerProvider: SerializerProvider?) {
        if (color == null) {
            jsonGenerator.writeNull()
        } else {
            jsonGenerator.writeStartObject()
            jsonGenerator.writeNumberField("a", color.getAlpha())
            jsonGenerator.writeNumberField("r", color.getRed())
            jsonGenerator.writeNumberField("g", color.getGreen())
            jsonGenerator.writeNumberField("b", color.getBlue())
            jsonGenerator.writeEndObject()
        }
    }
}