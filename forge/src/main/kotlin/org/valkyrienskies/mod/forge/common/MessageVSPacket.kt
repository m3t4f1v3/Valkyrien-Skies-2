package org.valkyrienskies.mod.forge.common

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import org.valkyrienskies.mod.common.ValkyrienSkiesMod.MOD_ID

class VSPacket(internal val data: ByteArray) : CustomPacketPayload {
    override fun type() = VS_PACKET_TYPE

    companion object {
        val VS_PACKET_RL: ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, "vs_packet")
        val VS_PACKET_TYPE = CustomPacketPayload.Type<VSPacket>(VS_PACKET_RL)
        val VS_PACKET_CODEC: StreamCodec<FriendlyByteBuf, VSPacket> = StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, VSPacket::data) {
            VSPacket(it)
        }
    }
}

class VSFragmentPacket(internal val data: ByteArray) : CustomPacketPayload {
    override fun type() = VS_FRAGMENT_TYPE

    companion object {
        val VS_FRAGMENT_RL: ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, "vs_fragment")
        val VS_FRAGMENT_TYPE = CustomPacketPayload.Type<VSFragmentPacket>(VS_FRAGMENT_RL)
        val VS_FRAGMENT_CODEC: StreamCodec<FriendlyByteBuf, VSFragmentPacket> = StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, VSFragmentPacket::data) {
            VSFragmentPacket(it)
        }
    }
}
