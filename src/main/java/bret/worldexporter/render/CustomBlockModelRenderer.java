package bret.worldexporter.render;

import bret.worldexporter.Exporter;
import bret.worldexporter.util.OptifineReflector;
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
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.IBlockDisplayReader;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

@OnlyIn(Dist.CLIENT)
public class CustomBlockModelRenderer {
    private final BlockColors blockColors;

    public CustomBlockModelRenderer(BlockColors p_i46575_1_) {
        this.blockColors = p_i46575_1_;
    }

    private static void renderQuadList(MatrixStack.Entry pMatrixEntry, IVertexBuilder pBuffer, float pRed, float pGreen, float pBlue, List<BakedQuad> pListQuads, int pCombinedLight, int pCombinedOverlay) {
        for (BakedQuad bakedquad : pListQuads) {
            float f;
            float f1;
            float f2;
            if (bakedquad.isTinted()) {
                f = MathHelper.clamp(pRed, 0.0F, 1.0F);
                f1 = MathHelper.clamp(pGreen, 0.0F, 1.0F);
                f2 = MathHelper.clamp(pBlue, 0.0F, 1.0F);
            } else {
                f = 1.0F;
                f1 = 1.0F;
                f2 = 1.0F;
            }

            pBuffer.putBulkData(pMatrixEntry, bakedquad, f, f1, f2, pCombinedLight, pCombinedOverlay);
        }
    }

    @Deprecated //Forge: Model data argument
    public boolean tesselateBlock(IBlockDisplayReader pLevel, IBakedModel pModel, BlockState pState, BlockPos pPos, MatrixStack pMatrix, IVertexBuilder pBuffer, BitSet forceRender, Random pRandom, long pRand, int pCombinedOverlay) {
        return renderModel(pLevel, pModel, pState, pPos, pMatrix, pBuffer, forceRender, pRandom, pRand, pCombinedOverlay, net.minecraftforge.client.model.data.EmptyModelData.INSTANCE);
    }

    public boolean renderModel(IBlockDisplayReader worldIn, IBakedModel modelIn, BlockState stateIn, BlockPos posIn, MatrixStack matrixIn, IVertexBuilder buffer, BitSet forceRender, Random randomIn, long rand, int combinedOverlayIn, net.minecraftforge.client.model.data.IModelData modelData) {
        Vector3d vector3d = stateIn.getOffset(worldIn, posIn);
        matrixIn.translate(vector3d.x, vector3d.y, vector3d.z);
        modelData = modelIn.getModelData(worldIn, posIn, stateIn, modelData);

        try {
            return this.renderModelFlat(worldIn, modelIn, stateIn, posIn, matrixIn, buffer, forceRender, randomIn, rand, combinedOverlayIn, modelData);

            // optifine calls renderOverlayModels here, not sure what this does.
//            if (rendered) {
//                this.renderOverlayModels(worldIn, modelIn, stateIn, posIn, matrixIn, buffer, combinedOverlayIn, checkSides, randomIn, rand, renderEnv, flag, vector3d);
//            }
        } catch (Throwable throwable) {
            CrashReport crashreport = CrashReport.forThrowable(throwable, "Tesselating block model");
            CrashReportCategory crashreportcategory = crashreport.addCategory("Block model being tesselated");
            CrashReportCategory.populateBlockDetails(crashreportcategory, posIn, stateIn);
            crashreportcategory.setDetail("Using AO", false);
            throw new ReportedException(crashreport);
        }
    }

