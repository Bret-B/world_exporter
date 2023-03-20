package bret.worldexporter;

import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.util.math.shapes.VoxelShapes;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.IBlockDisplayReader;
import net.minecraft.world.IBlockReader;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.BitSet;

@OnlyIn(Dist.CLIENT)
public class CustomFluidBlockRenderer {
    private static boolean isNeighborSameFluid(IBlockReader pLevel, BlockPos pPos, Direction pSide, FluidState pState) {
        BlockPos blockpos = pPos.relative(pSide);
        FluidState fluidstate = pLevel.getFluidState(blockpos);
        return fluidstate.getType().isSame(pState.getType());
    }

    private static boolean isFaceOccludedByState(IBlockReader p_239284_0_, Direction p_239284_1_, float p_239284_2_, BlockPos p_239284_3_, BlockState p_239284_4_) {
        if (p_239284_4_.canOcclude()) {
            VoxelShape voxelshape = VoxelShapes.box(0.0D, 0.0D, 0.0D, 1.0D, (double) p_239284_2_, 1.0D);
            VoxelShape voxelshape1 = p_239284_4_.getOcclusionShape(p_239284_0_, p_239284_3_);
            return VoxelShapes.blockOccudes(voxelshape, voxelshape1, p_239284_1_);
        } else {
            return false;
        }
    }

    private static boolean isFaceOccludedByNeighbor(IBlockReader p_239283_0_, BlockPos p_239283_1_, Direction p_239283_2_, float p_239283_3_) {
        BlockPos blockpos = p_239283_1_.relative(p_239283_2_);
        BlockState blockstate = p_239283_0_.getBlockState(blockpos);
        return isFaceOccludedByState(p_239283_0_, p_239283_2_, p_239283_3_, blockpos, blockstate);
    }

    private static boolean isFaceOccludedBySelf(IBlockReader p_239282_0_, BlockPos p_239282_1_, BlockState p_239282_2_, Direction p_239282_3_) {
        return isFaceOccludedByState(p_239282_0_, p_239282_3_.getOpposite(), 1.0F, p_239282_1_, p_239282_2_);
    }

    public static boolean shouldRenderFace(IBlockDisplayReader p_239281_0_, BlockPos p_239281_1_, FluidState p_239281_2_, BlockState p_239281_3_, Direction p_239281_4_) {
        return !isFaceOccludedBySelf(p_239281_0_, p_239281_1_, p_239281_3_, p_239281_4_) && !isNeighborSameFluid(p_239281_0_, p_239281_1_, p_239281_4_, p_239281_2_);
    }

