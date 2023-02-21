package bret.worldexporter;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.server.permission.DefaultPermissionLevel;
import net.minecraftforge.server.permission.PermissionAPI;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

@Mod(modid = WorldExporter.MODID, acceptableRemoteVersions = "*", useMetadata = true)
public class WorldExporter {
    public static final String MODID = "worldexporter";

    private static Logger logger;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        logger.info("Loading World Exporter");
    }

    @EventHandler
    public void init(FMLPostInitializationEvent event) {
        ClientCommandHandler.instance.registerCommand(new WorldExport());
        PermissionAPI.registerNode("worldexporter.worldexport", DefaultPermissionLevel.ALL, "Dump an area of the world to disk as a Wavefront .obj file");
    }

    public static class WorldExport extends CommandBase {
        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] params) throws CommandException {
            EntityPlayer player = sender.getEntityWorld().getPlayerEntityByName(sender.getName());
            if (player != null) {
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

                ObjExporter objExporter = new ObjExporter(player, radius, lower, upper);
                try {
                    objExporter.export("world.obj", "world.mtl");
                } catch (IOException e) {
                    logger.error("Unable to export world data");
                }
            }
        }

        @Override
        public String getName() {
            return "worldexport";
        }

        @Override
        public String getUsage(ICommandSender sender) {
            return "command.worldexport.usage";
        }

        @Override
        public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
            return true;
        }
    }
}
