package bret.worldexporter.networking.packets.serverout;

import bret.worldexporter.WorldExporterClient;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.concurrent.BrokenBarrierException;
import java.util.function.Supplier;

public class SSetExportStateResponsePacket {
    public boolean isExporting;
    public boolean paused;
    public boolean isAuthenticated;

    public SSetExportStateResponsePacket(boolean isExporting, boolean paused, boolean isAuthenticated) {
        this.isExporting = isExporting;
        this.paused = paused;
        this.isAuthenticated = isAuthenticated;
    }

    public static SSetExportStateResponsePacket decode(PacketBuffer buf) {
        boolean isExporting = buf.readBoolean();
        boolean paused = buf.readBoolean();
        boolean isAuthenticated = buf.readBoolean();
        return new SSetExportStateResponsePacket(isExporting, paused, isAuthenticated);
    }

    public static void encode(SSetExportStateResponsePacket packet, PacketBuffer buf) {
        buf.writeBoolean(packet.isExporting);
        buf.writeBoolean(packet.paused);
        buf.writeBoolean(packet.isAuthenticated);
    }

    public static void handle(SSetExportStateResponsePacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
        WorldExporterClient.stateResponseValid.set(packet.isExporting && packet.isAuthenticated);
        try {
            if (WorldExporterClient.receivedStateResponseBarrier.getNumberWaiting() != 1) {
                return;
            }

            WorldExporterClient.receivedStateResponseBarrier.await();
        } catch (InterruptedException | BrokenBarrierException ignored) {}
    }
}
