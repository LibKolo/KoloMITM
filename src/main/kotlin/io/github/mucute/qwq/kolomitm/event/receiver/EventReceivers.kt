package io.github.mucute.qwq.kolomitm.event.receiver

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.google.gson.JsonParser
import io.github.mucute.qwq.kolomitm.definition.CameraPresetDefinition
import io.github.mucute.qwq.kolomitm.definition.DataEntry
import io.github.mucute.qwq.kolomitm.definition.Definitions
import io.github.mucute.qwq.kolomitm.definition.NbtBlockDefinitionRegistry
import io.github.mucute.qwq.kolomitm.jackson.ColorDeserializer
import io.github.mucute.qwq.kolomitm.jackson.ColorSerializer
import io.github.mucute.qwq.kolomitm.jackson.NbtDefinitionSerializer
import io.github.mucute.qwq.kolomitm.session.EventUnregister
import io.github.mucute.qwq.kolomitm.session.KoloSession
import io.github.mucute.qwq.kolomitm.util.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kyori.adventure.text.Component
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.protocol.adventure.AdventureTextConverter
import org.cloudburstmc.protocol.adventure.BedrockComponent
import org.cloudburstmc.protocol.bedrock.codec.v729.serializer.InventoryContentSerializer_v729
import org.cloudburstmc.protocol.bedrock.codec.v729.serializer.InventorySlotSerializer_v729
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload
import org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition
import org.cloudburstmc.protocol.bedrock.packet.*
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils
import org.cloudburstmc.protocol.bedrock.util.JsonUtils
import org.cloudburstmc.protocol.common.NamedDefinition
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry
import org.jose4j.json.JsonUtil
import org.jose4j.json.internal.json_simple.JSONArray
import org.jose4j.json.internal.json_simple.JSONObject
import org.jose4j.jws.JsonWebSignature
import org.jose4j.jwx.HeaderParameterNames
import java.awt.Color
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPair
import java.util.*
import kotlin.io.encoding.Base64

private val UnlimitedEncodingSettings = EncodingSettings.UNLIMITED

private val AdventureTextConverter = AdventureTextConverter()

private val JsonMapper = ObjectMapper()
    .registerModule(
        SimpleModule("KoloMITM")
            .addSerializer(Color::class.java, ColorSerializer())
            .addDeserializer(Color::class.java, ColorDeserializer())
            .addSerializer(NbtBlockDefinitionRegistry.NbtBlockDefinition::class.java, NbtDefinitionSerializer())
    )
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

fun KoloSession.proxyPassReceiver(
    autoCodec: Boolean = true,
    patchCodec: Boolean = true
): EventUnregister {
    val keyPair = EncryptionUtils.createKeyPair()
    var loginPacket: LoginPacket? = null

    return packet<BedrockPacket> { packetEvent, _ ->
        val packet = packetEvent.packet
        if (packet is RequestNetworkSettingsPacket) {
            packetEvent.consume()

            val protocolVersion = packet.protocolVersion
            val targetCodec = if (autoCodec) {
                BedrockCodecs.bedrockCodecMap[protocolVersion] ?: koloMITM.codec
            } else {
                koloMITM.codec
            }

            koloMITM.codecHelper = targetCodec.createHelper().apply {
                itemDefinitions = Definitions.itemDefinitions
                blockDefinitions = Definitions.blockDefinition
                cameraPresetDefinitions = Definitions.cameraDefinitions
                encodingSettings = UnlimitedEncodingSettings
                textConverter = AdventureTextConverter
            }

            if (protocolVersion != targetCodec.protocolVersion) {
                val status = PlayStatusPacket()
                status.status = if (protocolVersion > targetCodec.protocolVersion) {
                    PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD
                } else {
                    PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD
                }

                inboundImmediately(status)
                return@packet
            }

            koloMITM.codec = targetCodec.toBuilder()
                .helper { koloMITM.codecHelper }
                .also {
                    if (patchCodec && protocolVersion > 729) {
                        it.updateSerializer(
                            InventoryContentPacket::class.java,
                            InventoryContentSerializer_v729.INSTANCE
                        )
                        it.updateSerializer(
                            InventorySlotPacket::class.java,
                            InventorySlotSerializer_v729.INSTANCE
                        )
                    }
                }
                .build()

            val networkSettingsPacket = NetworkSettingsPacket()
            networkSettingsPacket.compressionThreshold = 0
            networkSettingsPacket.compressionAlgorithm = PacketCompressionAlgorithm.ZLIB

            inboundImmediately(networkSettingsPacket)
            inboundSession?.setCompression(PacketCompressionAlgorithm.ZLIB)
        }
        if (packet is LoginPacket) {
            packetEvent.consume()

            runCatching {

                // TODO: Support new TokenPayload

            }.exceptionOrNull()?.let {
                it.printStackTrace()
                inbound(DisconnectPacket().apply {
                    setKickMessage(BedrockComponent.of(Component.text(it.message.toString())))
                })
            }
        }
        if (packet is NetworkSettingsPacket) {
            packetEvent.consume()

            outboundSession?.setCompression(packet.compressionAlgorithm)
            println("Compression threshold set to ${packet.compressionThreshold}")

            loginPacket?.let { outboundImmediately(it) }
        }
        if (packet is ServerToClientHandshakePacket) {
            packetEvent.consume()

            val jws = JsonWebSignature()
            jws.compactSerialization = packet.jwt
            val saltJwt = JSONObject(JsonUtil.parseJson(jws.unverifiedPayload))
            val x5u = jws.getHeader(HeaderParameterNames.X509_URL)
            val serverKey = EncryptionUtils.parseKey(x5u)
            val key = EncryptionUtils.getSecretKey(
                koloMITM.account!!.mcChain.privateKey, serverKey,
                Base64.decode(
                    JsonUtils.childAsType(
                        saltJwt, "salt",
                        String::class.java
                    )
                )
            )
            outboundSession?.enableEncryption(key)
            outboundImmediately(ClientToServerHandshakePacket())
        }

    }
}