    @SuppressWarnings("unchecked")
    public boolean renderModelFlat(IBlockDisplayReader worldIn, IBakedModel modelIn, BlockState stateIn, BlockPos posIn, MatrixStack matrixStackIn, IVertexBuilder buffer, BitSet forceRender, Random randomIn, long rand, int combinedOverlayIn, net.minecraftforge.client.model.data.IModelData modelData) {
        boolean flag = false;
        BitSet bitset = new BitSet(3);

        // optifine
        boolean useOptifine = false;
        Object renderEnv = null;
        Object layer = null;
        if (OptifineReflector.validOptifine && OptifineReflector.isConnectedTextures) {
            try {
                // RenderEnv renderEnv = buffer.getRenderEnv(stateIn, posIn);
                // RenderType layer = buffer.getRenderType();
                renderEnv = OptifineReflector.getRenderEnv.invoke(buffer, stateIn, posIn);
                layer = OptifineReflector.getRenderType.invoke(buffer);
                useOptifine = true;
            } catch (Throwable e) {
                Exporter.LOGGER.warn("Unable to get renderEnv/layer info even though Optifine is valid", e);
            }
        }

        for (Direction direction : Direction.values()) {
            randomIn.setSeed(rand);
            List<BakedQuad> list = modelIn.getQuads(stateIn, direction, randomIn, modelData);
            if (!list.isEmpty() && (Exporter.isForced(forceRender, direction) || Block.shouldRenderFace(stateIn, worldIn, posIn, direction))) {
                int i = WorldRenderer.getLightColor(worldIn, stateIn, posIn.relative(direction));
                if (useOptifine) {
                    try {
                        list = (List<BakedQuad>) OptifineReflector.getRenderQuads.invoke(list, worldIn, stateIn, posIn, direction, layer, rand, renderEnv);
                    } catch (Throwable e) {
                        Exporter.LOGGER.warn("Unable to get modified quad list even though Optifine is valid", e);
                    }
                }
                this.renderModelFaceFlat(worldIn, stateIn, posIn, i, combinedOverlayIn, false, matrixStackIn, buffer, list, bitset);
                flag = true;
            }
        }

        randomIn.setSeed(rand);
        List<BakedQuad> list1 = modelIn.getQuads(stateIn, (Direction) null, randomIn, modelData);
        if (!list1.isEmpty()) {
            if (useOptifine) {
                try {
                    list1 = (List<BakedQuad>) OptifineReflector.getRenderQuads.invoke(list1, worldIn, stateIn, posIn, (Direction) null, layer, rand, renderEnv);
                } catch (Throwable e) {
                    Exporter.LOGGER.warn("Unable to get modified quad list even though Optifine is valid", e);
                }
            }
            this.renderModelFaceFlat(worldIn, stateIn, posIn, -1, combinedOverlayIn, true, matrixStackIn, buffer, list1, bitset);
            flag = true;
        }

        return flag;
    }

    private void putQuadData(IBlockDisplayReader pBlockAccess, BlockState pState, BlockPos pPos, IVertexBuilder pBuffer, MatrixStack.Entry pMatrixEntry, BakedQuad pQuad, float pColorMul0, float pColorMul1, float pColorMul2, float pColorMul3, int pBrightness0, int pBrightness1, int pBrightness2, int pBrightness3, int pCombinedOverlay) {
        float f;
        float f1;
        float f2;
        if (pQuad.isTinted()) {
            int i = this.blockColors.getColor(pState, pBlockAccess, pPos, pQuad.getTintIndex());
            f = (float) (i >> 16 & 255) / 255.0F;
            f1 = (float) (i >> 8 & 255) / 255.0F;
            f2 = (float) (i & 255) / 255.0F;
        } else {
            f = 1.0F;
            f1 = 1.0F;
            f2 = 1.0F;
        }

        pBuffer.putBulkData(pMatrixEntry, pQuad, new float[]{pColorMul0, pColorMul1, pColorMul2, pColorMul3}, f, f1, f2, new int[]{pBrightness0, pBrightness1, pBrightness2, pBrightness3}, pCombinedOverlay, true);
    }

