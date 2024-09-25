//package bret.worldexporter;
//
//import net.minecraft.client.Minecraft;
//import net.minecraftforge.api.distmarker.Dist;
//import net.minecraftforge.eventbus.api.SubscribeEvent;
//import net.minecraftforge.fml.common.Mod;
//import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
//
//import static bret.worldexporter.WorldExporter.LOGGER;
//
//@Mod.EventBusSubscriber(modid = WorldExporter.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
//public class Dev {
//    // For gathering the info to log into the dev environment with
//    @SubscribeEvent
//    public static void onFMLClientSetupEvent(final FMLClientSetupEvent event) {
//        LOGGER.error("ACCESS_TOKEN "+ Minecraft.getInstance().getUser().getAccessToken());
//        System.out.println("ACCESS_TOKEN "+ Minecraft.getInstance().getUser().getAccessToken());
//        LOGGER.error("UUID "+ Minecraft.getInstance().getUser().getUuid());
//        System.out.println("UUID "+ Minecraft.getInstance().getUser().getUuid());
//    }
//}
