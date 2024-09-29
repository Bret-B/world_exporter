package bret.worldexporter.networking.packets.clientout;

import bret.worldexporter.config.WorldExporterConfig;
import bret.worldexporter.networking.packets.PacketHandler;
import bret.worldexporter.networking.packets.serverout.SPermissionsPacket;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

public class CCheckPermissionsPacket {
    public CCheckPermissionsPacket() {
    }

    public static CCheckPermissionsPacket decode(PacketBuffer buf) {
        //noinspection InstantiationOfUtilityClass
        return new CCheckPermissionsPacket();
    }

    public static void encode(CCheckPermissionsPacket packet, PacketBuffer buf) {
    }

    public static void handle(CCheckPermissionsPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
        ServerPlayerEntity sender = ctx.get().getSender();  // the client that sent this packet
        if (sender == null) {
            return;
        }

        boolean hasPerms = sender.hasPermissions(WorldExporterConfig.SERVER.requiredPermissionLevel.get());
        // WorldExporter.LOGGER.info(String.format("Sending permission response packet from server: hasPerms is %s", hasPerms ? "true" : "false"));
        PacketHandler.sendToPlayer(sender, new SPermissionsPacket(hasPerms, hasPerms && WorldExporterConfig.SERVER.pauseEnabled.get()));
    }
}
