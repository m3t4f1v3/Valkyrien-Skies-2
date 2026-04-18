package org.valkyrienskies.mod.forge.common

import com.mojang.logging.LogUtils
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.network.registration.HandlerThread
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import org.valkyrienskies.core.internal.hooks.VsiCoreHooksIn
import org.valkyrienskies.core.internal.world.VsiPlayer
import org.valkyrienskies.mod.common.mcPlayer
import org.valkyrienskies.mod.common.networking.VSPacketFragmenter
import org.valkyrienskies.mod.mixinducks.world.entity.PlayerDuck
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

object VSForgeNetworking {
    private val logger = LogUtils.getLogger()
    private val clientReceiveLogsRemaining = AtomicInteger(12)
    private val serverReceiveLogsRemaining = AtomicInteger(12)
    private val sendToClientLogsRemaining = AtomicInteger(12)
    private val sendToServerLogsRemaining = AtomicInteger(12)
    lateinit var registrar: PayloadRegistrar
    lateinit var hooks: VsiCoreHooksIn

    fun registerPacketHandlers(hooks: VsiCoreHooksIn) {
        // Loading this class registers the handlers
        this.hooks = hooks
    }

    @SubscribeEvent
    fun register(event: RegisterPayloadHandlersEvent) {
        // Sets the current network version
        registrar = event.registrar("1")
        registrar = registrar.executesOn(HandlerThread.NETWORK)
        registrar.playBidirectional(
            VSPacket.VS_PACKET_TYPE,
            VSPacket.VS_PACKET_CODEC,
            DirectionalPayloadHandler<VSPacket>(
                ::handleClient,
                ::handleServer,
            )
        )
        registrar.playBidirectional(
            VSFragmentPacket.VS_FRAGMENT_TYPE,
            VSFragmentPacket.VS_FRAGMENT_CODEC,
            DirectionalPayloadHandler<VSFragmentPacket>(
                ::handleClientFragment,
                ::handleServerFragment,
            )
        )
    }

    fun handleClient(
        message: VSPacket,
        context: IPayloadContext,
    ): CompletableFuture<Void?> = context.enqueueWork {
        if (clientReceiveLogsRemaining.getAndDecrement() > 0) {
            logger.info("VS NeoForge handleClient packetBytes={}", message.data.size)
        }
        hooks.onReceiveClient(Unpooled.wrappedBuffer(message.data))
    }

    fun handleServer(
        message: VSPacket,
        context: IPayloadContext,
    ): CompletableFuture<Void?> = context.enqueueWork {
        val player = context.player()
        if (player != null) {
            if (serverReceiveLogsRemaining.getAndDecrement() > 0) {
                logger.info(
                    "VS NeoForge handleServer packetBytes={} playerAddr={}",
                    message.data.size,
                    (player as ServerPlayer).connection.connection.remoteAddress,
                )
            }
            hooks.onReceiveServer(Unpooled.wrappedBuffer(message.data), (player as PlayerDuck).vs_getPlayer())
        } else {
            println("context.sender was null?")
        }
    }

    fun handleClientFragment(
        message: VSFragmentPacket,
        context: IPayloadContext,
    ): CompletableFuture<Void?> = context.enqueueWork {
        if (clientReceiveLogsRemaining.getAndDecrement() > 0) {
            logger.info("VS NeoForge handleClientFragment packetBytes={}", message.data.size)
        }
        val assembled = VSPacketFragmenter.onReceiveFragment(Unpooled.wrappedBuffer(message.data))
        if (assembled != null) {
            hooks.onReceiveClient(assembled)
        }
    }

    fun handleServerFragment(
        message: VSFragmentPacket,
        context: IPayloadContext,
    ): CompletableFuture<Void?> = context.enqueueWork {
        if (serverReceiveLogsRemaining.getAndDecrement() > 0) {
            val player = context.player()
            logger.info(
                "VS NeoForge handleServerFragment packetBytes={} playerAddr={}",
                message.data.size,
                if (player is ServerPlayer) player.connection.connection.remoteAddress else "null",
            )
        }
        val assembled = VSPacketFragmenter.onReceiveFragment(Unpooled.wrappedBuffer(message.data))
        if (assembled != null) {
            val player = context.player()
            if (player != null) {
                hooks.onReceiveServer(assembled, (player as PlayerDuck).vs_getPlayer())
            } else {
                println("context.sender was null?")
            }
        }
    }

    private fun ByteBuf.copyToByteArray(): ByteArray {
        val byteArray = ByteArray(readableBytes())
        getBytes(readerIndex(), byteArray)
        return byteArray
    }

    fun sendToClient(data: ByteBuf, player: VsiPlayer) {
        if (sendToClientLogsRemaining.getAndDecrement() > 0) {
            logger.info(
                "VS NeoForge sendToClient packetBytes={} playerAddr={}",
                data.readableBytes(),
                (player.mcPlayer as ServerPlayer).connection.connection.remoteAddress,
            )
        }
        if (VSPacketFragmenter.needsSplitting(data)) {
            for (fragment in VSPacketFragmenter.split(data.copy())) {
                PacketDistributor.sendToPlayer(player.mcPlayer as ServerPlayer, VSFragmentPacket(fragment.copyToByteArray()))
            }
        } else {
            PacketDistributor.sendToPlayer(player.mcPlayer as ServerPlayer, VSPacket(data.copyToByteArray()))
        }
    }

    fun sendToServer(data: ByteBuf) {
        if (sendToServerLogsRemaining.getAndDecrement() > 0) {
            logger.info("VS NeoForge sendToServer packetBytes={}", data.readableBytes())
        }
        if (VSPacketFragmenter.needsSplitting(data)) {
            for (fragment in VSPacketFragmenter.split(data.copy())) {
                PacketDistributor.sendToServer(VSFragmentPacket(fragment.copyToByteArray()))
            }
        } else {
            PacketDistributor.sendToServer(VSPacket(data.copyToByteArray()))
        }
    }
}