@Suppress("DEPRECATION")
fun KoloSession.definitionReceiver(): EventUnregister {
    return packet<BedrockPacket> { packetEvent, _ ->
        val packet = packetEvent.packet

        if (packet is StartGamePacket) {
            if (koloMITM.codec.protocolVersion < 776) {
                val itemData: MutableList<DataEntry> = ArrayList<DataEntry>()

                val legacyItems = LinkedHashMap<String, Int>()
                val legacyBlocks = LinkedHashMap<String, Int>()

                for (entry in packet.itemDefinitions) {
                    if (entry.runtimeId > 255) {
                        legacyItems.putIfAbsent(entry.identifier, entry.runtimeId)
                    } else {
                        var id = entry.identifier
                        if (id.contains(":item.")) {
                            id = id.replace(":item.", ":")
                        }
                        if (entry.runtimeId > 0) {
                            legacyBlocks.putIfAbsent(id, entry.runtimeId)
                        } else {
                            legacyBlocks.putIfAbsent(id, 255 - entry.runtimeId)
                        }
                    }

                    itemData.add(DataEntry(entry.identifier, entry.runtimeId, -1, false))
                    Definitions.legacyIdMap[entry.runtimeId] = entry.identifier
                }

                Definitions.itemDefinitions = SimpleDefinitionRegistry.builder<ItemDefinition>()
                    .addAll(packet.itemDefinitions)
                    .add(SimpleItemDefinition("minecraft:empty", 0, false))
                    .build()

                koloMITM.codecHelper?.itemDefinitions = Definitions.itemDefinitions
                outboundSession?.peer?.codecHelper?.itemDefinitions = Definitions.itemDefinitions
                inboundSession?.peer?.codecHelper?.itemDefinitions = Definitions.itemDefinitions
            }

            val blockDefinitions = if (packet.isBlockNetworkIdsHashed) {
                Definitions.blockDefinitionHashed
            } else {
                Definitions.blockDefinition
            }

            koloMITM.codecHelper?.blockDefinitions = blockDefinitions
            outboundSession?.peer?.codecHelper?.blockDefinitions = blockDefinitions
            inboundSession?.peer?.codecHelper?.blockDefinitions = blockDefinitions
        }

        if (packet is ItemComponentPacket) {
            val itemData = ArrayList<DataEntry>()

            val root = NbtMap.builder()
            for (item in packet.items) {
                root.putCompound(item.identifier, item.componentData)
                itemData.add(
                    DataEntry(
                        item.identifier,
                        item.runtimeId,
                        item.version.ordinal,
                        item.isComponentBased
                    )
                )
            }

            if (koloMITM.codec.protocolVersion >= 776) {
                val builder = SimpleDefinitionRegistry.builder<ItemDefinition>()
                    .add(SimpleItemDefinition("minecraft:empty", 0, false))

                for (entry in itemData) {
                    Definitions.legacyIdMap[entry.id] = entry.name
                    builder.add(SimpleItemDefinition(entry.name, entry.id, false))
                }

                Definitions.itemDefinitions = builder.build()

                koloMITM.codecHelper?.itemDefinitions = Definitions.itemDefinitions
                outboundSession?.peer?.codecHelper?.itemDefinitions = Definitions.itemDefinitions
                inboundSession?.peer?.codecHelper?.itemDefinitions = Definitions.itemDefinitions
            }

        }

        if (packet is CameraPresetsPacket) {
            Definitions.cameraDefinitions =
                SimpleDefinitionRegistry.builder<NamedDefinition>()
                    .addAll(List(packet.presets.size) {
                        CameraPresetDefinition.fromCameraPreset(packet.presets[it], it)
                    })
                    .build()



            koloMITM.codecHelper?.cameraPresetDefinitions = Definitions.cameraDefinitions
            outboundSession?.peer?.codecHelper?.cameraPresetDefinitions = Definitions.cameraDefinitions
            inboundSession?.peer?.codecHelper?.cameraPresetDefinitions = Definitions.cameraDefinitions
        }
    }
}

