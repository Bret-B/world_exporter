package bret.worldexporter.networking.packets;

import bret.worldexporter.Exporter;

public interface IWorldExporterPacket {
//    public static <T> T decode(PacketBuffer buf) {
//        return null;
//    }
//    public static <T> void encode(T packet, PacketBuffer buf) {}
//    public static <T> void handle(T packet, Supplier<NetworkEvent.Context> ctx) {}
    public void handleOnClientMainThread(Exporter exporter);
}