    private void calculateShape(IBlockDisplayReader pBlockReader, BlockState pState, BlockPos pPos, int[] pVertexData, Direction pFace, @Nullable float[] pQuadBounds, BitSet pBoundsFlags) {
        float f = 32.0F;
        float f1 = 32.0F;
        float f2 = 32.0F;
        float f3 = -32.0F;
        float f4 = -32.0F;
        float f5 = -32.0F;

        for (int i = 0; i < 4; ++i) {
            float f6 = Float.intBitsToFloat(pVertexData[i * 8]);
            float f7 = Float.intBitsToFloat(pVertexData[i * 8 + 1]);
            float f8 = Float.intBitsToFloat(pVertexData[i * 8 + 2]);
            f = Math.min(f, f6);
            f1 = Math.min(f1, f7);
            f2 = Math.min(f2, f8);
            f3 = Math.max(f3, f6);
            f4 = Math.max(f4, f7);
            f5 = Math.max(f5, f8);
        }

        if (pQuadBounds != null) {
            pQuadBounds[Direction.WEST.get3DDataValue()] = f;
            pQuadBounds[Direction.EAST.get3DDataValue()] = f3;
            pQuadBounds[Direction.DOWN.get3DDataValue()] = f1;
            pQuadBounds[Direction.UP.get3DDataValue()] = f4;
            pQuadBounds[Direction.NORTH.get3DDataValue()] = f2;
            pQuadBounds[Direction.SOUTH.get3DDataValue()] = f5;
            int j = Direction.values().length;
            pQuadBounds[Direction.WEST.get3DDataValue() + j] = 1.0F - f;
            pQuadBounds[Direction.EAST.get3DDataValue() + j] = 1.0F - f3;
            pQuadBounds[Direction.DOWN.get3DDataValue() + j] = 1.0F - f1;
            pQuadBounds[Direction.UP.get3DDataValue() + j] = 1.0F - f4;
            pQuadBounds[Direction.NORTH.get3DDataValue() + j] = 1.0F - f2;
            pQuadBounds[Direction.SOUTH.get3DDataValue() + j] = 1.0F - f5;
        }

        float f9 = 1.0E-4F;
        float f10 = 0.9999F;
        switch (pFace) {
            case DOWN:
                pBoundsFlags.set(1, f >= 1.0E-4F || f2 >= 1.0E-4F || f3 <= 0.9999F || f5 <= 0.9999F);
                pBoundsFlags.set(0, f1 == f4 && (f1 < 1.0E-4F || pState.isCollisionShapeFullBlock(pBlockReader, pPos)));
                break;
            case UP:
                pBoundsFlags.set(1, f >= 1.0E-4F || f2 >= 1.0E-4F || f3 <= 0.9999F || f5 <= 0.9999F);
                pBoundsFlags.set(0, f1 == f4 && (f4 > 0.9999F || pState.isCollisionShapeFullBlock(pBlockReader, pPos)));
                break;
            case NORTH:
                pBoundsFlags.set(1, f >= 1.0E-4F || f1 >= 1.0E-4F || f3 <= 0.9999F || f4 <= 0.9999F);
                pBoundsFlags.set(0, f2 == f5 && (f2 < 1.0E-4F || pState.isCollisionShapeFullBlock(pBlockReader, pPos)));
                break;
            case SOUTH:
                pBoundsFlags.set(1, f >= 1.0E-4F || f1 >= 1.0E-4F || f3 <= 0.9999F || f4 <= 0.9999F);
                pBoundsFlags.set(0, f2 == f5 && (f5 > 0.9999F || pState.isCollisionShapeFullBlock(pBlockReader, pPos)));
                break;
            case WEST:
                pBoundsFlags.set(1, f1 >= 1.0E-4F || f2 >= 1.0E-4F || f4 <= 0.9999F || f5 <= 0.9999F);
                pBoundsFlags.set(0, f == f3 && (f < 1.0E-4F || pState.isCollisionShapeFullBlock(pBlockReader, pPos)));
                break;
            case EAST:
                pBoundsFlags.set(1, f1 >= 1.0E-4F || f2 >= 1.0E-4F || f4 <= 0.9999F || f5 <= 0.9999F);
                pBoundsFlags.set(0, f == f3 && (f3 > 0.9999F || pState.isCollisionShapeFullBlock(pBlockReader, pPos)));
        }

    }

    private void renderModelFaceFlat(IBlockDisplayReader pBlockAccess, BlockState pState, BlockPos pPos, int pBrightness, int pCombinedOverlay, boolean pOwnBrightness, MatrixStack pMatrixStack, IVertexBuilder pBuffer, List<BakedQuad> pList, BitSet pBitSet) {
        for (BakedQuad bakedquad : pList) {
            if (pOwnBrightness) {
                this.calculateShape(pBlockAccess, pState, pPos, bakedquad.getVertices(), bakedquad.getDirection(), (float[]) null, pBitSet);
                BlockPos blockpos = pBitSet.get(0) ? pPos.relative(bakedquad.getDirection()) : pPos;
                pBrightness = WorldRenderer.getLightColor(pBlockAccess, pState, blockpos);
            }

            // float f = pBlockAccess.getShade(bakedquad.getDirection(), bakedquad.isShade());
            float f = 1.0F; // force diffuse lighting off
            this.putQuadData(pBlockAccess, pState, pPos, pBuffer, pMatrixStack.last(), bakedquad, f, f, f, f, pBrightness, pBrightness, pBrightness, pBrightness, pCombinedOverlay);
        }
    }

    @Deprecated //Forge: Model data argument
    public void renderModel(MatrixStack.Entry pMatrixEntry, IVertexBuilder pBuffer, @Nullable BlockState pState, IBakedModel pModel, float pRed, float pGreen, float pBlue, int pCombinedLight, int pCombinedOverlay) {
        renderModel(pMatrixEntry, pBuffer, pState, pModel, pRed, pGreen, pBlue, pCombinedLight, pCombinedOverlay, net.minecraftforge.client.model.data.EmptyModelData.INSTANCE);
    }

    public void renderModel(MatrixStack.Entry pMatrixEntry, IVertexBuilder pBuffer, @Nullable BlockState pState, IBakedModel pModel, float pRed, float pGreen, float pBlue, int pCombinedLight, int pCombinedOverlay, net.minecraftforge.client.model.data.IModelData modelData) {
        Random random = new Random();
        long i = 42L;

        for (Direction direction : Direction.values()) {
            random.setSeed(42L);
            renderQuadList(pMatrixEntry, pBuffer, pRed, pGreen, pBlue, pModel.getQuads(pState, direction, random, modelData), pCombinedLight, pCombinedOverlay);
        }

        random.setSeed(42L);
        renderQuadList(pMatrixEntry, pBuffer, pRed, pGreen, pBlue, pModel.getQuads(pState, (Direction) null, random, modelData), pCombinedLight, pCombinedOverlay);
    }
}
