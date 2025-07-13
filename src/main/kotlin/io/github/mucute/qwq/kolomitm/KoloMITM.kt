package io.github.mucute.qwq.kolomitm

import io.github.mucute.qwq.kolomitm.definition.Definitions
import io.github.mucute.qwq.kolomitm.event.receiver.*
import io.github.mucute.qwq.kolomitm.session.KoloSession
import io.github.mucute.qwq.kolomitm.util.fetchAccount
import io.github.mucute.qwq.kolomitm.util.refresh
import io.github.mucute.qwq.kolomitm.util.saveAccount
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import net.raphimc.minecraftauth.step.bedrock.session.StepFullBedrockSession
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerRateLimiter
import org.cloudburstmc.protocol.bedrock.BedrockPeer
import org.cloudburstmc.protocol.bedrock.BedrockPong
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.codec.v819.Bedrock_v819
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket
import java.net.InetSocketAddress
import java.nio.file.Paths
import java.util.concurrent.ThreadLocalRandom

@Suppress("MemberVisibilityCanBePrivate")
class KoloMITM {

    var codec: BedrockCodec = Bedrock_v819.CODEC

    var advertisement: BedrockPong = BedrockPong()
        .edition("MCPE")
        .gameType("Survival")
        .version(codec.minecraftVersion)
        .protocolVersion(codec.protocolVersion)
        .motd("Kolo MITM")
        .playerCount(0)
        .maximumPlayerCount(20)
        .subMotd("A MITM cheat for Minecraft: Bedrock Edition")
        .nintendoLimited(false);

    var account: StepFullBedrockSession.FullBedrockSession? = null

    var localAddress = InetSocketAddress("0.0.0.0", 19132)

    var remoteAddress = InetSocketAddress("geo.hivebedrock.network", 19132)

    var serverEventLoopGroup: EventLoopGroup = NioEventLoopGroup()

    var clientEventLoopGroup: EventLoopGroup = NioEventLoopGroup()

    var serverChannel: Channel? = null

    var clientChannel: Channel? = null

    val koloSession = KoloSession(this)

    companion object {

        // Used for running as a program
        @JvmStatic
        fun main(args: Array<String>) {
            val waigamePasswordFile = Paths.get(".").resolve("waigamePassword.txt").toFile()
            val waigamePassword = if (waigamePasswordFile.isFile) waigamePasswordFile.readText() else null
            var account = fetchAccount()
            if (account.isExpired) {
                println("Expired account, refreshing and saving")
                account = account.refresh().also { saveAccount(it) }
            }

            Definitions.loadBlockPalette()
            val koloMITM = KoloMITM()
            koloMITM.account = account
            koloMITM.localAddress = InetSocketAddress("0.0.0.0", 19132)
            koloMITM.remoteAddress = InetSocketAddress("play.lbsg.net", 19132)
            koloMITM.koloSession.apply {
                proxyPassReceiver()
                definitionReceiver()
                transferReceiver()
                transferCommandReceiver()
                echoCommandReceiver()
                waigamePassword?.let { autoLoginWaiGameReceiver(it) }
            }
            koloMITM.bootServer()

            println("Kolo MITM started at: ${koloMITM.localAddress}")
        }

    }

    fun bootServer() {
        advertisement
            .ipv4Port(localAddress.port)
            .ipv6Port(localAddress.port)

        serverChannel = ServerBootstrap()
            .group(serverEventLoopGroup)
            .channelFactory(RakChannelFactory.server(NioDatagramChannel::class.java))
            .option(RakChannelOption.RAK_ADVERTISEMENT, advertisement.toByteBuf())
            .option(RakChannelOption.RAK_GUID, ThreadLocalRandom.current().nextLong())
            .option(RakChannelOption.RAK_PACKET_LIMIT, Int.MAX_VALUE)
            .option(RakChannelOption.RAK_GLOBAL_PACKET_LIMIT, Int.MAX_VALUE)
            .option(RakChannelOption.RAK_FLUSH_INTERVAL, 5)
            .childOption(RakChannelOption.RAK_ORDERING_CHANNELS, 1)
            .childHandler(object : BedrockChannelInitializer<KoloSession.InboundSession>() {
                override fun createSession0(peer: BedrockPeer, subClientId: Int): KoloSession.InboundSession {
                    return koloSession.InboundSession(peer, subClientId)
                }

                override fun initSession(session: KoloSession.InboundSession) {}
            })
            .localAddress(localAddress)
            .bind()
            .awaitUninterruptibly()
            .channel()
            .also { it.pipeline().remove(RakServerRateLimiter::class.java) }
    }

    fun bootClient(inetSocketAddress: InetSocketAddress = remoteAddress) {
        val clientGUID = ThreadLocalRandom.current().nextLong()

        clientChannel = Bootstrap()
            .group(clientEventLoopGroup)
            .channelFactory(RakChannelFactory.client(NioDatagramChannel::class.java))
            .option(RakChannelOption.RAK_PROTOCOL_VERSION, codec.raknetProtocolVersion)
            .option(RakChannelOption.RAK_GUID, clientGUID)
            .option(RakChannelOption.RAK_REMOTE_GUID, clientGUID)
            .option(RakChannelOption.RAK_MTU, 1492)
            .option(RakChannelOption.RAK_MTU_SIZES, arrayOf(1492, 1200, 576))
            .option(RakChannelOption.RAK_MAX_QUEUED_BYTES, Int.MAX_VALUE)
            .option(RakChannelOption.RAK_FLUSH_INTERVAL, 5)
            .option(RakChannelOption.RAK_COMPATIBILITY_MODE, true)
            .handler(object : BedrockChannelInitializer<KoloSession.OutboundSession>() {
                override fun createSession0(peer: BedrockPeer, subClientId: Int): KoloSession.OutboundSession {
                    return koloSession.OutboundSession(peer, subClientId)
                }

                override fun initSession(session: KoloSession.OutboundSession) {
                    session.sendPacketImmediately(RequestNetworkSettingsPacket().apply {
                        protocolVersion = codec.protocolVersion
                    })
                }
            })
            .remoteAddress(inetSocketAddress)
            .connect()
            .awaitUninterruptibly()
            .channel()
    }

}