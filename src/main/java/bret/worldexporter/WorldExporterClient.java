package bret.worldexporter;

import bret.worldexporter.config.WorldExporterConfig;
import bret.worldexporter.mixinsadditional.IMixinChunkArrayAccessor;
import bret.worldexporter.networking.packets.PacketHandler;
import bret.worldexporter.networking.packets.clientout.CCheckPermissionsPacket;
import bret.worldexporter.networking.packets.clientout.CSetExportStatePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Util;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static bret.worldexporter.WorldExporter.LOGGER;


@Mod.EventBusSubscriber(modid = WorldExporter.MODID, value = Dist.CLIENT)
public class WorldExporterClient {
    private static final String CMD_BASE = "/worldexport ";
    private static boolean clientExporting = false;
    private static boolean canRequestChunks = false;
    private static boolean canPauseServer = false;
    private static boolean installedOnServer = false;  // set during PacketHandler setup
    public static final AtomicBoolean receivedPermissions = new AtomicBoolean(false);
    public static final CyclicBarrier receivedPermissionsBarrier = new CyclicBarrier(2);
    public static final AtomicBoolean stateResponseValid = new AtomicBoolean(false);
    public static final CyclicBarrier receivedStateResponseBarrier = new CyclicBarrier(2);
    private static File baseDir = null;

    public static File getExportDirectory() {
        return baseDir;
    }

    public static String getCacheDirectory() {
        return Paths.get(getExportDirectory().getPath(), "cache").toString();
    }

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
        return installedOnServer && canRequestChunks;
    }

    public static void setCanPauseServer(boolean canPause) {
        canPauseServer = canPause;
    }

    public static boolean canPauseServer() {
        return canPauseServer;
    }

    private static boolean sendInitialPackets() {
        // check permissions from server
        receivedPermissions.set(false);
        receivedPermissionsBarrier.reset();
        if (isInstalledOnServer()) {
            //noinspection InstantiationOfUtilityClass
            PacketHandler.INSTANCE.sendToServer(new CCheckPermissionsPacket());
            try {
                receivedPermissionsBarrier.await(1000L * 10L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                if (!receivedPermissions.get()) {
                    LOGGER.warn("Did not receive permissions response from server in time, disabling server-sided features for this export");
                    canPauseServer = false;
                    canRequestChunks = false;
                }
            }
        }

        stateResponseValid.set(false);
        receivedStateResponseBarrier.reset();
        if (canRequestChunks()) {
            PacketHandler.INSTANCE.sendToServer(new CSetExportStatePacket(true,
                    canPauseServer() && WorldExporterConfig.CLIENT.requestPause.get()));
            try {
                receivedStateResponseBarrier.await(1000L * 10L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                if (!stateResponseValid.get()) {
                    LOGGER.warn("Did not receive state response from server in time, cancelling export");
                    return false;
                }
            }
            return stateResponseValid.get();
        } else {
            return true;
        }
    }

    public static void execute(ClientPlayerEntity player,
                                int radius,
                                int lower,
                                int upper,
                                boolean optimizeMesh,
                                boolean randomizeTextureOrientation,
                                int threads) {

        WorldExporterClient.baseDir = new File(
                Minecraft.getInstance().gameDirectory,
                "worldexporter/worlddump"
                        + java.time.LocalDateTime.now().toString().replace(':', '-'));

        clientExporting = true;
        boolean doExport = sendInitialPackets();
        if (!doExport) {
            player.sendMessage(new StringTextComponent("The server did not authenticate the client. " +
                            "The server state may be broken or there may be another export currently in progress."),
                    Util.NIL_UUID
            );
            return;
        }

        threads = Math.max(1, Math.min(32, threads));
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
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Export failed: " + e);
            success = false;
        } finally {
            clientExporting = false;
            if (canRequestChunks()) {
                PacketHandler.INSTANCE.sendToServer(new CSetExportStatePacket(false, false));
                ((IMixinChunkArrayAccessor) (Object) Objects.requireNonNull(Minecraft.getInstance().level).getChunkSource().storage).worldexporter$clear();
            }
        }

        System.gc();
        player.sendMessage(new StringTextComponent(
                success ? "Export successful." : "An error occurred when exporting the world."), Util.NIL_UUID);
    }

    @SubscribeEvent
    public static void onClientChatEvent(ClientChatEvent event) {
//        String msg = event.getOriginalMessage();
//
//        ClientPlayerEntity player = Minecraft.getInstance().player;
//        ClientWorld world = Minecraft.getInstance().level;
//        if (player == null || world == null) return;
//
//        // the following commands are client side only, so the event is canceled if the msg matches a command
//        if (msg.startsWith(CMD_BASE)) {
//            event.setCanceled(true);
//            execute(msg, player);
//        }
    }
}
