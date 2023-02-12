package bret.worldexporter;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.ReportedException;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class CustomBlockRendererDispatcher {

    private final BlockModelShapes blockModelShapes;
    private final CustomBlockModelRenderer blockModelRenderer;
    public final CustomBlockFluidRenderer fluidRenderer;

    public CustomBlockRendererDispatcher(BlockModelShapes p_i46577_1_, BlockColors p_i46577_2_) {
        this.blockModelShapes = p_i46577_1_;
        this.blockModelRenderer = new CustomBlockModelRenderer(p_i46577_2_);
        this.fluidRenderer = new CustomBlockFluidRenderer(p_i46577_2_);
    }

    public boolean renderBlock(IBlockState state, BlockPos pos, IBlockAccess blockAccess, BufferBuilder bufferBuilderIn) {
        try {
            EnumBlockRenderType enumblockrendertype = state.getRenderType();

            if (enumblockrendertype == EnumBlockRenderType.INVISIBLE) {
                return false;
            } else {
                if (blockAccess.getWorldType() != WorldType.DEBUG_ALL_BLOCK_STATES) {
                    try {
                        state = state.getActualState(blockAccess, pos);
                    } catch (Exception ignored) {
                    }
                }

                switch (enumblockrendertype) {
                    case MODEL:
                        IBakedModel model = this.getModelForState(state);
                        state = state.getBlock().getExtendedState(state, blockAccess, pos);
                        return this.blockModelRenderer.renderModel(blockAccess, model, state, pos, bufferBuilderIn, true);
                    case LIQUID:
                        return this.fluidRenderer.renderFluid(blockAccess, state, pos, bufferBuilderIn);
                    case ENTITYBLOCK_ANIMATED:
                    default:
                        return false;
                }
            }
        } catch (Throwable throwable) {
            CrashReport crashreport = CrashReport.makeCrashReport(throwable, "Tesselating block in world");
            CrashReportCategory crashreportcategory = crashreport.makeCategory("Block being tesselated");
            CrashReportCategory.addBlockInfo(crashreportcategory, pos, state.getBlock(), state.getBlock().getMetaFromState(state));
            throw new ReportedException(crashreport);
        }
    }

    public IBakedModel getModelForState(IBlockState state) {
        return this.blockModelShapes.getModelForState(state);
    }
}