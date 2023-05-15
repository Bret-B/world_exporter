package bret.worldexporter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.network.play.ClientPlayNetHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.play.server.SUnloadChunkPacket;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.Util;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

@Mod(WorldExporter.MODID)
public class WorldExporter {
    public static final String MODID = "worldexporter";
    private static final Logger LOGGER = LogManager.getLogger(WorldExporter.MODID);
    private static final String CMD_BASE = "/worldexport";
    private static final String CMD_RADIUS = CMD_BASE + " keepradius";
    private static final Set<HashableSUnloadChunkPacket> heldChunks = new HashSet<>();
    private static int forceChunkRadius = -1;

    public WorldExporter() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    public static int getForceChunkRadius() {
        return forceChunkRadius;
    }

    private static void setForceChunkRadius(int newRadius) {
        forceChunkRadius = newRadius;
    }

    public static void addHeldChunk(SUnloadChunkPacket packet) {
        heldChunks.add(new HashableSUnloadChunkPacket(packet));
    }

    public static boolean isInRender(int chunkX, int chunkZ) {
        int playerRender = Minecraft.getInstance().options.renderDistance;
        ClientPlayerEntity player = Minecraft.getInstance().player;
        if (player == null) return false;
        int chunkXDist = Math.abs(player.xChunk - chunkX);
        int chunkZDist = Math.abs(player.zChunk - chunkZ);
        return chunkXDist <= playerRender && chunkZDist <= playerRender;
    }

    private static void execute(String msg, ClientPlayerEntity player) {
        String[] params = msg.substring(CMD_BASE.length()).trim().split("\\s+");
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
        threads = Math.max(1, Math.min(8, threads));

        ObjExporter objExporter = new ObjExporter(player, radius, lower, upper, optimizeMesh, randomizeTextureOrientation, threads);
        boolean success;
        try {
            success = objExporter.export("world.obj", "world.mtl");
        } catch (OutOfMemoryError e) {
            player.sendMessage(new StringTextComponent("Ran out of memory while exporting. " +
                            "Allocate more memory to Minecraft and try again."),
                    Util.NIL_UUID
            );
            System.gc();
            return;
        } catch (IOException e) {
            LOGGER.error("Export failed: " + e);
            success = false;
        }

        if (!success) {
            player.sendMessage(new StringTextComponent("An error occurred when exporting the world."), Util.NIL_UUID);
        }
        System.gc();
    }

    // TODO: add to README documentation
    private static void keepRadius(String msg, ClientWorld world, ClientPlayerEntity player) {
        try {
            int newRadius = Integer.parseInt(msg.substring(CMD_RADIUS.length()).trim());
            setForceChunkRadius(newRadius);

            int chunkStorageDist;
            if (newRadius <= 32) {
                IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
                // viewDistance (a server option, unique from client render distance) is guessed to be 32 if not singleplayer
                chunkStorageDist = server == null ? 32 : server.getPlayerList().getViewDistance();
            } else {
                chunkStorageDist = newRadius;
            }
            world.getChunkSource().updateViewRadius(chunkStorageDist);

            ClientPlayNetHandler handler = Minecraft.getInstance().getConnection();
            if (handler == null) return;

            for (SUnloadChunkPacket packet : heldChunks) {
                if (isInRender(packet.getX(), packet.getZ())) continue;
                handler.handleForgetLevelChunk(packet);
            }
            heldChunks.clear();
        } catch (NumberFormatException exception) {
            player.sendMessage(new StringTextComponent("Could not update chunk radius."), Util.NIL_UUID);
        }
    }

    @SubscribeEvent
    public void onUnloadEvent(WorldEvent.Unload event) {
        heldChunks.clear();
    }

    @SubscribeEvent
    public void onClientChatEvent(ClientChatEvent event) {
        String msg = event.getOriginalMessage();

        ClientPlayerEntity player = Minecraft.getInstance().player;
        ClientWorld world = Minecraft.getInstance().level;
        if (player == null || world == null) return;

        // the following commands are client side only, so the event is canceled if the msg matches a command
        if (msg.startsWith(CMD_RADIUS)) {
            event.setCanceled(true);
            keepRadius(msg, world, player);
            return;
        }

        if (msg.startsWith(CMD_BASE)) {
            event.setCanceled(true);
            execute(msg, player);
        }
    }
}
