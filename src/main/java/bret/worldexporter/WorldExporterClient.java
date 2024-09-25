package bret.worldexporter;

import bret.worldexporter.config.WorldExporterConfig;
import bret.worldexporter.networking.packets.PacketHandler;
import bret.worldexporter.networking.packets.clientout.CCheckPermissionsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Util;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static bret.worldexporter.WorldExporter.LOGGER;


@Mod.EventBusSubscriber(modid = WorldExporter.MODID, value = Dist.CLIENT)
public class WorldExporterClient {
    private static final String CMD_BASE = "/worldexport";
    private static boolean clientExporting = false;
    private static boolean canRequestChunks = false;
    private static boolean canPauseServer = false;
    private static boolean installedOnServer = false;
    public static final AtomicBoolean receivedPermissions = new AtomicBoolean(false);
    public static final CyclicBarrier receivedPermissionsBarrier = new CyclicBarrier(2);

    public static boolean isClientExporting() {
        return clientExporting;
    }

    public static void setInstalledOnServer(boolean isInstalled) {
        installedOnServer = isInstalled;
    }

    public static boolean isInstalledOnServer() {
        return installedOnServer;
    }

    public static void setCanRequestChunks(boolean canRequest) {
        canRequestChunks = canRequest;
    }

    public static boolean canRequestChunks() {
        return canRequestChunks;
    }

    public static void setCanPauseServer(boolean canPause) {
        canPauseServer = canPause;
    }

    public static boolean canPauseServer() {
        return canPauseServer;
    }

    private static void execute(String msg, ClientPlayerEntity player) {
        // check permissions from server
        receivedPermissions.set(false);
        receivedPermissionsBarrier.reset();
        if (isInstalledOnServer()) {
            //noinspection InstantiationOfUtilityClass
            PacketHandler.INSTANCE.sendToServer(new CCheckPermissionsPacket());
            try {
//                receivedPermissions.wait(1000L * 15L);
                //noinspection ResultOfMethodCallIgnored
                receivedPermissionsBarrier.await(1000L * 15L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                if (!receivedPermissions.get()) {
                    LOGGER.warn("Did not receive permissions response from server within 8s, disabling server-sided features for this export");
                    canPauseServer = false;
                    canRequestChunks = false;
                }
            }
        }

        String argsString = msg.substring(CMD_BASE.length()).trim();
        String[] params = argsString.isEmpty() ? new String[]{} : argsString.split("\\s+");
        int radius = 64;
        int lower = 0;
        int upper = 255;
        boolean optimizeMesh = true;
        boolean randomizeTextureOrientation = false;
        int threads = 4;
        try {
            radius = params.length >= 1 ? Integer.parseInt(params[0]) : radius;
            lower = params.length >= 2 ? Integer.parseInt(params[1]) : lower;
            upper = params.length >= 3 ? Integer.parseInt(params[2]) : upper;
            optimizeMesh = params.length >= 4 ? Boolean.parseBoolean(params[3]) : optimizeMesh;
            randomizeTextureOrientation = params.length >= 5 ? Boolean.parseBoolean(params[4]) : randomizeTextureOrientation;
            threads = params.length >= 6 ? Integer.parseInt(params[5]) : threads;
        } catch (Exception exception) {
            player.sendMessage(new StringTextComponent("There was an error parsing the command arguments. " +
                            "Example usage: " + CMD_BASE + " 64 0 255 true false 4"),
                    Util.NIL_UUID
            );
            return;
        }
        threads = Math.max(1, Math.min(32, threads));

        clientExporting = true;
        ObjExporter objExporter = new ObjExporter(player, radius, lower, upper, optimizeMesh, randomizeTextureOrientation, threads);
        boolean success;
        try {
            success = objExporter.export("world", "world_materials");
        } catch (OutOfMemoryError e) {
            player.sendMessage(new StringTextComponent("Ran out of memory while exporting. " +
                            "Allocate more memory to Minecraft or reduce the number of export threads and try again." +
                            ((WorldExporterConfig.CLIENT.exportVisibleExteriorOnly.get()
                                    && !WorldExporterConfig.CLIENT.segmentedExteriorPathfinding.get())
                                    ? " Also, turning on segmentedExteriorPathfinding can help reduce required memory" : "")),
                    Util.NIL_UUID
            );
            System.gc();
            return;
        } catch (IOException e) {
            LOGGER.error("Export failed: " + e);
            success = false;
        } finally {
            clientExporting = false;
            if (canRequestChunks) {
                ((IMixinChunkArrayAccessor) (Object) Objects.requireNonNull(Minecraft.getInstance().level).getChunkSource().storage).worldexporter$clear();
            }
        }

        System.gc();
        player.sendMessage(new StringTextComponent(
                success ? "Export successful." : "An error occurred when exporting the world."), Util.NIL_UUID);
    }

    private static void debug(String msg, ClientWorld world, ClientPlayerEntity player) {
        clientExporting = true;
        try {

        } catch (NullPointerException | ClassCastException e) {
            LOGGER.warn("Unable to change pause status of internal server");
        } catch (Throwable e) {
            throw new RuntimeException(e);
        } finally {
            clientExporting = false;
            if (canRequestChunks) {
                ((IMixinChunkArrayAccessor) (Object) Objects.requireNonNull(Minecraft.getInstance().level).getChunkSource().storage).worldexporter$clear();
            }
        }
    }

    @SubscribeEvent
    public static void onClientChatEvent(ClientChatEvent event) {
        String msg = event.getOriginalMessage();

        ClientPlayerEntity player = Minecraft.getInstance().player;
        ClientWorld world = Minecraft.getInstance().level;
        if (player == null || world == null) return;

        // the following commands are client side only, so the event is canceled if the msg matches a command
        if (msg.startsWith(CMD_BASE)) {
            event.setCanceled(true);
            execute(msg, player);
        }

        if (msg.startsWith("/wedebug")) {
            event.setCanceled(true);
            debug(msg, world, player);
        }
    }
}
