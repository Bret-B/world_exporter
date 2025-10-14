package bret.worldexporter.networking.packets.serverout;

import bret.worldexporter.WorldExporterClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.fml.network.NetworkEvent;

import java.io.IOException;
import java.util.function.Supplier;

public class SInvokeExport {
    public int blockRadius;
    public int maxYLevel = 255;
    public int minYLevel = 0;
    public boolean optimizeMesh = true;
    public int threadCount = 4;
    public static void encode(SInvokeExport packet, PacketBuffer buf) {
        buf.writeInt(packet.blockRadius);
        buf.writeInt(packet.minYLevel);
        buf.writeInt(packet.maxYLevel);
        buf.writeBoolean(packet.optimizeMesh);
        buf.writeInt(packet.threadCount);
    }
    public static SInvokeExport decode(PacketBuffer buf){
        SInvokeExport export = new SInvokeExport();
        export.blockRadius = buf.readInt();
        export.minYLevel = buf.readInt();
        export.maxYLevel = buf.readInt();
        export.optimizeMesh = buf.readBoolean();
        export.threadCount = buf.readInt();
        return export;
    }

    public static void handle(SInvokeExport packet, Supplier<NetworkEvent.Context> ctx){
        ClientPlayerEntity player = Minecraft.getInstance().player;
        if (player == null) return;

        WorldExporterClient.execute(player,
                packet.blockRadius,
                packet.minYLevel,
                packet.maxYLevel,
                packet.optimizeMesh,
                false,
                packet.threadCount);
    }
}
