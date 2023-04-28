package bret.worldexporter.render;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderTypeLookup;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.client.renderer.model.ItemCameraTransforms;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.crash.ReportedException;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockDisplayReader;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.BitSet;
import java.util.Random;

@OnlyIn(Dist.CLIENT)
public class CustomBlockRendererDispatcher {
    private final BlockModelShapes blockModelShapes;
    private final CustomBlockModelRenderer modelRenderer;
    private final CustomFluidBlockRenderer liquidBlockRenderer;
    private final BlockColors blockColors;

    public CustomBlockRendererDispatcher(BlockModelShapes shapes, BlockColors colors) {
        this.blockModelShapes = shapes;
        this.blockColors = colors;
        this.modelRenderer = new CustomBlockModelRenderer(this.blockColors);
        this.liquidBlockRenderer = new CustomFluidBlockRenderer();
    }

    @Deprecated //Forge: Model parameter
    public boolean renderBatched(BlockState pBlockState, BlockPos pPos, IBlockDisplayReader pLightReader, MatrixStack pMatrixStack, IVertexBuilder pVertexBuilder, BitSet forceRender, Random pRand, boolean randomize) {
        return renderModel(pBlockState, pPos, pLightReader, pMatrixStack, pVertexBuilder, forceRender, pRand, net.minecraftforge.client.model.data.EmptyModelData.INSTANCE, randomize);
    }

    public boolean renderModel(BlockState blockStateIn, BlockPos posIn, IBlockDisplayReader lightReaderIn, MatrixStack matrixStackIn, IVertexBuilder vertexBuilderIn, BitSet forceRender, Random rand, net.minecraftforge.client.model.data.IModelData modelData, boolean randomize) {
        try {
            long randSeed = randomize ? blockStateIn.getSeed(posIn) : 0;
            BlockRenderType blockrendertype = blockStateIn.getRenderShape();
            return blockrendertype != BlockRenderType.MODEL ? false : this.modelRenderer.renderModel(lightReaderIn, this.getBlockModel(blockStateIn), blockStateIn, posIn, matrixStackIn, vertexBuilderIn, forceRender, rand, randSeed, OverlayTexture.NO_OVERLAY, modelData);
        } catch (Throwable throwable) {
            CrashReport crashreport = CrashReport.forThrowable(throwable, "Tesselating block in world");
            CrashReportCategory crashreportcategory = crashreport.addCategory("Block being tesselated");
            CrashReportCategory.populateBlockDetails(crashreportcategory, posIn, blockStateIn);
            throw new ReportedException(crashreport);
        }
    }

    public boolean renderLiquid(BlockPos pPos, IBlockDisplayReader pLightReader, IVertexBuilder pVertexBuilder, FluidState pFluidState, int xOff, int zOff, BitSet forceRender) {
        try {
            return this.liquidBlockRenderer.tesselate(pLightReader, pPos, pVertexBuilder, pFluidState, xOff, zOff, forceRender);
        } catch (Throwable throwable) {
            CrashReport crashreport = CrashReport.forThrowable(throwable, "Tesselating liquid in world");
            CrashReportCategory crashreportcategory = crashreport.addCategory("Block being tesselated");
            CrashReportCategory.populateBlockDetails(crashreportcategory, pPos, (BlockState) null);
            throw new ReportedException(crashreport);
        }
    }

    public IBakedModel getBlockModel(BlockState pState) {
        return this.blockModelShapes.getBlockModel(pState);
    }

    @Deprecated //Forge: Model parameter
    public void renderSingleBlock(BlockState pBlockState, MatrixStack pMatrixStack, IRenderTypeBuffer pBufferType, int pCombinedLight, int pCombinedOverlay) {
        renderBlock(pBlockState, pMatrixStack, pBufferType, pCombinedLight, pCombinedOverlay, net.minecraftforge.client.model.data.EmptyModelData.INSTANCE);
    }

    public void renderBlock(BlockState pBlockState, MatrixStack pMatrixStack, IRenderTypeBuffer pBufferType, int pCombinedLight, int pCombinedOverlay, net.minecraftforge.client.model.data.IModelData modelData) {
        BlockRenderType blockrendertype = pBlockState.getRenderShape();
        if (blockrendertype != BlockRenderType.INVISIBLE) {
            switch (blockrendertype) {
                case MODEL:
                    IBakedModel ibakedmodel = this.getBlockModel(pBlockState);
                    int i = this.blockColors.getColor(pBlockState, (IBlockDisplayReader) null, (BlockPos) null, 0);
                    float f = (float) (i >> 16 & 255) / 255.0F;
                    float f1 = (float) (i >> 8 & 255) / 255.0F;
                    float f2 = (float) (i & 255) / 255.0F;
                    this.modelRenderer.renderModel(pMatrixStack.last(), pBufferType.getBuffer(RenderTypeLookup.getRenderType(pBlockState, false)), pBlockState, ibakedmodel, f, f1, f2, pCombinedLight, pCombinedOverlay, modelData);
                    break;
                case ENTITYBLOCK_ANIMATED:
                    ItemStack stack = new ItemStack(pBlockState.getBlock());
                    stack.getItem().getItemStackTileEntityRenderer().renderByItem(stack, ItemCameraTransforms.TransformType.NONE, pMatrixStack, pBufferType, pCombinedLight, pCombinedOverlay);
            }
        }
    }
}
