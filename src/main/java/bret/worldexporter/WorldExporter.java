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

@Mod(modid = WorldExporter.MODID, name = WorldExporter.NAME, version = WorldExporter.VERSION, acceptableRemoteVersions = "*", useMetadata = true)
public class WorldExporter
{
    public static final String MODID = "worldexporter";
    public static final String NAME = "World Exporter";
    public static final String VERSION = "0.0.1";

    private static Logger logger;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event)
    {
        logger = event.getModLog();
    }

    @EventHandler
    public void init(FMLInitializationEvent event)
    {
        logger.info("Loading World Exporter");
    }

    @EventHandler
    public void init(FMLPostInitializationEvent event) {
        PermissionAPI.registerNode("worldexport", DefaultPermissionLevel.valueOf("ALL"), "Dump an area of the world to disk as a Wavefront .obj file");
        ClientCommandHandler.instance.registerCommand(new WorldExport());
    }

    // TODO: add support for radius as command argument
    public static class WorldExport extends CommandBase {
        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] params) throws CommandException {
             EntityPlayer player = sender.getEntityWorld().getPlayerEntityByName(sender.getName());
            if (player != null) {
                ObjExporter exporter = new ObjExporter();

                int radius = 128;
                try {
                    radius = params.length > 0 ? Integer.parseInt(params[0]) : 128;
                } catch (NumberFormatException ignored) { }

                exporter.buildObjData(player, radius);

                try {
                    exporter.exportAllData("world.obj", "world.mtl");
                } catch (IOException e) {
                    logger.error("Unable to export .obj/.mtl data");
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
    }
}
