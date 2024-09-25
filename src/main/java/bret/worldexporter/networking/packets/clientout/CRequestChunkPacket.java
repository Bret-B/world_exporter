package bret.worldexporter.networking.packets.clientout;

import bret.worldexporter.config.WorldExporterConfig;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

public class CRequestChunkPacket {
    public int chunkX;
    public int chunkZ;

    public CRequestChunkPacket(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public CRequestChunkPacket(BlockPos pos) {
        this.chunkX = pos.getX() >> 4;
        this.chunkZ = pos.getZ() >> 4;
    }

    public static CRequestChunkPacket decode(PacketBuffer buf) {
        int chunkX = buf.readInt();
        int chunkZ = buf.readInt();
        return new CRequestChunkPacket(chunkX, chunkZ);
    }

    public static void encode(CRequestChunkPacket packet, PacketBuffer buf) {
        buf.writeInt(packet.chunkX);
        buf.writeInt(packet.chunkZ);
    }

    public static void handle(CRequestChunkPacket packet, Supplier<NetworkEvent.Context> ctx) {
        // enqueueWork is for stuff that needs to be thread safe
        ctx.get().setPacketHandled(true);
        ServerPlayerEntity sender = ctx.get().getSender();  // the client that sent this packet
        if (sender == null) {
            return;
        }
        if (sender.hasPermissions(WorldExporterConfig.SERVER.requiredPermissionLevel.get())) {
            ctx.get().enqueueWork(() -> {
                Chunk toSend = sender.level.getChunk(packet.chunkX, packet.chunkZ);
                // TODO send packets

            });
        }
    }
}
