package bret.worldexporter.networking.packets.clientout;

import bret.worldexporter.WorldExporterServer;
import bret.worldexporter.config.WorldExporterConfig;
import bret.worldexporter.networking.packets.PacketHandler;
import bret.worldexporter.networking.packets.serverout.SChunkDataPacketCustom;
import bret.worldexporter.networking.packets.serverout.SUpdateLightPacketCustom;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
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
        ctx.get().setPacketHandled(true);
        ServerPlayerEntity sender = ctx.get().getSender();  // the client that sent this packet
        if (sender == null) {
            return;
        }
        if (sender.hasPermissions(WorldExporterConfig.SERVER.requiredPermissionLevel.get())) {
            // WorldExporter.LOGGER.info(String.format("Request received for chunk x:%d, z:%d received on server", packet.chunkX, packet.chunkZ));
            WorldExporterServer.add(() -> {
                // WorldExporter.LOGGER.info(String.format("Processing chunk request x:%d, z:%d on server thread and returning data packets", packet.chunkX, packet.chunkZ));
                // Will generate or load the chunk from disk as necessary
                Chunk toSend = sender.level.getChunk(packet.chunkX, packet.chunkZ);

                PacketHandler.sendToPlayer(sender, new SUpdateLightPacketCustom(new ChunkPos(packet.chunkX, packet.chunkZ), sender.level.getChunkSource().getLightEngine(), true));
                PacketHandler.sendToPlayer(sender, new SChunkDataPacketCustom(toSend, 65535));
            });
        }
    }
}
