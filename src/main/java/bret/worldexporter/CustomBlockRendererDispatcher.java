package bret.worldexporter;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.crash.ReportedException;
import net.minecraft.fluid.IFluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ILightReader;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.BitSet;
import java.util.Random;

@OnlyIn(Dist.CLIENT)
public class CustomBlockRendererDispatcher {
    private final BlockModelShapes blockModelShapes;
    private final CustomBlockModelRenderer blockModelRenderer;
    private final CustomFluidBlockRenderer fluidRenderer;

    public CustomBlockRendererDispatcher(BlockModelShapes shapes, BlockColors colors) {
        this.blockModelShapes = shapes;
        this.blockModelRenderer = new CustomBlockModelRenderer(colors);
        this.fluidRenderer = new CustomFluidBlockRenderer();
    }

    public boolean renderModel(BlockState blockStateIn, BlockPos posIn, ILightReader lightReaderIn, MatrixStack matrixStackIn, IVertexBuilder vertexBuilderIn, BitSet forceRender, Random rand, net.minecraftforge.client.model.data.IModelData modelData) {
        try {
            BlockRenderType blockrendertype = blockStateIn.getRenderType();
            return blockrendertype == BlockRenderType.MODEL && this.blockModelRenderer.renderModel(lightReaderIn, this.getModelForState(blockStateIn), blockStateIn, posIn, matrixStackIn, vertexBuilderIn, forceRender, rand, blockStateIn.getPositionRandom(posIn), OverlayTexture.NO_OVERLAY, modelData);
        } catch (Throwable throwable) {
            CrashReport crashreport = CrashReport.makeCrashReport(throwable, "Tesselating block in world");
            CrashReportCategory crashreportcategory = crashreport.makeCategory("Block being tesselated");
            CrashReportCategory.addBlockInfo(crashreportcategory, posIn, blockStateIn);
            throw new ReportedException(crashreport);
        }
    }

    public boolean renderFluid(BlockPos posIn, ILightReader lightReaderIn, IVertexBuilder vertexBuilderIn, IFluidState fluidStateIn, int xOff, int zOff, BitSet forceRender) {
        try {
            return this.fluidRenderer.render(lightReaderIn, posIn, vertexBuilderIn, fluidStateIn, xOff, zOff, forceRender);
        } catch (Throwable throwable) {
            CrashReport crashreport = CrashReport.makeCrashReport(throwable, "Tesselating liquid in world");
            CrashReportCategory crashreportcategory = crashreport.makeCategory("Block being tesselated");
            CrashReportCategory.addBlockInfo(crashreportcategory, posIn, (BlockState) null);
            throw new ReportedException(crashreport);
        }
    }

    public IBakedModel getModelForState(BlockState state) {
        return this.blockModelShapes.getModel(state);
    }
}
