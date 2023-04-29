package bret.worldexporter.util;

import net.minecraft.block.BlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.Texture;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockDisplayReader;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;

public class OptifineReflector {
    public static boolean validOptifine;
    public static MethodHandle getRenderQuads;  // BlockModelCustomizer method getRenderQuads: returns List<BakedQuad>
    public static MethodHandle getRenderEnv;  // BufferBuilder method getRenderEnv: returns RenderEnv instance
    public static MethodHandle getRenderType;  // BufferBuilder method getRenderType: returns RenderType instance
    public static Field multiTex;  // multiTex field of Texture
    public static Field multiTexNorm;  // norm field of MultiTexID (int, the glTextureId)
    public static Field multiTexSpec;  // spec field of MultiTexID (int, the glTextureId)

    public static void init() {
        boolean hasOptifineTemp = false;
        MethodHandle getRenderQuadsTemp = null;
        MethodHandle getRenderTypeTemp = null;
        MethodHandle getRenderEnvTemp = null;
        Field multiTexTemp = null;
        Field multiTexNormTemp = null;
        Field multiTexSpecTemp = null;

        try {
            getRenderQuadsTemp = Objects.requireNonNull(ReflectionHandler.getMethod(
                    "net.optifine.model.BlockModelCustomizer",
                    "getRenderQuads",
                    List.class,
                    IBlockDisplayReader.class,
                    BlockState.class,
                    BlockPos.class,
                    Direction.class,
                    RenderType.class,
                    long.class,
                    "net.optifine.render.RenderEnv")
            );
            getRenderEnvTemp = Objects.requireNonNull(ReflectionHandler.getMethod(
                    BufferBuilder.class,
                    "getRenderEnv",
                    BlockState.class,
                    BlockPos.class)
            );
            getRenderTypeTemp = Objects.requireNonNull(ReflectionHandler.getMethod(BufferBuilder.class, "getRenderType"));
            multiTexTemp = Objects.requireNonNull(ReflectionHandler.getField(Texture.class, "multiTex"));
            multiTexNormTemp = Objects.requireNonNull(ReflectionHandler.getField("net.optifine.shaders.MultiTexID", "norm"));
            multiTexSpecTemp = Objects.requireNonNull(ReflectionHandler.getField("net.optifine.shaders.MultiTexID", "spec"));
            hasOptifineTemp = true;
        } catch (Exception ignored) {
        }

        getRenderQuads = getRenderQuadsTemp;
        getRenderEnv = getRenderEnvTemp;
        getRenderType = getRenderTypeTemp;
        multiTex = multiTexTemp;
        multiTexNorm = multiTexNormTemp;
        multiTexSpec = multiTexSpecTemp;
        validOptifine = hasOptifineTemp;
    }
}
