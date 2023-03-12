package bret.worldexporter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
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

    public WorldExporter() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    private static void execute(ClientPlayerEntity player, int radius, int lower, int upper) {
        ObjExporter objExporter = new ObjExporter(player, radius, lower, upper);
        try {
            objExporter.export("world.obj", "world.mtl");
        } catch (IOException e) {
            LOGGER.error("Unable to export world data");
        }
    }

    @SubscribeEvent
    public void onClientChatEvent(ClientChatEvent event) {
        String msg = event.getOriginalMessage();
        final String cmdName = "/worldexport";
        if (!msg.startsWith(cmdName)) return;

        event.setCanceled(true);  // Client side only: don't send a message to the server
        String[] params = msg.substring(cmdName.length()).trim().split("\\s+");

        int radius = 64;
        try {
            radius = params.length > 0 ? Integer.parseInt(params[0]) : 64;
        } catch (NumberFormatException ignored) {
        }

        int lower = 0;
        int upper = 255;
        try {
            lower = params.length >= 2 ? Integer.parseInt(params[1]) : lower;
            upper = params.length >= 3 ? Integer.parseInt(params[2]) : upper;
        } catch (NumberFormatException ignored) {
        }

        execute(Minecraft.getInstance().player, radius, lower, upper);
    }
}
