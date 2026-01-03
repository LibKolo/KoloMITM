package io.github.mucute.qwq.kolomitm.util

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.github.mucute.qwq.kolomitm.event.KoloEvent
import io.github.mucute.qwq.kolomitm.event.PacketEvent
import io.github.mucute.qwq.kolomitm.event.handler.EventHandler
import io.github.mucute.qwq.kolomitm.session.EventReceiver
import io.github.mucute.qwq.kolomitm.session.EventUnregister
import io.github.mucute.qwq.kolomitm.session.KoloSession
import net.kyori.adventure.text.Component
import net.raphimc.minecraftauth.MinecraftAuth
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager
import net.raphimc.minecraftauth.java.JavaAuthManager
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService
import org.cloudburstmc.protocol.adventure.BedrockComponent
import org.cloudburstmc.protocol.bedrock.data.command.CommandData
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import java.io.File
import java.nio.file.Paths
import java.util.function.Consumer

fun KoloSession.on(eventReceiver: EventReceiver): EventUnregister {
    eventReceivers.add(eventReceiver)
    return {
        eventReceivers.remove(eventReceiver)
    }
}

inline fun <reified T : KoloEvent> KoloSession.event(
    crossinline eventReceiver: KoloSession.(T, EventUnregister) -> Unit
): EventUnregister {
    return on { event, eventUnregister ->
        if (event is T) {
            eventReceiver(event, eventUnregister)
        }
    }
}

@Suppress("UNCHECKED_CAST")
inline fun <reified T : BedrockPacket> KoloSession.packet(
    crossinline eventReceiver: KoloSession.(PacketEvent<T>, EventUnregister) -> Unit
): EventUnregister {
    return on { event, eventUnregister ->
        if (event is PacketEvent<*> && event.packet is T) {
            eventReceiver(event as PacketEvent<T>, eventUnregister)
        }
    }
}

private val gson = GsonBuilder()
    .setPrettyPrinting()
    .create()

fun saveAccount(
    bedrockAuthManager: BedrockAuthManager,
    file: File? = Paths.get(".").resolve("bedrockSession.json").toFile()
) {
    if (file != null && !file.isDirectory) {
        val json = gson.toJson(BedrockAuthManager.toJson(bedrockAuthManager))
        file.writeText(json)
    }
}

fun fetchAccount(
    gameVersion: String,
    cache: Boolean = true,
    file: File? = Paths.get(".").resolve("bedrockSession.json").toFile(),
    msaDeviceCodeCallback: Consumer<MsaDeviceCode> = {
        println("Go to ${it.directVerificationUri}")
    }
): BedrockAuthManager {
    if (cache && file != null && file.exists()) {
        val json = JsonParser.parseString(file.readText()).asJsonObject
        return BedrockAuthManager.fromJson(MinecraftAuth.createHttpClient(), gameVersion, json).apply {
            minecraftSession.refresh()
            minecraftCertificateChain.refresh()
            minecraftMultiplayerToken.refresh()
        }
    }

    val bedrockAuthManagerBuilder = BedrockAuthManager.create(MinecraftAuth.createHttpClient(), gameVersion)
    val bedrockAuthManager = bedrockAuthManagerBuilder
        .login(::DeviceCodeMsaAuthService, msaDeviceCodeCallback)
        .apply {
            minecraftSession.refresh()
            minecraftCertificateChain.refresh()
            minecraftMultiplayerToken.refresh()
        }

    if (cache) {
        saveAccount(bedrockAuthManager, file)
    }

    println("Username: ${bedrockAuthManager.minecraftCertificateChain.cached.identityDisplayName}")
    println("MSA Access Token: ${bedrockAuthManager.msaToken.cached.accessToken}")
    return bedrockAuthManager
}

inline fun KoloSession.command(
    commandName: String,
    commandDescription: String = commandName,
    crossinline handler: KoloSession.(List<String>) -> Unit,
    vararg helps: Array<String>
): Pair<EventUnregister, EventUnregister> {
    return packet<AvailableCommandsPacket> { packetEvent, _ ->
        val packet = packetEvent.packet
        packet.commands.add(
            CommandData(
                commandName,
                commandDescription,
                emptySet(),
                CommandPermission.ANY,
                null,
                emptyList(),
                arrayOf(),
            )
        )
    } to packet<CommandRequestPacket> { packetEvent, _ ->
        val packet = packetEvent.packet
        val command = packet.command
        val arguments = "\\s+".toRegex().split(command.trim())

        if (arguments[0] == "/$commandName") {
            packetEvent.consume()
            val subList = arguments.subList(1, arguments.size)
            runCatching {
                handler(subList)
            }.exceptionOrNull()?.let { exception ->
                inbound(TextPacket().apply {
                    type = TextPacket.Type.RAW
                    sourceName = ""
                    setMessage(BedrockComponent.of(Component.text(
                        """
                        |§cParse or execute command failed: 
                        |Input: /${commandName}${subList.joinToString(prefix = " ", separator = " ")}
                        |Helps: ${helps.joinToString(separator = "\n") { "/" + commandName + " " + it.joinToString(" ") }}
                        |Error: ${exception.message}
                    """.trimMargin()
                    )))
                    xuid = ""
                })
            }
        }
    }
}

inline fun EventHandler.command(
    commandName: String,
    commandDescription: String = commandName,
    crossinline handler: KoloSession.(List<String>) -> Unit,
    vararg helps: Array<String>,
): Pair<EventUnregister, EventUnregister> {
    return koloSession.command(commandName, commandDescription, handler, *helps)
}

fun mismatch(): Nothing = error("Mismatched argument(s)")