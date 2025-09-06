package io.github.mucute.qwq.kolomitm.jackson

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import org.cloudburstmc.protocol.common.util.Color
import java.io.IOException


class ColorDeserializer : StdDeserializer<Color?>(Color::class.java) {

    @Throws(IOException::class, JacksonException::class)
    override fun deserialize(jsonParser: JsonParser, deserializationContext: DeserializationContext): Color? {
        if (jsonParser.currentToken.isStructStart) {
            var alpha = 255
            var red = 0
            var green = 0
            var blue = 0

            while (jsonParser.nextToken() != null) {
                val fieldName: String = jsonParser.currentName
                jsonParser.nextToken()
                when (fieldName) {
                    "a" -> alpha = jsonParser.intValue
                    "r" -> red = jsonParser.intValue
                    "g" -> green = jsonParser.intValue
                    "b" -> blue = jsonParser.intValue
                }
            }
            return Color(red, green, blue, alpha)
        } else if (jsonParser.currentToken === JsonToken.VALUE_NULL) {
            return null
        } else {
            throw deserializationContext.wrongTokenException(
                jsonParser,
                Color::class.java,
                JsonToken.START_OBJECT,
                "Expected a color object"
            )
        }
    }

}