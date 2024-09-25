package bret.worldexporter.networking.packets.serverout;

import bret.worldexporter.WorldExporterClient;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.concurrent.BrokenBarrierException;
import java.util.function.Supplier;

public class SPermissionsPacket {
    public boolean canRequestChunks;
    public boolean canPause;

    public SPermissionsPacket(boolean canRequestChunks, boolean canPause) {
        this.canRequestChunks = canRequestChunks;
        this.canPause = canPause;
    }

    public static SPermissionsPacket decode(PacketBuffer buf) {
        boolean canRequestChunks = buf.readBoolean();
        boolean canPause = buf.readBoolean();
        return new SPermissionsPacket(canRequestChunks, canPause);
    }

    public static void encode(SPermissionsPacket packet, PacketBuffer buf) {
        buf.writeBoolean(packet.canRequestChunks);
        buf.writeBoolean(packet.canPause);
    }

    public static void handle(SPermissionsPacket packet, Supplier<NetworkEvent.Context> ctx) {
        WorldExporterClient.setCanRequestChunks(packet.canRequestChunks);
        WorldExporterClient.setCanPauseServer(packet.canPause);
        WorldExporterClient.receivedPermissions.set(true);
        try {
            if (WorldExporterClient.receivedPermissionsBarrier.getNumberWaiting() != 1) {
                return;
            }

            WorldExporterClient.receivedPermissionsBarrier.await();
        } catch (InterruptedException | BrokenBarrierException ignored) {}
    }
}
