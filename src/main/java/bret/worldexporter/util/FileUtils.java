package bret.worldexporter.util;

import it.unimi.dsi.fastutil.io.FastBufferedInputStream;
import it.unimi.dsi.fastutil.io.FastBufferedOutputStream;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.*;

public class FileUtils {
    private static final int DEFLATER_LEVEL = 1;

    public static void deleteDirectoryRecursive(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            //noinspection ResultOfMethodCallIgnored
            walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            throw new RuntimeException(e);
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
}
