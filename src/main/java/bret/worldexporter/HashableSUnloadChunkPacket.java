package bret.worldexporter;

import net.minecraft.network.play.server.SUnloadChunkPacket;

import java.util.Objects;

public class HashableSUnloadChunkPacket extends SUnloadChunkPacket {
    public HashableSUnloadChunkPacket(SUnloadChunkPacket packet) {
        super(packet.getX(), packet.getZ());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getX(), getZ());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        HashableSUnloadChunkPacket packet = (HashableSUnloadChunkPacket) obj;
        return getX() == packet.getX() && getZ() == packet.getZ();
    }
}