fun KoloSession.transferReceiver(): EventUnregister {
    return packet<TransferPacket> { packetEvent, _ ->
        packetEvent.consume()

        val packet = packetEvent.packet

        koloMITM.remoteAddress = InetSocketAddress(packet.address, packet.port)

        // Redirect to itself
        inboundImmediately(TransferPacket().apply {
            address = InetAddress.getLocalHost().hostAddress
            port = koloMITM.localAddress.port
        })
    }
}

fun KoloSession.transferCommandReceiver(): Pair<EventUnregister, EventUnregister> {
    return command(
        "transfer",
        "Transfer to another server",
        handler = {
            when (it.size) {
                1 -> {
                    val remoteAddress = InetSocketAddress(it[0], 19132)
                    koloMITM.remoteAddress = remoteAddress

                    // Redirect to itself
                    inboundImmediately(TransferPacket().apply {
                        this.address = InetAddress.getLocalHost().hostAddress
                        this.port = koloMITM.localAddress.port
                    })
                }

                2 -> {
                    val remoteAddress = InetSocketAddress(it[0], it[1].toInt())
                    koloMITM.remoteAddress = remoteAddress

                    // Redirect to itself
                    inboundImmediately(TransferPacket().apply {
                        this.address = InetAddress.getLocalHost().hostAddress
                        this.port = koloMITM.localAddress.port
                    })
                }

                else -> mismatch()
            }
        },
        arrayOf("[ip: string]", "[port: int]"),
        arrayOf("[ip: string]")
    )
}

fun KoloSession.echoCommandReceiver(): Pair<EventUnregister, EventUnregister> {
    return command(
        "echo",
        "Print a raw text in console",
        handler = {
            when (it.size) {
                0 -> mismatch()
                else -> {
                    inbound(TextPacket().apply {
                        type = TextPacket.Type.RAW
                        sourceName = ""
                        setMessage(BedrockComponent.of(Component.text(it.joinToString(separator = " "))))
                        xuid = ""
                    })
                }
            }
        },
        arrayOf("[messages: any]...")
    )
}

fun KoloSession.autoLoginWaiGameReceiver(password: String): EventUnregister {
    return packet<BedrockPacket> { packetEvent, _ ->
        val packet = packetEvent.packet

        if (packet is ModalFormRequestPacket) {
            val jsonObject = JsonParser.parseString(packet.formData).asJsonObject
            if (jsonObject["title"]?.isJsonPrimitive == true) {
                val title = jsonObject["title"].asJsonPrimitive
                if (title.isString && title.asString == "登录") {
                    packetEvent.consume()

                    val modalFormResponsePacket = ModalFormResponsePacket().apply {
                        formId = packet.formId
                        formData = JSONArray(listOf(password)).toJSONString()
                        cancelReason = Optional.empty()
                    }
                    outbound(modalFormResponsePacket)
                }
            }
        }
    }
}

fun KoloSession.packDownloaderReceiver(
    packDownloader: PackDownloader
): EventUnregister {
    return packet<BedrockPacket> { packetEvent, _ ->
        val packet = packetEvent.packet
        if (packet is ResourcePacksInfoPacket) {
            for (entry in packet.resourcePackInfos) {
                packDownloader.registerPack(entry.packId, entry.cdnUrl, entry.contentKey)
            }
        }
        if (packet is ResourcePackChunkDataPacket) {
            packDownloader.addChunk(packet.packId, packet.chunkIndex, packet.data.retain())
        }
        if (packet is ResourcePackClientResponsePacket) {
            if (packet.status == ResourcePackClientResponsePacket.Status.COMPLETED) {
                packDownloader.processPacks()
            }
        }
    }
}