package bret.worldexporter;

import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.crash.ReportedException;
import net.minecraft.fluid.IFluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IEnviromentBlockReader;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Random;

@OnlyIn(Dist.CLIENT)
public class CustomBlockRendererDispatcher {
    private final BlockModelShapes blockModelShapes;
    private final CustomBlockModelRenderer blockModelRenderer;
    //    private final ChestRenderer chestRenderer = new ChestRenderer();
    private final CustomFluidBlockRenderer fluidRenderer;
//    private final Random random = new Random();

    public CustomBlockRendererDispatcher(BlockModelShapes p_i46577_1_, BlockColors p_i46577_2_) {
        this.blockModelShapes = p_i46577_1_;
        this.blockModelRenderer = new CustomBlockModelRenderer(p_i46577_2_);
        this.fluidRenderer = new CustomFluidBlockRenderer();
    }

    public boolean renderBlock(BlockState p_215330_1_, BlockPos p_215330_2_, IEnviromentBlockReader p_215330_3_, BufferBuilder p_215330_4_, Random p_215330_5_, net.minecraftforge.client.model.data.IModelData modelData) {
        try {
            BlockRenderType blockrendertype = p_215330_1_.getRenderType();
            if (blockrendertype == BlockRenderType.INVISIBLE) {
                return false;
            } else {
                switch (blockrendertype) {
                    case MODEL:
                        return this.blockModelRenderer.renderModel(p_215330_3_, this.getModelForState(p_215330_1_), p_215330_1_, p_215330_2_, p_215330_4_, true, p_215330_5_, p_215330_1_.getPositionRandom(p_215330_2_), modelData);
                    case ENTITYBLOCK_ANIMATED:
                        return false;
                    default:
                        return false;
                }
            }
        } catch (Throwable throwable) {
            CrashReport crashreport = CrashReport.makeCrashReport(throwable, "Tesselating block in world");
            CrashReportCategory crashreportcategory = crashreport.makeCategory("Block being tesselated");
            CrashReportCategory.addBlockInfo(crashreportcategory, p_215330_2_, p_215330_1_);
            throw new ReportedException(crashreport);
        }
    }

    public boolean renderFluid(BlockPos p_215331_1_, IEnviromentBlockReader p_215331_2_, BufferBuilder p_215331_3_, IFluidState p_215331_4_) {
        try {
            return this.fluidRenderer.render(p_215331_2_, p_215331_1_, p_215331_3_, p_215331_4_);
        } catch (Throwable throwable) {
            CrashReport crashreport = CrashReport.makeCrashReport(throwable, "Tesselating liquid in world");
            CrashReportCategory crashreportcategory = crashreport.makeCategory("Block being tesselated");
            CrashReportCategory.addBlockInfo(crashreportcategory, p_215331_1_, (BlockState) null);
            throw new ReportedException(crashreport);
        }
    }

    public IBakedModel getModelForState(BlockState state) {
        return this.blockModelShapes.getModel(state);
    }
}
