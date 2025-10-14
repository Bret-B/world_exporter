package bret.worldexporter.commands;

import bret.worldexporter.networking.packets.PacketHandler;
import bret.worldexporter.networking.packets.serverout.SInvokeExport;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;

public class ExportDataCommand {
    public ExportDataCommand(CommandDispatcher<CommandSource> dispatcher){
        // fugly API
        dispatcher.register(Commands.literal("worldexport")
                .then(Commands.literal("asMesh")
                        .then(Commands.argument("blockRadius", IntegerArgumentType.integer()).executes((ctx) ->{
                            SInvokeExport export = new SInvokeExport();
                            export.minYLevel = 0;
                            export.maxYLevel = 255;
                            export.threadCount = 4;
                            export.optimizeMesh = true;
                            export.blockRadius = IntegerArgumentType.getInteger(ctx,"blockRadius");
                            PacketHandler.sendToPlayer(ctx.getSource().getPlayerOrException(), export);
                            return 1;
                        }))));
    }
}
