package bret.worldexporter;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.client.renderer.model.BakedQuad;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.crash.ReportedException;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.ILightReader;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

@OnlyIn(Dist.CLIENT)
public class CustomBlockModelRenderer {
    private final BlockColors blockColors;

    public CustomBlockModelRenderer(BlockColors blockColorsIn) {
        this.blockColors = blockColorsIn;
    }

    public boolean renderModel(ILightReader worldIn, IBakedModel modelIn, BlockState stateIn, BlockPos posIn, MatrixStack matrixIn, IVertexBuilder buffer, boolean checkSides, Random randomIn, long rand, int combinedOverlayIn, net.minecraftforge.client.model.data.IModelData modelData) {
        Vec3d vec3d = stateIn.getOffset(worldIn, posIn);
        matrixIn.translate(vec3d.x, vec3d.y, vec3d.z);
        modelData = modelIn.getModelData(worldIn, posIn, stateIn, modelData);

        try {
            return this.renderModelFlat(worldIn, modelIn, stateIn, posIn, matrixIn, buffer, checkSides, randomIn, rand, combinedOverlayIn, modelData);
        } catch (Throwable throwable) {
            CrashReport crashreport = CrashReport.makeCrashReport(throwable, "Tesselating block model");
            CrashReportCategory crashreportcategory = crashreport.makeCategory("Block model being tesselated");
            CrashReportCategory.addBlockInfo(crashreportcategory, posIn, stateIn);
            crashreportcategory.addDetail("Using AO", false);
            throw new ReportedException(crashreport);
        }
    }

    public boolean renderModelFlat(ILightReader worldIn, IBakedModel modelIn, BlockState stateIn, BlockPos posIn, MatrixStack matrixStackIn, IVertexBuilder buffer, boolean checkSides, Random randomIn, long rand, int combinedOverlayIn, net.minecraftforge.client.model.data.IModelData modelData) {
        boolean flag = false;
        BitSet bitset = new BitSet(3);

        for (Direction direction : Direction.values()) {
            randomIn.setSeed(rand);
            List<BakedQuad> list = modelIn.getQuads(stateIn, direction, randomIn, modelData);
            if (!list.isEmpty() && (!checkSides || Block.shouldSideBeRendered(stateIn, worldIn, posIn, direction))) {
                int i = WorldRenderer.getPackedLightmapCoords(worldIn, stateIn, posIn.offset(direction));
                this.renderQuadsFlat(worldIn, stateIn, posIn, i, combinedOverlayIn, false, matrixStackIn, buffer, list, bitset);
                flag = true;
            }
        }

        randomIn.setSeed(rand);
        List<BakedQuad> list1 = modelIn.getQuads(stateIn, (Direction) null, randomIn, modelData);
        if (!list1.isEmpty()) {
            this.renderQuadsFlat(worldIn, stateIn, posIn, -1, combinedOverlayIn, true, matrixStackIn, buffer, list1, bitset);
            flag = true;
        }

        return flag;
    }

    private void renderQuadSmooth(ILightReader blockAccessIn, BlockState stateIn, BlockPos posIn, IVertexBuilder buffer, MatrixStack.Entry matrixEntry, BakedQuad quadIn, float colorMul0, float colorMul1, float colorMul2, float colorMul3, int brightness0, int brightness1, int brightness2, int brightness3, int combinedOverlayIn) {
        float f;
        float f1;
        float f2;
        if (quadIn.hasTintIndex()) {
            int i = this.blockColors.getColor(stateIn, blockAccessIn, posIn, quadIn.getTintIndex());
            f = (float) (i >> 16 & 255) / 255.0F;
            f1 = (float) (i >> 8 & 255) / 255.0F;
            f2 = (float) (i & 255) / 255.0F;
        } else {
            f = 1.0F;
            f1 = 1.0F;
            f2 = 1.0F;
        }
        // FORGE: Apply diffuse lighting at render-time instead of baking it in
//        if (quadIn.shouldApplyDiffuseLighting()) {
//            float l = net.minecraftforge.client.model.pipeline.LightUtil.diffuseLight(quadIn.getFace());
//            f *= l;
//            f1 *= l;
//            f2 *= l;
//        }

        buffer.addQuad(matrixEntry, quadIn, new float[]{colorMul0, colorMul1, colorMul2, colorMul3}, f, f1, f2, new int[]{brightness0, brightness1, brightness2, brightness3}, combinedOverlayIn, true);
    }

