package bret.worldexporter;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.server.permission.DefaultPermissionLevel;
import net.minecraftforge.server.permission.PermissionAPI;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.io.IOException;


@Mod(modid = WorldExporter.MODID, acceptableRemoteVersions = "*", useMetadata = true)
public class WorldExporter {
    public static final String MODID = "worldexporter";

    public static Logger LOGGER;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER = event.getModLog();
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        LOGGER.info("Loading World Exporter");
    }

    @EventHandler
    public void init(FMLPostInitializationEvent event) {
        ClientCommandHandler.instance.registerCommand(new WorldExport());
        PermissionAPI.registerNode("worldexporter.worldexport", DefaultPermissionLevel.ALL, "Dump an area of the world to disk as a Wavefront .obj file");
    }

    public static class WorldExport extends CommandBase {
        @Override
        public void execute(@Nonnull MinecraftServer server, ICommandSender sender, @Nonnull String[] params) throws CommandException {
            EntityPlayer player = sender.getEntityWorld().getPlayerEntityByName(sender.getName());
            if (player == null) {
                return;
            }

            int radius = 64;
            int lower = 0;
            int upper = 255;
            boolean optimizeMesh = true;
            boolean randomizeTextureOrientation = false;
            try {
                radius = params.length >= 1 ? Integer.parseInt(params[0]) : radius;
                lower = params.length >= 2 ? Integer.parseInt(params[1]) : lower;
                upper = params.length >= 3 ? Integer.parseInt(params[2]) : upper;
                optimizeMesh = params.length >= 4 ? Boolean.parseBoolean(params[3]) : optimizeMesh;
                randomizeTextureOrientation = params.length >= 5 ? Boolean.parseBoolean(params[4]) : randomizeTextureOrientation;
            } catch (Exception exception) {
                player.sendMessage(new TextComponentString("There was an error parsing the command arguments. " +
                        "Example usage: /" + getName() + " 64 0 255 true false")
                );
                return;
            }

            ObjExporter objExporter = new ObjExporter(player, radius, lower, upper, optimizeMesh, randomizeTextureOrientation);
            boolean success;
            try {
                success = objExporter.export("world", "world_materials");
            } catch (OutOfMemoryError e) {
                player.sendMessage(new TextComponentString("Ran out of memory while exporting. " +
                        "Allocate more memory to Minecraft and try again."));
                return;
            } catch (IOException e) {
                LOGGER.error("Export failed: " + e);
                success = false;
            }

            player.sendMessage(new TextComponentString(
                    success ? "Export successful." : "An error occurred when exporting the world."));
        }

        @Override
        @Nonnull
        public String getName() {
            return "worldexport";
        }

        @Override
        @Nonnull
        public String getUsage(@Nonnull ICommandSender sender) {
            return "command.worldexport.usage";
        }

        @Override
        public boolean checkPermission(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender) {
            return true;
        }
    }
}
