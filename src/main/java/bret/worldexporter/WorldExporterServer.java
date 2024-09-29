package bret.worldexporter;

import net.minecraft.world.server.ServerWorld;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

// TODO subscribe to player disconnection events and stop the export/unpause if the player leaves
//@Mod.EventBusSubscriber(modid = WorldExporter.MODID, value = Dist.DEDICATED_SERVER)
public class WorldExporterServer {
    // touched only by the server side
    private static final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    public static final AtomicBoolean serverShouldBePaused = new AtomicBoolean(false);
    public static final AtomicBoolean serverExporting = new AtomicBoolean(false);
    public static final AtomicReference<UUID> exportRequester = new AtomicReference<>();
    public static final AtomicReference<ServerWorld> requesterWorld = new AtomicReference<>();

    public static void add(Runnable task) {
        tasks.add(task);
    }

    public static void processTasks(BooleanSupplier pHasTimeLeft) {
        do {
            Runnable task = tasks.poll();
            if (task != null) task.run();
        } while (!tasks.isEmpty() && pHasTimeLeft.getAsBoolean());
    }

    public static boolean tasksEmpty() {
        return tasks.isEmpty();
    }
}
