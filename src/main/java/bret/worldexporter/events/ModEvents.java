package bret.worldexporter.events;

import bret.worldexporter.WorldExporter;
import bret.worldexporter.commands.ExportDataCommand;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.command.ConfigCommand;

@Mod.EventBusSubscriber(modid = WorldExporter.MODID)
public class ModEvents {
    @SubscribeEvent
    public static void onCommandsRegister(RegisterCommandsEvent event){
        new ExportDataCommand(event.getDispatcher());
        ConfigCommand.register(event.getDispatcher());
    }
}
