package io.github.mucute.qwq.kolomitm.util

import kotlinx.serialization.json.*
import org.jose4j.jws.EcdsaUsingShaAlgorithm
import java.security.KeyPair
import java.security.Signature
import kotlin.io.encoding.Base64

fun decodeJWT(jwt: String): JsonObject {
    val parts = jwt.split(".")
    if (parts.size != 3) {
        throw IllegalArgumentException("Invalid JWT")
    }

    return Json.parseToJsonElement(
        Base64.UrlSafe.decode(parts[1]).decodeToString()
    ).jsonObject
}

fun encodeJWT(decodedPayload: JsonObject, keyPair: KeyPair): String {
    val headerJsonObject = buildJsonObject {
        put("alg", "ES384")
        put("x5u", Base64.withPadding(Base64.PaddingOption.ABSENT).encode(keyPair.public.encoded))
    }
    val header =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(headerJsonObject.toString().encodeToByteArray())
    val payload =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(decodedPayload.toString().encodeToByteArray())
    val signature =
        Signature.getInstance("SHA384withECDSA").let {
            it.initSign(keyPair.private)
            it.update("${header}.${payload}".encodeToByteArray())

            val signedBytes = EcdsaUsingShaAlgorithm.convertDerToConcatenated(it.sign(), 48)
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(signedBytes)
        }

    return "${header}.${payload}.${signature}"
}