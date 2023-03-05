package bret.worldexporter;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;

// TODO: use ClientChatEvent to allow the mod to function client-only
@Mod(WorldExporter.MODID)
public class WorldExporter {
    public static final String MODID = "worldexporter";
    private static Logger logger;

    @Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class RegistryEvents {
        @SubscribeEvent
        public static void onServerStarting(FMLServerStartingEvent event) {
            logger = LogManager.getLogger();
            WorldExport.register(event.getCommandDispatcher());
        }
    }

    public static class WorldExport {
        public static void register(CommandDispatcher<CommandSource> dispatcher) {
            dispatcher.register(
                    Commands.literal("worldexport")
                            .then(Commands.argument("radius", integer(1)))
                            .then(Commands.argument("lower", integer(0)))
                            .then(Commands.argument("upper", integer(0, 255)))
                                .executes(context -> execute(
                                        context.getSource().asPlayer(),
                                        IntegerArgumentType.getInteger(context, "radius"),
                                        IntegerArgumentType.getInteger(context, "lower"),
                                        IntegerArgumentType.getInteger(context, "upper")
                                ))
            );
        }

        public static int execute(ServerPlayerEntity player, int radius, int lower, int upper) {
            if (player == null) return 1;

            ObjExporter objExporter = new ObjExporter(player, radius, lower, upper);
            try {
                objExporter.export("world.obj", "world.mtl");
            } catch (IOException e) {
                logger.error("Unable to export world data");
                return 1;
            }
            return 0;
        }
    }
}
