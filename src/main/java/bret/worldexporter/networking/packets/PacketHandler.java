package bret.worldexporter.networking.packets;

import bret.worldexporter.WorldExporterClient;
import bret.worldexporter.networking.packets.clientout.CCheckPermissionsPacket;
import bret.worldexporter.networking.packets.clientout.CRequestChunkPacket;
import bret.worldexporter.networking.packets.clientout.CSetExportStatePacket;
import bret.worldexporter.networking.packets.serverout.SChunkDataPacketCustom;
import bret.worldexporter.networking.packets.serverout.SPermissionsPacket;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.simple.SimpleChannel;

import static bret.worldexporter.WorldExporter.MODID;

public class PacketHandler {
    private static final String PROTOCOL_VERSION = "0";
    public static final ResourceLocation CHANNEL_RESOURCE = new ResourceLocation(MODID, "main");
    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            CHANNEL_RESOURCE,
            () -> PROTOCOL_VERSION,
            PacketHandler::checkServersProtocolVersion,  // the server is never required to have the mod, but check if it does
            (String version) -> true  // the client is never required to have the mod
    );

    // The server version is never necessary so this always returns true
    private static boolean checkServersProtocolVersion(String version) {
        boolean serverSupported = version.equals(PROTOCOL_VERSION);
        WorldExporterClient.setInstalledOnServer(serverSupported);
        return true;
    }

    public static void register() {
        int index = 0;
        // client to server
        INSTANCE.registerMessage(index++, CSetExportStatePacket.class, CSetExportStatePacket::encode, CSetExportStatePacket::decode, CSetExportStatePacket::handle);
        INSTANCE.registerMessage(index++, CRequestChunkPacket.class, CRequestChunkPacket::encode, CRequestChunkPacket::decode, CRequestChunkPacket::handle);
        INSTANCE.registerMessage(index++, CCheckPermissionsPacket.class, CCheckPermissionsPacket::encode, CCheckPermissionsPacket::decode, CCheckPermissionsPacket::handle);

        // server to client
        INSTANCE.registerMessage(index++, SPermissionsPacket.class, SPermissionsPacket::encode, SPermissionsPacket::decode, SPermissionsPacket::handle);
        INSTANCE.registerMessage(index++, SChunkDataPacketCustom.class, SChunkDataPacketCustom::encode, SChunkDataPacketCustom::decode, SChunkDataPacketCustom::handle);
    }
}
