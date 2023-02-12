package bret.worldexporter;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.client.renderer.color.IBlockColor;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.ForgeHooksClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.vecmath.Vector2f;
import javax.vecmath.Vector3f;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.PixelGrabber;
import java.awt.image.RescaleOp;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.awt.image.BufferedImage.TYPE_INT_ARGB;

public class Exporter {
    protected final Minecraft mc = Minecraft.getMinecraft();
    protected final CustomBlockRendererDispatcher blockRenderer = new CustomBlockRendererDispatcher(mc.getBlockRendererDispatcher().getBlockModelShapes(), mc.getBlockColors());
    protected final static Logger logger = LogManager.getLogger(WorldExporter.MODID);

    protected final ArrayList<Quad> quads = new ArrayList<>();
    protected static BufferedImage atlasImage;

    public Exporter() {
        // Create entire atlas image as a BufferedImage to be used when exporting
        int textureId = mc.getTextureManager().getTexture(new ResourceLocation("minecraft", "textures/atlas/blocks.png")).getGlTextureId();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        int atlasWidth = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int atlasHeight = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        int size = atlasWidth * atlasHeight;
        atlasImage = new BufferedImage(atlasWidth, atlasHeight, TYPE_INT_ARGB);
        IntBuffer buffer = BufferUtils.createIntBuffer(size);
        int[] data = new int[size];
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        buffer.get(data);
        atlasImage.setRGB(0, 0, atlasWidth, atlasHeight, data, 0, atlasWidth);
    }

    public void buildData(EntityPlayer player, int radius) {
        CustomRegionRenderCacheBuilder renderCacheBuilder = new CustomRegionRenderCacheBuilder(radius);
        boolean[] startedBufferBuilders = new boolean[BlockRenderLayer.values().length];

        int playerX = (int) Math.round(player.posX);
        int playerZ = (int) Math.round(player.posZ);

        IBlockAccess world = player.getEntityWorld();
        BlockPos startPos = new BlockPos(playerX + radius, 255, playerZ+ radius);
        BlockPos endPos = new BlockPos(playerX - radius, 0, playerZ - radius);


        // Set bufferbuilder offsets
        for (BlockRenderLayer blockRenderLayer : BlockRenderLayer.values()) {
            int blockRenderLayerId = blockRenderLayer.ordinal();
            BufferBuilder bufferBuilder = renderCacheBuilder.getWorldRendererByLayerId(blockRenderLayerId);
            bufferBuilder.setTranslation(playerX, 0, playerZ);
        }

        for (BlockPos pos : BlockPos.getAllInBoxMutable(startPos, endPos)) {
            IBlockState state = world.getBlockState(pos).getActualState(world, pos);
            if (state.getBlock().isAir(state, world, pos)) {
                continue;
            }

            for (BlockRenderLayer blockRenderLayer : BlockRenderLayer.values()) {
                if (!state.getBlock().canRenderInLayer(state, blockRenderLayer)) {
                    continue;
                }

                ForgeHooksClient.setRenderLayer(blockRenderLayer);
                int blockRenderLayerId = blockRenderLayer.ordinal();
                BufferBuilder bufferBuilder = renderCacheBuilder.getWorldRendererByLayerId(blockRenderLayerId);

                if (!startedBufferBuilders[blockRenderLayerId]) {
                    startedBufferBuilders[blockRenderLayerId] = true;
                    bufferBuilder.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
                }

                // OptiFine Shaders compatibility -- https://gist.github.com/Cadiboo/753607e41ca4e2ca9e0ce3b928bab5ef
//				if (Config.isShaders()) SVertexBuilder.pushEntity(state, pos, blockAccess, bufferBuilder);
                try {
                    blockRenderer.renderBlock(state, pos, world, bufferBuilder);
                } catch (Exception ignored) {}
//				if (Config.isShaders()) SVertexBuilder.popEntity(bufferBuilder);
            }
            ForgeHooksClient.setRenderLayer(null);
        }

        for (int blockRenderLayerId = 0; blockRenderLayerId < startedBufferBuilders.length; ++blockRenderLayerId) {
            if (!startedBufferBuilders[blockRenderLayerId]) {
                continue;
            }

            renderCacheBuilder.getWorldRendererByLayerId(blockRenderLayerId).finishDrawing();
        }

        addBuilderData(renderCacheBuilder);
    }

