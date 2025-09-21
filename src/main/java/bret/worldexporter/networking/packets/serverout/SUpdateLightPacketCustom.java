package bret.worldexporter.networking.packets.serverout;

import bret.worldexporter.ChunkThreadSyncManager;
import bret.worldexporter.networking.packets.PacketUtil;
import bret.worldexporter.networking.packets.ReceivedChunkEnum;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SUpdateLightPacket;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.lighting.WorldLightManager;
import net.minecraftforge.fml.network.NetworkEvent;

import java.io.IOException;
import java.io.Serializable;
import java.util.Objects;
import java.util.function.Supplier;

public class SUpdateLightPacketCustom implements Serializable {
    private SUpdateLightPacket nested;

    public SUpdateLightPacketCustom() {}

    public SUpdateLightPacketCustom(SUpdateLightPacket nested) {
        this.nested = nested;
    }

    public SUpdateLightPacketCustom(ChunkPos p_i50774_1_, WorldLightManager p_i50774_2_, boolean p_i50774_3_) {
        this.nested = new SUpdateLightPacket(p_i50774_1_, p_i50774_2_, p_i50774_3_);
    }

    public static SUpdateLightPacketCustom decode(PacketBuffer buf) {
        SUpdateLightPacket nested = new SUpdateLightPacket();
        try {
            nested.read(buf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new SUpdateLightPacketCustom(nested);
    }

    public static void encode(SUpdateLightPacketCustom packet, PacketBuffer buf) {
        try {
            packet.nested.write(buf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
        PacketUtil.clientPacketWrite(s, nested, 1 << 18);
    }

    private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
        nested = new SUpdateLightPacket();
        PacketUtil.clientPacketRead(s, nested);
    }

    public static void handle(SUpdateLightPacketCustom packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
        // WorldExporter.LOGGER.info(String.format("Received SUpdateLightPacketCustom, x:%d, z:%d", packet.nested.getX(), packet.nested.getZ()));
        handle(packet, true);
    }

    public static void handle(SUpdateLightPacketCustom packet, boolean saveToDisk) {
        ChunkThreadSyncManager.add(() -> {
            // WorldExporter.LOGGER.info(String.format("Handling SUpdateLightPacketCustom, x:%d, z:%d", packet.nested.getX(), packet.nested.getZ()));
            packet.nested.handle(Objects.requireNonNull(Minecraft.getInstance().getConnection()));
            if (saveToDisk) {
                ChunkThreadSyncManager.saveLightDataPacket(packet);
            }
            ChunkThreadSyncManager.notifyChunkReceived(packet.nested.getX(), packet.nested.getZ(), ReceivedChunkEnum.LIGHT_DATA);
        });
    }

    public long getPos() {
        return ChunkPos.asLong(nested.getX(), nested.getZ());
    }
}
