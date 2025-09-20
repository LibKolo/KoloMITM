package io.github.mucute.qwq.kolomitm.session

import io.github.mucute.qwq.kolomitm.KoloMITM
import io.github.mucute.qwq.kolomitm.event.DisconnectEvent
import io.github.mucute.qwq.kolomitm.event.KoloEvent
import io.github.mucute.qwq.kolomitm.event.PacketEvent
import io.github.mucute.qwq.kolomitm.packet.PacketDirection
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.*
import net.kyori.adventure.text.Component
import org.cloudburstmc.protocol.bedrock.BedrockClientSession
import org.cloudburstmc.protocol.bedrock.BedrockPeer
import org.cloudburstmc.protocol.bedrock.BedrockServerSession
import org.cloudburstmc.protocol.bedrock.BedrockSession
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler
import java.util.*
import java.util.Queue
import java.util.concurrent.CopyOnWriteArrayList

typealias EventUnregister = () -> Unit

typealias EventReceiver = KoloSession.(KoloEvent, EventUnregister) -> Unit

@Suppress("MemberVisibilityCanBePrivate")
class KoloSession(
    val koloMITM: KoloMITM
) {

    var inboundSession: InboundSession? = null
        private set(value) {
            field = value

            value?.sendCachedPackets(inboundPacketQueue)
        }

    var outboundSession: OutboundSession? = null
        private set(value) {
            field = value

            value?.sendCachedPackets(outboundPacketQueue)
        }

    val eventReceivers = CopyOnWriteArrayList<EventReceiver>()

    private val inboundPacketQueue: Queue<Pair<BedrockPacket, Boolean>> = ArrayDeque()

    private val outboundPacketQueue: Queue<Pair<BedrockPacket, Boolean>> = ArrayDeque()

    inner class InboundSession(peer: BedrockPeer?, subClientId: Int) : BedrockServerSession(peer, subClientId) {

        internal val inboundScope = createCoroutineScope("InboundCoroutine")

        init {
            inboundSession = this
            codec = koloMITM.codec
            packetHandler = object : BedrockPacketHandler {

                override fun onDisconnect(reason: Component) {
                    println("InboundSession Disconnected: $reason")
                    dispatchEvent(DisconnectEvent(reason), isConsumable = false)
                    release()
                    if (inboundScope.isActive) {
                        inboundScope.cancel()
                    }
                    runCatching {
                        outboundSession?.disconnect()
                    }
                }

            }
        }

        override fun onPacket(wrapper: BedrockPacketWrapper) {
            val packet = ReferenceCountUtil.retain(wrapper.packet)
            handlePacket(packet)
        }

        private fun handlePacket(packet: BedrockPacket) {
            val packetEvent = PacketEvent(packet, PacketDirection.Outbound)

            if (!dispatchEvent(packetEvent)) {
                outbound(packet)
            }
        }

    }

    inner class OutboundSession(peer: BedrockPeer?, subClientId: Int) : BedrockClientSession(peer, subClientId) {

        internal val outboundScope = createCoroutineScope("OutboundCoroutine")

        init {
            outboundSession = this
            codec = koloMITM.codec
            packetHandler = object : BedrockPacketHandler {

                override fun onDisconnect(reason: Component) {
                    println("OutboundSession Disconnected: $reason")
                    dispatchEvent(DisconnectEvent(reason), isConsumable = false)
                    release()
                    if (outboundScope.isActive) {
                        outboundScope.cancel()
                    }
                    runCatching {
                        inboundSession?.disconnect()
                    }
                }

            }
        }

        override fun onPacket(wrapper: BedrockPacketWrapper) {
            val packet = ReferenceCountUtil.retain(wrapper.packet)
            handlePacket(packet)
        }

        private fun handlePacket(packet: BedrockPacket) {
            val packetEvent = PacketEvent(packet, PacketDirection.Inbound)

            if (!dispatchEvent(packetEvent)) {
                inbound(packet)
            }
        }

    }

    fun inbound(packet: BedrockPacket) {
        inboundSession?.sendPacket(packet) ?: inboundPacketQueue.add(packet to false)
    }

    fun inboundImmediately(packet: BedrockPacket) {
        inboundSession?.sendPacketImmediately(packet) ?: inboundPacketQueue.add(packet to false)
    }

    fun outbound(packet: BedrockPacket) {
        outboundSession?.sendPacket(packet) ?: outboundPacketQueue.add(packet to false)
    }

    fun outboundImmediately(packet: BedrockPacket) {
        outboundSession?.sendPacketImmediately(packet) ?: outboundPacketQueue.add(packet to true)
    }

    private fun release() {
        inboundPacketQueue.clear()
        outboundPacketQueue.clear()
    }

    private fun BedrockSession.sendCachedPackets(queue: Queue<Pair<BedrockPacket, Boolean>>) {
        var pair: Pair<BedrockPacket, Boolean>? = null
        while (queue.poll().also { pair = it } != null) {
            if (pair!!.second) {
                sendPacketImmediately(pair.first)
            } else {
                sendPacket(pair.first)
            }
        }
    }

    fun publishEvent(event: KoloEvent): Boolean {
        return dispatchEvent(event, isConsumable = true)
    }

    private fun dispatchEvent(event: KoloEvent, isConsumable: Boolean = true): Boolean {
        val iterator = eventReceivers.iterator()
        while (iterator.hasNext()) {
            val eventReceiver = iterator.next()
            if (event is PacketEvent<*>) {
                event.isConsumable = isConsumable
            }
            eventReceiver.invoke(this, event) {
                iterator.remove()
            }
            if (event is PacketEvent<*> && event.isConsumed) {
                return true
            }
        }
        return false
    }

    private fun createCoroutineScope(coroutineName: String): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName(coroutineName))
    }

}