    private void addBuilderData(CustomRegionRenderCacheBuilder renderCacheBuilder) {
        for (int blockRenderLayerId = 0; blockRenderLayerId < BlockRenderLayer.values().length; ++blockRenderLayerId) {
            BufferBuilder bufferBuilder = renderCacheBuilder.getWorldRendererByLayerId(blockRenderLayerId);
            int vertexCount = bufferBuilder.getVertexCount();
            if (vertexCount > 0) {
                VertexFormat vertexFormat = bufferBuilder.getVertexFormat();
                ByteBuffer bytebuffer = bufferBuilder.getByteBuffer();
                List<VertexFormatElement> list = vertexFormat.getElements();

                Quad quad = new Quad();
                boolean skipQuad = false;
                for (int vertexNum = 0; vertexNum < vertexCount; ++vertexNum) {
                    if (quad.getCount() == 4) {
                        if (skipQuad) {
                            quad = new Quad();
                            skipQuad = false;
                        } else {
                            quads.add(quad);
                            quad = new Quad();
                        }
                    }

                    Vertex vertex = new Vertex();
                    for (int elementIndex = 0; !skipQuad && elementIndex < list.size(); ++elementIndex) {
                        VertexFormatElement vertexFormatElement = list.get(elementIndex);
                        VertexFormatElement.EnumUsage vertexElementEnumUsage = vertexFormatElement.getUsage();

                        switch (vertexElementEnumUsage) {
                            case POSITION:
                                if (vertexFormatElement.getType() == VertexFormatElement.EnumType.FLOAT) {
                                    vertex.setPosition(new Vector3f(bytebuffer.getFloat(), bytebuffer.getFloat(), bytebuffer.getFloat()));
                                } else {
                                    bytebuffer.position(bytebuffer.position() + vertexFormat.getNextOffset());
                                    logger.warn("Vertex position element had no supported type, skipping.");
                                    continue;
                                }
                                break;
                            case COLOR:
                                if (vertexFormatElement.getType() == VertexFormatElement.EnumType.UBYTE) {
                                    vertex.setColor(bytebuffer.getInt());
                                } else {
                                    bytebuffer.position(bytebuffer.position() + vertexFormat.getNextOffset());
                                    logger.warn("Vertex color element had no supported type, skipping.");
                                    continue;
                                }
                                break;
                            case UV:
                                switch (vertexFormatElement.getType()) {
                                    case FLOAT:
                                        float u = bytebuffer.getFloat();
                                        float v = bytebuffer.getFloat();
                                        // Check for NaNs
                                        if (u != u || v != v) {
                                            skipQuad = true;
                                            break;
                                        }

                                        vertex.setUv(new Vector2f(u, v));
                                        break;
                                    case SHORT:
                                        // TODO: ensure value / 16 / (2^16 - 1) gives proper 0-1 float range
                                        //  Minecraft.getMinecraft().getTextureManager().getTexture(new ResourceLocation( "minecraft", "dynamic/lightmap_1"))
                                        //  Discard first short (sky light) and only use second (block light)?
                                        vertex.setUvlight(new Vector2f(bytebuffer.getShort() / 65520.0f, bytebuffer.getShort() / 65520.0f));
                                        break;
                                    default:
                                        bytebuffer.position(bytebuffer.position() + vertexFormat.getNextOffset());
                                        logger.warn("Vertex UV element had no supported type, skipping.");
                                        continue;
                                }
                                break;
                            case PADDING:
                            case NORMAL:
                            default:
                                bytebuffer.position(bytebuffer.position() + vertexFormat.getNextOffset());
                        }
                    }

                    quad.addVertex(vertex);
                }
            }
        }
    }

    protected BufferedImage tintImage(BufferedImage image, int color) {
        // https://forge.gemwire.uk/wiki/Tinted_Textures
        float[] offsets = new float[]{0, 0, 0, 0};
        float[] rgbaFactors = new float[4];
        rgbaFactors[0] = (color & 255) / 255.0f;
        rgbaFactors[1] = ((color >> 8) & 255) / 255.0f;
        rgbaFactors[2] = ((color >> 16) & 255) / 255.0f;
        rgbaFactors[3] = 1.0f;
        RenderingHints hints = new RenderingHints(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        hints.add(new RenderingHints(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_DISABLE));
        return new RescaleOp(rgbaFactors, offsets, hints).filter(image, null);
    }

    protected boolean imageHasTransparency(BufferedImage image) throws InterruptedException {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = new int[image.getWidth() * image.getHeight()];
        PixelGrabber pixelGrabber = new PixelGrabber(image, 0, 0, width, height, pixels, 0, width);
        pixelGrabber.grabPixels();
        for (int pixel : pixels) {
            if ((pixel & 0xFF000000) != 0xFF000000) {
                return true;
            }
        }
        return false;
    }
}