    public boolean tesselate(IBlockDisplayReader pLightReader, BlockPos pPos, IVertexBuilder pVertexBuilder, FluidState pFluidState, int xOff, int zOff, BitSet forceRender) {
        boolean flag = pFluidState.is(FluidTags.LAVA);
        TextureAtlasSprite[] atextureatlassprite = net.minecraftforge.client.ForgeHooksClient.getFluidSprites(pLightReader, pPos, pFluidState);
        BlockState blockstate = pLightReader.getBlockState(pPos);
        int i = pFluidState.getType().getAttributes().getColor(pLightReader, pPos);
        float alpha = (float) (i >> 24 & 255) / 255.0F;
        float f = (float) (i >> 16 & 255) / 255.0F;
        float f1 = (float) (i >> 8 & 255) / 255.0F;
        float f2 = (float) (i & 255) / 255.0F;
        boolean flag1 = !isNeighborSameFluid(pLightReader, pPos, Direction.UP, pFluidState);
        boolean flag2 = shouldRenderFace(pLightReader, pPos, pFluidState, blockstate, Direction.DOWN) && !isFaceOccludedByNeighbor(pLightReader, pPos, Direction.DOWN, 0.8888889F);
        boolean flag3 = shouldRenderFace(pLightReader, pPos, pFluidState, blockstate, Direction.NORTH);
        boolean flag4 = shouldRenderFace(pLightReader, pPos, pFluidState, blockstate, Direction.SOUTH);
        boolean flag5 = shouldRenderFace(pLightReader, pPos, pFluidState, blockstate, Direction.WEST);
        boolean flag6 = shouldRenderFace(pLightReader, pPos, pFluidState, blockstate, Direction.EAST);
        if (!flag1 && !flag2 && !flag6 && !flag5 && !flag3 && !flag4 && forceRender.cardinality() == 0) {
            return false;
        } else {
            boolean flag7 = false;
//            float f3 = pLightReader.getShade(Direction.DOWN, true);
//            float f4 = pLightReader.getShade(Direction.UP, true);
//            float f5 = pLightReader.getShade(Direction.NORTH, true);
//            float f6 = pLightReader.getShade(Direction.WEST, true);
            // Force all faces to the same 1x brightness
            float f3 = 1.0F;
            float f4 = 1.0F;
            float f5 = 1.0F;
            float f6 = 1.0F;
            float f7 = this.getWaterHeight(pLightReader, pPos, pFluidState.getType());
            float f8 = this.getWaterHeight(pLightReader, pPos.south(), pFluidState.getType());
            float f9 = this.getWaterHeight(pLightReader, pPos.east().south(), pFluidState.getType());
            float f10 = this.getWaterHeight(pLightReader, pPos.east(), pFluidState.getType());
//            double d0 = (double) (pPos.getX() & 15);
//            double d1 = (double) (pPos.getY() & 15);
//            double d2 = (double) (pPos.getZ() & 15);
            double d0 = (double) pPos.getX() - xOff;
            double d1 = (double) pPos.getY();
            double d2 = (double) pPos.getZ() - zOff;
            float f11 = 0.001F;
            float f12 = flag2 ? 0.001F : 0.0F;
            if (Exporter.isForced(forceRender, Direction.UP) || (flag1 && !isFaceOccludedByNeighbor(pLightReader, pPos, Direction.UP, Math.min(Math.min(f7, f8), Math.min(f9, f10))))) {
                flag7 = true;
                f7 -= 0.001F;
                f8 -= 0.001F;
                f9 -= 0.001F;
                f10 -= 0.001F;
                Vector3d vector3d = pFluidState.getFlow(pLightReader, pPos);
                float f13;
                float f14;
                float f15;
                float f16;
                float f17;
                float f18;
                float f19;
                float f20;
                if (vector3d.x == 0.0D && vector3d.z == 0.0D) {
                    TextureAtlasSprite textureatlassprite1 = atextureatlassprite[0];
                    f13 = textureatlassprite1.getU(0.0D);
                    f17 = textureatlassprite1.getV(0.0D);
                    f14 = f13;
                    f18 = textureatlassprite1.getV(16.0D);
                    f15 = textureatlassprite1.getU(16.0D);
                    f19 = f18;
                    f16 = f15;
                    f20 = f17;
                } else {
                    TextureAtlasSprite textureatlassprite = atextureatlassprite[1];
                    float f21 = (float) MathHelper.atan2(vector3d.z, vector3d.x) - ((float) Math.PI / 2F);
                    float f22 = MathHelper.sin(f21) * 0.25F;
                    float f23 = MathHelper.cos(f21) * 0.25F;
                    float f24 = 8.0F;
                    f13 = textureatlassprite.getU((double) (8.0F + (-f23 - f22) * 16.0F));
                    f17 = textureatlassprite.getV((double) (8.0F + (-f23 + f22) * 16.0F));
                    f14 = textureatlassprite.getU((double) (8.0F + (-f23 + f22) * 16.0F));
                    f18 = textureatlassprite.getV((double) (8.0F + (f23 + f22) * 16.0F));
                    f15 = textureatlassprite.getU((double) (8.0F + (f23 + f22) * 16.0F));
                    f19 = textureatlassprite.getV((double) (8.0F + (f23 - f22) * 16.0F));
                    f16 = textureatlassprite.getU((double) (8.0F + (f23 - f22) * 16.0F));
                    f20 = textureatlassprite.getV((double) (8.0F + (-f23 - f22) * 16.0F));
                }

                float f43 = (f13 + f14 + f15 + f16) / 4.0F;
                float f44 = (f17 + f18 + f19 + f20) / 4.0F;
                float f45 = (float) atextureatlassprite[0].getWidth() / (atextureatlassprite[0].getU1() - atextureatlassprite[0].getU0());
                float f46 = (float) atextureatlassprite[0].getHeight() / (atextureatlassprite[0].getV1() - atextureatlassprite[0].getV0());
                float f47 = 4.0F / Math.max(f46, f45);
                f13 = MathHelper.lerp(f47, f13, f43);
                f14 = MathHelper.lerp(f47, f14, f43);
                f15 = MathHelper.lerp(f47, f15, f43);
                f16 = MathHelper.lerp(f47, f16, f43);
                f17 = MathHelper.lerp(f47, f17, f44);
                f18 = MathHelper.lerp(f47, f18, f44);
                f19 = MathHelper.lerp(f47, f19, f44);
                f20 = MathHelper.lerp(f47, f20, f44);
                int j = this.getLightColor(pLightReader, pPos);
                float f25 = f4 * f;
                float f26 = f4 * f1;
                float f27 = f4 * f2;
                this.vertexVanilla(pVertexBuilder, d0 + 0.0D, d1 + (double) f7, d2 + 0.0D, f25, f26, f27, alpha, f13, f17, j);
                this.vertexVanilla(pVertexBuilder, d0 + 0.0D, d1 + (double) f8, d2 + 1.0D, f25, f26, f27, alpha, f14, f18, j);
                this.vertexVanilla(pVertexBuilder, d0 + 1.0D, d1 + (double) f9, d2 + 1.0D, f25, f26, f27, alpha, f15, f19, j);
                this.vertexVanilla(pVertexBuilder, d0 + 1.0D, d1 + (double) f10, d2 + 0.0D, f25, f26, f27, alpha, f16, f20, j);
                // duplicate quad
//                if (pFluidState.shouldRenderBackwardUpFace(pLightReader, pPos.above())) {
//                    this.vertexVanilla(pVertexBuilder, d0 + 0.0D, d1 + (double) f7, d2 + 0.0D, f25, f26, f27, alpha, f13, f17, j);
//                    this.vertexVanilla(pVertexBuilder, d0 + 1.0D, d1 + (double) f10, d2 + 0.0D, f25, f26, f27, alpha, f16, f20, j);
//                    this.vertexVanilla(pVertexBuilder, d0 + 1.0D, d1 + (double) f9, d2 + 1.0D, f25, f26, f27, alpha, f15, f19, j);
//                    this.vertexVanilla(pVertexBuilder, d0 + 0.0D, d1 + (double) f8, d2 + 1.0D, f25, f26, f27, alpha, f14, f18, j);
//                }
            }

            if (Exporter.isForced(forceRender, Direction.DOWN) || flag2) {
                float f34 = atextureatlassprite[0].getU0();
                float f35 = atextureatlassprite[0].getU1();
                float f37 = atextureatlassprite[0].getV0();
                float f39 = atextureatlassprite[0].getV1();
                int i1 = this.getLightColor(pLightReader, pPos.below());
                float f40 = f3 * f;
                float f41 = f3 * f1;
                float f42 = f3 * f2;
                this.vertexVanilla(pVertexBuilder, d0, d1 + (double) f12, d2 + 1.0D, f40, f41, f42, alpha, f34, f39, i1);
                this.vertexVanilla(pVertexBuilder, d0, d1 + (double) f12, d2, f40, f41, f42, alpha, f34, f37, i1);
                this.vertexVanilla(pVertexBuilder, d0 + 1.0D, d1 + (double) f12, d2, f40, f41, f42, alpha, f35, f37, i1);
                this.vertexVanilla(pVertexBuilder, d0 + 1.0D, d1 + (double) f12, d2 + 1.0D, f40, f41, f42, alpha, f35, f39, i1);
                flag7 = true;
            }

            for (int l = 0; l < 4; ++l) {
                float f36;
                float f38;
                double d3;
                double d4;
                double d5;
                double d6;
                Direction direction;
                boolean flag8;
                if (l == 0) {
                    f36 = f7;
                    f38 = f10;
                    d3 = d0;
                    d5 = d0 + 1.0D;
                    d4 = d2 + (double) 0.001F;
                    d6 = d2 + (double) 0.001F;
                    direction = Direction.NORTH;
                    flag8 = flag3;
                } else if (l == 1) {
                    f36 = f9;
                    f38 = f8;
                    d3 = d0 + 1.0D;
                    d5 = d0;
                    d4 = d2 + 1.0D - (double) 0.001F;
                    d6 = d2 + 1.0D - (double) 0.001F;
                    direction = Direction.SOUTH;
                    flag8 = flag4;
                } else if (l == 2) {
                    f36 = f8;
                    f38 = f7;
                    d3 = d0 + (double) 0.001F;
                    d5 = d0 + (double) 0.001F;
                    d4 = d2 + 1.0D;
                    d6 = d2;
                    direction = Direction.WEST;
                    flag8 = flag5;
                } else {
                    f36 = f10;
                    f38 = f9;
                    d3 = d0 + 1.0D - (double) 0.001F;
                    d5 = d0 + 1.0D - (double) 0.001F;
                    d4 = d2;
                    d6 = d2 + 1.0D;
                    direction = Direction.EAST;
                    flag8 = flag6;
                }

                if (Exporter.isForced(forceRender, direction) || (flag8 && !isFaceOccludedByNeighbor(pLightReader, pPos, direction, Math.max(f36, f38)))) {
                    flag7 = true;
                    BlockPos blockpos = pPos.relative(direction);
                    TextureAtlasSprite textureatlassprite2 = atextureatlassprite[1];
                    if (atextureatlassprite[2] != null) {
                        if (pLightReader.getBlockState(blockpos).shouldDisplayFluidOverlay(pLightReader, blockpos, pFluidState)) {
                            textureatlassprite2 = atextureatlassprite[2];
                        }
                    }

                    float f48 = textureatlassprite2.getU(0.0D);
                    float f49 = textureatlassprite2.getU(8.0D);
                    float f50 = textureatlassprite2.getV((double) ((1.0F - f36) * 16.0F * 0.5F));
                    float f28 = textureatlassprite2.getV((double) ((1.0F - f38) * 16.0F * 0.5F));
                    float f29 = textureatlassprite2.getV(8.0D);
                    int k = this.getLightColor(pLightReader, blockpos);
                    float f30 = l < 2 ? f5 : f6;
                    float f31 = f4 * f30 * f;
                    float f32 = f4 * f30 * f1;
                    float f33 = f4 * f30 * f2;
                    this.vertexVanilla(pVertexBuilder, d3, d1 + (double) f36, d4, f31, f32, f33, alpha, f48, f50, k);
                    this.vertexVanilla(pVertexBuilder, d5, d1 + (double) f38, d6, f31, f32, f33, alpha, f49, f28, k);
                    this.vertexVanilla(pVertexBuilder, d5, d1 + (double) f12, d6, f31, f32, f33, alpha, f49, f29, k);
                    this.vertexVanilla(pVertexBuilder, d3, d1 + (double) f12, d4, f31, f32, f33, alpha, f48, f29, k);
                    // duplicate quad
//                    if (textureatlassprite2 != atextureatlassprite[2]) {
//                        this.vertexVanilla(pVertexBuilder, d3, d1 + (double) f12, d4, f31, f32, f33, alpha, f48, f29, k);
//                        this.vertexVanilla(pVertexBuilder, d5, d1 + (double) f12, d6, f31, f32, f33, alpha, f49, f29, k);
//                        this.vertexVanilla(pVertexBuilder, d5, d1 + (double) f38, d6, f31, f32, f33, alpha, f49, f28, k);
//                        this.vertexVanilla(pVertexBuilder, d3, d1 + (double) f36, d4, f31, f32, f33, alpha, f48, f50, k);
//                    }
                }
            }

            return flag7;
        }
    }

