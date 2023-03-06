package bret.worldexporter;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.client.renderer.model.BakedQuad;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.crash.ReportedException;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IEnviromentBlockReader;
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

    public boolean renderModel(IEnviromentBlockReader p_217631_1_, IBakedModel p_217631_2_, BlockState p_217631_3_, BlockPos p_217631_4_, BufferBuilder p_217631_5_, boolean p_217631_6_, Random p_217631_7_, long p_217631_8_, net.minecraftforge.client.model.data.IModelData modelData) {
        modelData = p_217631_2_.getModelData(p_217631_1_, p_217631_4_, p_217631_3_, modelData);

        try {
            return this.renderModelFlat(p_217631_1_, p_217631_2_, p_217631_3_, p_217631_4_, p_217631_5_, p_217631_6_, p_217631_7_, p_217631_8_, modelData);
        } catch (Throwable throwable) {
            CrashReport crashreport = CrashReport.makeCrashReport(throwable, "Tesselating block model");
            CrashReportCategory crashreportcategory = crashreport.makeCategory("Block model being tesselated");
            CrashReportCategory.addBlockInfo(crashreportcategory, p_217631_4_, p_217631_3_);
            crashreportcategory.addDetail("Using AO", false);
            throw new ReportedException(crashreport);
        }
    }

    public boolean renderModelFlat(IEnviromentBlockReader p_217635_1_, IBakedModel p_217635_2_, BlockState p_217635_3_, BlockPos p_217635_4_, BufferBuilder p_217635_5_, boolean p_217635_6_, Random p_217635_7_, long p_217635_8_, net.minecraftforge.client.model.data.IModelData modelData) {
        boolean flag = false;
        BitSet bitset = new BitSet(3);

        for (Direction direction : Direction.values()) {
            p_217635_7_.setSeed(p_217635_8_);
            List<BakedQuad> list = p_217635_2_.getQuads(p_217635_3_, direction, p_217635_7_, modelData);
            if (!list.isEmpty() && (!p_217635_6_ || Block.shouldSideBeRendered(p_217635_3_, p_217635_1_, p_217635_4_, direction))) {
                int i = p_217635_3_.getPackedLightmapCoords(p_217635_1_, p_217635_4_.offset(direction));
                this.renderQuadsFlat(p_217635_1_, p_217635_3_, p_217635_4_, i, false, p_217635_5_, list, bitset);
                flag = true;
            }
        }

        p_217635_7_.setSeed(p_217635_8_);
        List<BakedQuad> list1 = p_217635_2_.getQuads(p_217635_3_, (Direction) null, p_217635_7_, modelData);
        if (!list1.isEmpty()) {
            this.renderQuadsFlat(p_217635_1_, p_217635_3_, p_217635_4_, -1, true, p_217635_5_, list1, bitset);
            flag = true;
        }

        return flag;
    }

    private void fillQuadBounds(IEnviromentBlockReader p_217633_1_, BlockState p_217633_2_, BlockPos p_217633_3_, int[] p_217633_4_, Direction p_217633_5_, @Nullable float[] p_217633_6_, BitSet p_217633_7_) {
        float f = 32.0F;
        float f1 = 32.0F;
        float f2 = 32.0F;
        float f3 = -32.0F;
        float f4 = -32.0F;
        float f5 = -32.0F;

        for (int i = 0; i < 4; ++i) {
            float f6 = Float.intBitsToFloat(p_217633_4_[i * 7]);
            float f7 = Float.intBitsToFloat(p_217633_4_[i * 7 + 1]);
            float f8 = Float.intBitsToFloat(p_217633_4_[i * 7 + 2]);
            f = Math.min(f, f6);
            f1 = Math.min(f1, f7);
            f2 = Math.min(f2, f8);
            f3 = Math.max(f3, f6);
            f4 = Math.max(f4, f7);
            f5 = Math.max(f5, f8);
        }

        if (p_217633_6_ != null) {
            p_217633_6_[Direction.WEST.getIndex()] = f;
            p_217633_6_[Direction.EAST.getIndex()] = f3;
            p_217633_6_[Direction.DOWN.getIndex()] = f1;
            p_217633_6_[Direction.UP.getIndex()] = f4;
            p_217633_6_[Direction.NORTH.getIndex()] = f2;
            p_217633_6_[Direction.SOUTH.getIndex()] = f5;
            int j = Direction.values().length;
            p_217633_6_[Direction.WEST.getIndex() + j] = 1.0F - f;
            p_217633_6_[Direction.EAST.getIndex() + j] = 1.0F - f3;
            p_217633_6_[Direction.DOWN.getIndex() + j] = 1.0F - f1;
            p_217633_6_[Direction.UP.getIndex() + j] = 1.0F - f4;
            p_217633_6_[Direction.NORTH.getIndex() + j] = 1.0F - f2;
            p_217633_6_[Direction.SOUTH.getIndex() + j] = 1.0F - f5;
        }

        float f9 = 1.0E-4F;
        float f10 = 0.9999F;
        switch (p_217633_5_) {
            case DOWN:
                p_217633_7_.set(1, f >= 1.0E-4F || f2 >= 1.0E-4F || f3 <= 0.9999F || f5 <= 0.9999F);
                p_217633_7_.set(0, f1 == f4 && (f1 < 1.0E-4F || p_217633_2_.func_224756_o(p_217633_1_, p_217633_3_)));
                break;
            case UP:
                p_217633_7_.set(1, f >= 1.0E-4F || f2 >= 1.0E-4F || f3 <= 0.9999F || f5 <= 0.9999F);
                p_217633_7_.set(0, f1 == f4 && (f4 > 0.9999F || p_217633_2_.func_224756_o(p_217633_1_, p_217633_3_)));
                break;
            case NORTH:
                p_217633_7_.set(1, f >= 1.0E-4F || f1 >= 1.0E-4F || f3 <= 0.9999F || f4 <= 0.9999F);
                p_217633_7_.set(0, f2 == f5 && (f2 < 1.0E-4F || p_217633_2_.func_224756_o(p_217633_1_, p_217633_3_)));
                break;
            case SOUTH:
                p_217633_7_.set(1, f >= 1.0E-4F || f1 >= 1.0E-4F || f3 <= 0.9999F || f4 <= 0.9999F);
                p_217633_7_.set(0, f2 == f5 && (f5 > 0.9999F || p_217633_2_.func_224756_o(p_217633_1_, p_217633_3_)));
                break;
            case WEST:
                p_217633_7_.set(1, f1 >= 1.0E-4F || f2 >= 1.0E-4F || f4 <= 0.9999F || f5 <= 0.9999F);
                p_217633_7_.set(0, f == f3 && (f < 1.0E-4F || p_217633_2_.func_224756_o(p_217633_1_, p_217633_3_)));
                break;
            case EAST:
                p_217633_7_.set(1, f1 >= 1.0E-4F || f2 >= 1.0E-4F || f4 <= 0.9999F || f5 <= 0.9999F);
                p_217633_7_.set(0, f == f3 && (f3 > 0.9999F || p_217633_2_.func_224756_o(p_217633_1_, p_217633_3_)));
        }
    }

    private void renderQuadsFlat(IEnviromentBlockReader p_217636_1_, BlockState p_217636_2_, BlockPos p_217636_3_, int p_217636_4_, boolean p_217636_5_, BufferBuilder p_217636_6_, List<BakedQuad> p_217636_7_, BitSet p_217636_8_) {
        Vec3d vec3d = p_217636_2_.getOffset(p_217636_1_, p_217636_3_);
        double d0 = (double) p_217636_3_.getX() + vec3d.x;
        double d1 = (double) p_217636_3_.getY() + vec3d.y;
        double d2 = (double) p_217636_3_.getZ() + vec3d.z;
        int i = 0;

        for (int j = p_217636_7_.size(); i < j; ++i) {
            BakedQuad bakedquad = p_217636_7_.get(i);
            if (p_217636_5_) {
                this.fillQuadBounds(p_217636_1_, p_217636_2_, p_217636_3_, bakedquad.getVertexData(), bakedquad.getFace(), (float[]) null, p_217636_8_);
                BlockPos blockpos = p_217636_8_.get(0) ? p_217636_3_.offset(bakedquad.getFace()) : p_217636_3_;
                p_217636_4_ = p_217636_2_.getPackedLightmapCoords(p_217636_1_, blockpos);
            }

            p_217636_6_.addVertexData(bakedquad.getVertexData());
            p_217636_6_.putBrightness4(p_217636_4_, p_217636_4_, p_217636_4_, p_217636_4_);
            if (bakedquad.hasTintIndex()) {
                int k = this.blockColors.getColor(p_217636_2_, p_217636_1_, p_217636_3_, bakedquad.getTintIndex());
                float f = (float) (k >> 16 & 255) / 255.0F;
                float f1 = (float) (k >> 8 & 255) / 255.0F;
                float f2 = (float) (k & 255) / 255.0F;
//                if(bakedquad.shouldApplyDiffuseLighting()) {
//                    float diffuse = net.minecraftforge.client.model.pipeline.LightUtil.diffuseLight(bakedquad.getFace());
//                    f *= diffuse;
//                    f1 *= diffuse;
//                    f2 *= diffuse;
//                }
                p_217636_6_.putColorMultiplier(f, f1, f2, 4);
                p_217636_6_.putColorMultiplier(f, f1, f2, 3);
                p_217636_6_.putColorMultiplier(f, f1, f2, 2);
                p_217636_6_.putColorMultiplier(f, f1, f2, 1);
            }
//            else if(bakedquad.shouldApplyDiffuseLighting()) {
//                float diffuse = net.minecraftforge.client.model.pipeline.LightUtil.diffuseLight(bakedquad.getFace());
//                p_217636_6_.putColorMultiplier(diffuse, diffuse, diffuse, 4);
//                p_217636_6_.putColorMultiplier(diffuse, diffuse, diffuse, 3);
//                p_217636_6_.putColorMultiplier(diffuse, diffuse, diffuse, 2);
//                p_217636_6_.putColorMultiplier(diffuse, diffuse, diffuse, 1);
//            }

            p_217636_6_.putPosition(d0, d1, d2);
        }

    }
}
