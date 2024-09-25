package bret.worldexporter.networking.packets.clientout;

import bret.worldexporter.WorldExporter;
import bret.worldexporter.config.WorldExporterConfig;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

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
        // enqueueWork is for stuff that needs to be thread safe
        // ctx.get().enqueueWork(() -> {});
        ctx.get().setPacketHandled(true);
        ServerPlayerEntity sender = ctx.get().getSender();  // the client that sent this packet
        if (sender == null) {
            return;
        }
        if (!sender.hasPermissions(WorldExporterConfig.SERVER.requiredPermissionLevel.get())) {
            return;
        }

        // TODO can check uuids to only have the person who started an export be able to change this stuff
        if (WorldExporter.serverExporting != packet.isExporting) {
            WorldExporter.serverExporting = packet.isExporting;
        }
        if (WorldExporterConfig.SERVER.pauseEnabled.get()) {
            WorldExporter.serverShouldBePaused.set(packet.pauseDesired);
        }
    }
}
