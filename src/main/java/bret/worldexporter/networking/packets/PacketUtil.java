package bret.worldexporter.networking.packets;

import io.netty.buffer.AbstractByteBufAllocator;
import net.minecraft.client.network.play.IClientPlayNetHandler;
import net.minecraft.network.IPacket;
import net.minecraft.network.PacketBuffer;

public class PacketUtil {

    public static void clientPacketWrite(java.io.ObjectOutputStream s,
                                         IPacket<IClientPlayNetHandler> packet,
                                         int initialCapacity) throws java.io.IOException {
        PacketBuffer temp = new PacketBuffer(AbstractByteBufAllocator.DEFAULT.heapBuffer(initialCapacity));
        packet.write(temp);
        int bytesWritten = temp.readableBytes();

        s.writeInt(bytesWritten);
        for (int i = 0; i < bytesWritten; ++i) {
            s.writeByte(temp.getByte(i));
        }
    }

    public static void clientPacketRead(java.io.ObjectInputStream s,
                                        IPacket<IClientPlayNetHandler> packet) throws java.io.IOException {
        int bytesToRead = s.readInt();
        PacketBuffer temp = new PacketBuffer(AbstractByteBufAllocator.DEFAULT.heapBuffer(bytesToRead));
        for (int i = 0; i < bytesToRead; i++) {
            temp.writeByte(s.readByte());
        }

        packet.read(temp);
    }

}
