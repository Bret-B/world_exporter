package bret.worldexporter.util;

import it.unimi.dsi.fastutil.io.FastBufferedInputStream;
import it.unimi.dsi.fastutil.io.FastBufferedOutputStream;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public class FileUtils {
    private static final int DEFLATER_LEVEL = 1;

    public static void deleteDirectoryRecursive(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            //noinspection ResultOfMethodCallIgnored
            walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (Throwable ignored) {
        }
    }

    public static void storeObjectDeflate(final Object o, final File file) throws IOException {
        final ObjectOutputStream oos = new ObjectOutputStream(
                new DeflaterOutputStream(
                        new FastBufferedOutputStream(
                                Files.newOutputStream(file.toPath())
                        ),
                        new Deflater(DEFLATER_LEVEL),
                        1024 * 16
                )
        );
        oos.writeObject(o);
        oos.close();
    }

    public static Object loadObjectDeflate(final File file) throws IOException, ClassNotFoundException {
        final ObjectInputStream ois = new ObjectInputStream(
                new InflaterInputStream(
                        new FastBufferedInputStream(
                                Files.newInputStream(file.toPath())
                        ),
                        new Inflater(),
                        1024 * 16
                )
        );
        final Object result = ois.readObject();
        ois.close();
        return result;
    }

    public static void storeObjectLZ4(final Object o, final File file) throws IOException {
        final ObjectOutputStream oos = new ObjectOutputStream(
                new LZ4FrameOutputStream(
                        new FastBufferedOutputStream(
                                Files.newOutputStream(file.toPath())
                        ),
                        LZ4FrameOutputStream.BLOCKSIZE.SIZE_256KB
                )
        );
        oos.writeObject(o);
        oos.close();
    }

    public static Object loadObjectLZ4(final File file) throws IOException, ClassNotFoundException {
        final ObjectInputStream ois = new ObjectInputStream(
                new LZ4FrameInputStream(
                        new FastBufferedInputStream(
                                Files.newInputStream(file.toPath())
                        ),
                        false
                )
        );
        final Object result = ois.readObject();
        ois.close();
        return result;
    }
}