    private void fillQuadBounds(ILightReader blockReaderIn, BlockState stateIn, BlockPos posIn, int[] vertexData, Direction face, @Nullable float[] quadBounds, BitSet boundsFlags) {
        float f = 32.0F;
        float f1 = 32.0F;
        float f2 = 32.0F;
        float f3 = -32.0F;
        float f4 = -32.0F;
        float f5 = -32.0F;

        for (int i = 0; i < 4; ++i) {
            float f6 = Float.intBitsToFloat(vertexData[i * 8]);
            float f7 = Float.intBitsToFloat(vertexData[i * 8 + 1]);
            float f8 = Float.intBitsToFloat(vertexData[i * 8 + 2]);
            f = Math.min(f, f6);
            f1 = Math.min(f1, f7);
            f2 = Math.min(f2, f8);
            f3 = Math.max(f3, f6);
            f4 = Math.max(f4, f7);
            f5 = Math.max(f5, f8);
        }

        if (quadBounds != null) {
            quadBounds[Direction.WEST.getIndex()] = f;
            quadBounds[Direction.EAST.getIndex()] = f3;
            quadBounds[Direction.DOWN.getIndex()] = f1;
            quadBounds[Direction.UP.getIndex()] = f4;
            quadBounds[Direction.NORTH.getIndex()] = f2;
            quadBounds[Direction.SOUTH.getIndex()] = f5;
            int j = Direction.values().length;
            quadBounds[Direction.WEST.getIndex() + j] = 1.0F - f;
            quadBounds[Direction.EAST.getIndex() + j] = 1.0F - f3;
            quadBounds[Direction.DOWN.getIndex() + j] = 1.0F - f1;
            quadBounds[Direction.UP.getIndex() + j] = 1.0F - f4;
            quadBounds[Direction.NORTH.getIndex() + j] = 1.0F - f2;
            quadBounds[Direction.SOUTH.getIndex() + j] = 1.0F - f5;
        }

        float f9 = 1.0E-4F;
        float f10 = 0.9999F;
        switch (face) {
            case DOWN:
                boundsFlags.set(1, f >= 1.0E-4F || f2 >= 1.0E-4F || f3 <= 0.9999F || f5 <= 0.9999F);
                boundsFlags.set(0, f1 == f4 && (f1 < 1.0E-4F || stateIn.isCollisionShapeOpaque(blockReaderIn, posIn)));
                break;
            case UP:
                boundsFlags.set(1, f >= 1.0E-4F || f2 >= 1.0E-4F || f3 <= 0.9999F || f5 <= 0.9999F);
                boundsFlags.set(0, f1 == f4 && (f4 > 0.9999F || stateIn.isCollisionShapeOpaque(blockReaderIn, posIn)));
                break;
            case NORTH:
                boundsFlags.set(1, f >= 1.0E-4F || f1 >= 1.0E-4F || f3 <= 0.9999F || f4 <= 0.9999F);
                boundsFlags.set(0, f2 == f5 && (f2 < 1.0E-4F || stateIn.isCollisionShapeOpaque(blockReaderIn, posIn)));
                break;
            case SOUTH:
                boundsFlags.set(1, f >= 1.0E-4F || f1 >= 1.0E-4F || f3 <= 0.9999F || f4 <= 0.9999F);
                boundsFlags.set(0, f2 == f5 && (f5 > 0.9999F || stateIn.isCollisionShapeOpaque(blockReaderIn, posIn)));
                break;
            case WEST:
                boundsFlags.set(1, f1 >= 1.0E-4F || f2 >= 1.0E-4F || f4 <= 0.9999F || f5 <= 0.9999F);
                boundsFlags.set(0, f == f3 && (f < 1.0E-4F || stateIn.isCollisionShapeOpaque(blockReaderIn, posIn)));
                break;
            case EAST:
                boundsFlags.set(1, f1 >= 1.0E-4F || f2 >= 1.0E-4F || f4 <= 0.9999F || f5 <= 0.9999F);
                boundsFlags.set(0, f == f3 && (f3 > 0.9999F || stateIn.isCollisionShapeOpaque(blockReaderIn, posIn)));
        }

    }

    private void renderQuadsFlat(ILightReader blockAccessIn, BlockState stateIn, BlockPos posIn, int brightnessIn, int combinedOverlayIn, boolean ownBrightness, MatrixStack matrixStackIn, IVertexBuilder buffer, List<BakedQuad> list, BitSet bitSet) {
        for (BakedQuad bakedquad : list) {
            if (ownBrightness) {
                this.fillQuadBounds(blockAccessIn, stateIn, posIn, bakedquad.getVertexData(), bakedquad.getFace(), (float[]) null, bitSet);
                BlockPos blockpos = bitSet.get(0) ? posIn.offset(bakedquad.getFace()) : posIn;
                brightnessIn = WorldRenderer.getPackedLightmapCoords(blockAccessIn, stateIn, blockpos);
            }

            this.renderQuadSmooth(blockAccessIn, stateIn, posIn, buffer, matrixStackIn.getLast(), bakedquad, 1.0F, 1.0F, 1.0F, 1.0F, brightnessIn, brightnessIn, brightnessIn, brightnessIn, combinedOverlayIn);
        }

    }
}

