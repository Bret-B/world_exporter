package bret.worldexporter.networking.packets.serverout;

import bret.worldexporter.ChunkThreadSyncManager;
import bret.worldexporter.networking.packets.ReceivedChunkEnum;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SChunkDataPacket;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.network.NetworkEvent;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;

public class SChunkDataPacketCustom {
    private SChunkDataPacket nested;

    public SChunkDataPacketCustom() {}

    public SChunkDataPacketCustom(SChunkDataPacket nested) {
        this.nested = nested;
    }

    public SChunkDataPacketCustom(Chunk chunk, int size) {
        this.nested = new SChunkDataPacket(chunk, size);
    }

    public static SChunkDataPacketCustom decode(PacketBuffer buf) {
        SChunkDataPacket nested = new SChunkDataPacket();
        try {
            nested.read(buf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new SChunkDataPacketCustom(nested);
    }

    public static void encode(SChunkDataPacketCustom packet, PacketBuffer buf) {
        try {
            packet.nested.write(buf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void handle(SChunkDataPacketCustom packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
        // WorldExporter.LOGGER.info("Received SChunkDataPacketCustom");
        ChunkThreadSyncManager.add(() -> {
            // WorldExporter.LOGGER.info("Handling SChunkDataPacketCustom");
            packet.nested.handle(Objects.requireNonNull(Minecraft.getInstance().getConnection()));
            ChunkThreadSyncManager.notifyChunkReceived(packet.nested.getX(), packet.nested.getZ(), ReceivedChunkEnum.CHUNK_DATA);
        });
    }
}
