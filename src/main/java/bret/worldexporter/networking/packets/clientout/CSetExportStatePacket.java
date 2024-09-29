package bret.worldexporter.networking.packets.clientout;

import bret.worldexporter.WorldExporterServer;
import bret.worldexporter.config.WorldExporterConfig;
import bret.worldexporter.networking.packets.PacketHandler;
import bret.worldexporter.networking.packets.serverout.SSetExportStateResponsePacket;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.PacketDistributor;

import java.util.function.Supplier;

public class CSetExportStatePacket {
    public boolean isExporting;
    public boolean pauseDesired;

    public CSetExportStatePacket(boolean isExporting, boolean pauseDesired) {
        this.isExporting = isExporting;
        this.pauseDesired = pauseDesired;
    }

    public static CSetExportStatePacket decode(PacketBuffer buf) {
        return new CSetExportStatePacket(buf.readBoolean(), buf.readBoolean());
    }

    public static void encode(CSetExportStatePacket packet, PacketBuffer buf) {
        buf.writeBoolean(packet.isExporting);
        buf.writeBoolean(packet.pauseDesired);
    }

    public static void handle(CSetExportStatePacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
        ServerPlayerEntity sender = ctx.get().getSender();  // the client that sent this packet
        if (sender == null) {
            return;
        }
        if (!sender.hasPermissions(WorldExporterConfig.SERVER.requiredPermissionLevel.get())) {
            PacketHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> sender), new SSetExportStateResponsePacket(
                    WorldExporterServer.serverExporting.get(),
                    WorldExporterServer.serverShouldBePaused.get(),
                    false
            ));
            return;
        }

        if (WorldExporterServer.serverExporting.get() && WorldExporterServer.exportRequester.get().equals(sender.getUUID())) {
            // modify export settings if requested by the client, and they have authority (started the export)
            WorldExporterServer.serverExporting.set(packet.isExporting);
            WorldExporterServer.serverShouldBePaused.set(packet.isExporting && packet.pauseDesired);
        } else if (!WorldExporterServer.serverExporting.get()) {
            // start the export if requested by the client and set them to have authority of this export
            if (packet.isExporting) {
                WorldExporterServer.exportRequester.set(sender.getUUID());
                WorldExporterServer.requesterWorld.set(sender.getLevel());
                WorldExporterServer.serverExporting.set(true);
                WorldExporterServer.serverShouldBePaused.set(packet.pauseDesired);
            }
        }

        PacketHandler.sendToPlayer(sender,
                new SSetExportStateResponsePacket(
                        WorldExporterServer.serverExporting.get(),
                        WorldExporterServer.serverShouldBePaused.get(),
                        WorldExporterServer.exportRequester.get() != null
                                && WorldExporterServer.exportRequester.get().equals(sender.getUUID())
                ));
    }
}
