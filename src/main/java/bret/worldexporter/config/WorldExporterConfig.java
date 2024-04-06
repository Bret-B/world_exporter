package bret.worldexporter.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.BooleanValue;
import net.minecraftforge.common.ForgeConfigSpec.DoubleValue;
import net.minecraftforge.common.ForgeConfigSpec.EnumValue;
import net.minecraftforge.common.ForgeConfigSpec.IntValue;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.commons.lang3.tuple.Pair;


// See https://github.com/Choonster-Minecraft-Mods/TestMod3/blob/1.16.x/src/main/java/choonster/testmod3/config/TestMod3Config.java
// and https://docs.minecraftforge.net/en/1.19.x/misc/config/
public class WorldExporterConfig {
    public static final Client CLIENT;
    private static final ForgeConfigSpec clientSpec;

    static {
        final Pair<Client, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder().configure(Client::new);
        clientSpec = specPair.getRight();
        CLIENT = specPair.getLeft();
    }

    public static void register(final ModLoadingContext context) {
        context.registerConfig(ModConfig.Type.CLIENT, clientSpec);
    }

    public enum ChunkExportType {
        SINGLE_FILE_SINGLE_OBJECT,
        SINGLE_FILE_MULTIPLE_OBJECTS,
        MULTIPLE_FILES
    }

    public enum NormalFormat {
        OPENGL,
        DIRECTX
    }

    public static class Client {
        public final EnumValue<ChunkExportType> chunkExportType;
        public final EnumValue<NormalFormat> normalFormat;
        public final BooleanValue enableEntities;
        public final BooleanValue enableLivingEntities;
        public final BooleanValue relativeCoordinates;
        public final BooleanValue outputHeightmap;
        public final BooleanValue outputAmbientocclusionMap;
        public final BooleanValue perceptualRoughness;
        public final BooleanValue squareEmissivity;
        public final BooleanValue forceResourceEmissivity;
        public final BooleanValue forceOutputUniformMaps;
        public final BooleanValue outputInvisibleFaces;
        public final DoubleValue normalStrength;
        public final DoubleValue overlapDistance;
        public final BooleanValue exportVisibleExteriorOnly;
        public final IntValue maxVisibilityPathLength;
        public final BooleanValue segmentedExteriorPathfinding;
        public final IntValue segmentChunkRadius;

        Client(final ForgeConfigSpec.Builder builder) {
            builder.comment("Client-only settings").push("client");

            chunkExportType = builder
                    .comment("The chunk mode the exporter should use for outputting OBJ files")
                    .translation("worldexporter.config.client.chunkExportType")
                    .defineEnum("chunkExportType", ChunkExportType.SINGLE_FILE_SINGLE_OBJECT);

            relativeCoordinates = builder
                    .comment("Export coordinates/chunk names relative to the player's position")
                    .translation("worldexporter.config.client.relativeCoordinates")
                    .define("relativeCoordinates", true);

            enableEntities = builder
                    .comment("Export entities (chests, beds, paintings, mobs, etc.)")
                    .translation("worldexporter.config.client.enableEntities")
                    .define("enableEntities", true);

            enableLivingEntities = builder
                    .comment("Export living entities (mobs, players, armor stands). Has no effect if exporting entities is disabled")
                    .translation("worldexporter.config.client.enableLivingEntities")
                    .define("enableLivingEntities", true);

            normalFormat = builder
                    .comment("Whether to use OpenGL (more common) or DirectX normal map formats")
                    .translation("worldexporter.config.client.normalFormat")
                    .defineEnum("normalFormat", NormalFormat.OPENGL);

            normalStrength = builder
                    .comment("The strength to be used for normal maps")
                    .translation("worldexporter.config.client.normalStrength")
                    .defineInRange("normalStrength", 1.0, 0.0, 10.0);

            outputHeightmap = builder
                    .comment("Export height maps")
                    .translation("worldexporter.config.client.outputHeightmap")
                    .define("outputHeightmap", false);

            outputAmbientocclusionMap = builder
                    .comment("Export ambient occlusion maps")
                    .translation("worldexporter.config.client.outputAmbientocclusionMap")
                    .define("outputAmbientocclusionMap", false);

            perceptualRoughness = builder
                    .comment("Export roughness maps using a perceptual/linear scale (enable for Blender)")
                    .translation("worldexporter.config.client.perceptualRoughness")
                    .define("perceptualRoughness", true);

            forceResourceEmissivity = builder
                    .comment("Always use the emissivity map from the resource pack when available (recommended value: on/true)")
                    .translation("worldexporter.config.client.forceResourceEmissivity")
                    .define("forceResourceEmissivity", true);

            squareEmissivity = builder
                    .comment("Square emissivity values when exporting, which can help differentiate between lower and higher emissivity values")
                    .translation("worldexporter.config.client.squareEmissivity")
                    .define("squareEmissivity", false);

            forceOutputUniformMaps = builder
                    .comment("Always export a grayscale texture image instead of a numeric value even if the image is uniform. Applies to roughness and metallic maps")
                    .translation("worldexporter.config.client.forceOutputUniformMaps")
                    .define("forceOutputUniformMaps", false);

            overlapDistance = builder
                    .comment("At this distance or smaller, faces are considered to overlap and therefore need to be offset from each other to avoid z-fighting." +
                            " Raise this if you experience z-fighting problems, but keep it as low as possible")
                    .translation("worldexporter.config.client.overlapDistance")
                    .defineInRange("overlapDistance", 0.0005, 0.0, 0.1);

            outputInvisibleFaces = builder
                    .comment("Enable to always output faces that are completely invisible/transparent")
                    .translation("worldexporter.config.client.outputInvisibleFaces")
                    .define("outputInvisibleFaces", true);

            exportVisibleExteriorOnly = builder
                    .comment("Enable to output only faces that are visible from the outside of the export." +
                            " For example, turning this on will eliminate the majority of caves and other underground" +
                            " structures dependent on the max path length defined with maxVisibilityPathLength." +
                            " Turning this on can greatly reduce the polygon count.")
                    .translation("worldexporter.config.client.exportVisibleExteriorOnly")
                    .define("exportVisibleExteriorOnly", false);

            maxVisibilityPathLength = builder
                    .comment("This number defines the max length of the path to skylight or the edge of the export." +
                            " This setting only applies when exportVisibleExteriorOnly=true." +
                            " Values higher than ((segmentChunkRadius + 1) * 16) might have less of an effect when segmentedExteriorPathfinding is enabled.")
                    .translation("worldexporter.config.client.maxVisibilityPathLength")
                    .defineInRange("maxVisibilityPathLength", 48, 1, Integer.MAX_VALUE);

            segmentedExteriorPathfinding = builder
                    .comment("Enable to perform segmented pathfinding for exterior visibility." +
                            " This can be significantly faster since it can be done with multiple threads," +
                            " and will also require less memory for large exports." +
                            " It may reduce effectiveness for high values of maxVisibilityPathLength." +
                            " This setting only applies when exportVisibleExteriorOnly=true.")
                    .translation("worldexporter.config.client.segmentedExteriorPathfinding")
                    .define("segmentedExteriorPathfinding", false);

            segmentChunkRadius = builder
                    .comment("The radius (in chunks) to do visibility pathfinding in when segmentedExteriorPathfinding=true." +
                            " Lower numbers will result in increased speed but less accuracy, especially if maxVisibilityPathLength is high.")
                    .translation("worldexporter.config.client.segmentChunkRadius")
                    .defineInRange("segmentChunkRadius", 1, 0, 4);

            builder.pop();
        }
    }
}