    private void vertexVanilla(IVertexBuilder vertexBuilderIn, double x, double y, double z, float red, float green, float blue, float alpha, float u, float v, int packedLight) {
        vertexBuilderIn.vertex(x, y, z).color(red, green, blue, alpha).uv(u, v).uv2(packedLight).normal(0.0F, 1.0F, 0.0F).endVertex();
    }

    private int getLightColor(IBlockDisplayReader pLightReader, BlockPos pPos) {
        int i = WorldRenderer.getLightColor(pLightReader, pPos);
        int j = WorldRenderer.getLightColor(pLightReader, pPos.above());
        int k = i & 255;
        int l = j & 255;
        int i1 = i >> 16 & 255;
        int j1 = j >> 16 & 255;
        return (k > l ? k : l) | (i1 > j1 ? i1 : j1) << 16;
    }

    private float getWaterHeight(IBlockReader pReader, BlockPos pPos, Fluid pFluid) {
        int i = 0;
        float f = 0.0F;

        for (int j = 0; j < 4; ++j) {
            BlockPos blockpos = pPos.offset(-(j & 1), 0, -(j >> 1 & 1));
            if (pReader.getFluidState(blockpos.above()).getType().isSame(pFluid)) {
                return 1.0F;
            }

            FluidState fluidstate = pReader.getFluidState(blockpos);
            if (fluidstate.getType().isSame(pFluid)) {
                float f1 = fluidstate.getHeight(pReader, blockpos);
                if (f1 >= 0.8F) {
                    f += f1 * 10.0F;
                    i += 10;
                } else {
                    f += f1;
                    ++i;
                }
            } else if (!pReader.getBlockState(blockpos).getMaterial().isSolid()) {
                ++i;
            }
        }

        return f / (float) i;
    }
}
