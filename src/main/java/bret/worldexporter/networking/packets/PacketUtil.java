package bret.worldexporter.networking.packets;

import io.netty.buffer.AbstractByteBufAllocator;
import net.minecraft.client.network.play.IClientPlayNetHandler;
import net.minecraft.network.IPacket;
import net.minecraft.network.PacketBuffer;

import java.io.IOException;

public class PacketUtil {
    private static final ThreadLocal<PacketBuffer> BUFFER_THREAD_LOCAL = ThreadLocal.withInitial(
            () -> new PacketBuffer(AbstractByteBufAllocator.DEFAULT.heapBuffer(1 << 20))
    );

    public static void clientPacketWrite(java.io.ObjectOutputStream s,
                                         IPacket<IClientPlayNetHandler> packet) throws java.io.IOException {
        PacketBuffer buffer = getBuffer();
        packet.write(buffer);
        int bytesWritten = buffer.readableBytes();
        s.writeInt(bytesWritten);
        s.write(buffer.array(), 0, bytesWritten);
    }

    public static void clientPacketRead(java.io.ObjectInputStream s,
                                        IPacket<IClientPlayNetHandler> packet) throws java.io.IOException {
        PacketBuffer buffer = getBuffer();
        int bytesToRead = s.readInt();
        buffer.writeBytes(s, bytesToRead);
        packet.read(buffer);
    }

    public static byte[] packetAsBytes(IPacket<IClientPlayNetHandler> packet) {
        PacketBuffer buffer = getBuffer();
        byte[] data;
        try {
            packet.write(buffer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        data = new byte[buffer.readableBytes()];
        buffer.readBytes(data, 0, data.length);
        return data;
    }

    public static PacketBuffer bytesAsBuffer(byte[] bytes) {
        PacketBuffer buffer = getBuffer();
        buffer.writeBytes(bytes);
        return buffer;
    }

    public static PacketBuffer getBuffer() {
        PacketBuffer buffer = BUFFER_THREAD_LOCAL.get();
        buffer.clear();
        return buffer;
    }
}
