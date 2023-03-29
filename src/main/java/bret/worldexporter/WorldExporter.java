package bret.worldexporter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.util.Util;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

@Mod(WorldExporter.MODID)
public class WorldExporter {
    public static final String MODID = "worldexporter";
    private static final Logger LOGGER = LogManager.getLogger(WorldExporter.MODID);
    private static final String cmdName = "/worldexport";

    public WorldExporter() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    private static void execute(ClientPlayerEntity player, int radius, int lower, int upper, boolean optimizeMesh, boolean randomize, int threads) {
        ObjExporter objExporter = new ObjExporter(player, radius, lower, upper, optimizeMesh, randomize, threads);
        try {
            objExporter.export("world.obj", "world.mtl");
        } catch (IOException e) {
            LOGGER.error("Unable to export world data");
            Minecraft.getInstance().player.sendMessage(
                    new StringTextComponent("An error occurred exporting the world."), Util.NIL_UUID);
        }
    }

    @SubscribeEvent
    public void onClientChatEvent(ClientChatEvent event) {
        String msg = event.getOriginalMessage();
        if (!msg.startsWith(cmdName)) return;

        event.setCanceled(true);  // Client side only: don't send a message to the server
        String[] params = msg.substring(cmdName.length()).trim().split("\\s+");

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
        } catch (Exception ignored) {
            Minecraft.getInstance().player.sendMessage(
                    new StringTextComponent("There was an error parsing the command arguments. " +
                            "Example usage: " + cmdName + " 64 0 255 true false 4"),
                    Util.NIL_UUID
            );
            return;
        }

        threads = Math.max(1, Math.min(8, threads));

        execute(Minecraft.getInstance().player, radius, lower, upper, optimizeMesh, randomizeTextureOrientation, threads);
    }
}
