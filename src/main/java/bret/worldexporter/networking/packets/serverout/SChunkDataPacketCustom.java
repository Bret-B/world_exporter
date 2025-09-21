package bret.worldexporter.networking.packets.serverout;

import bret.worldexporter.ChunkThreadSyncManager;
import bret.worldexporter.networking.packets.PacketUtil;
import bret.worldexporter.networking.packets.ReceivedChunkEnum;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SChunkDataPacket;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.network.NetworkEvent;

import java.io.IOException;
import java.io.Serializable;
import java.util.Objects;
import java.util.function.Supplier;

public class SChunkDataPacketCustom implements Serializable {
    private SChunkDataPacket nested;

    public SChunkDataPacketCustom() {
    }

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

    private void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
        PacketUtil.clientPacketWrite(s, nested, 1 << 20);
    }

    private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
        nested = new SChunkDataPacket();
        PacketUtil.clientPacketRead(s, nested);
    }

    public static void handle(SChunkDataPacketCustom packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
        // WorldExporter.LOGGER.info("Received SChunkDataPacketCustom");
        handle(packet, true);
    }

    public static void handle(SChunkDataPacketCustom packet, boolean saveToDisk) {
        ChunkThreadSyncManager.add(() -> {
            // WorldExporter.LOGGER.info("Handling SChunkDataPacketCustom");
            packet.nested.handle(Objects.requireNonNull(Minecraft.getInstance().getConnection()));
            if (saveToDisk) {
                ChunkThreadSyncManager.saveChunkDataPacket(packet);
            }
            ChunkThreadSyncManager.notifyChunkReceived(packet.nested.getX(), packet.nested.getZ(), ReceivedChunkEnum.CHUNK_DATA);
        });
    }

    public long getPos() {
        return ChunkPos.asLong(nested.getX(), nested.getZ());
    }
}
