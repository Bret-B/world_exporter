package bret.worldexporter;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.PixelGrabber;
import java.awt.image.RescaleOp;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.RoundingMode;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.util.List;
import java.util.*;

import static java.awt.image.BufferedImage.TYPE_INT_ARGB;

public class ObjExporter {
    private final Map<String, List<Quad>> modelQuadsMap = new HashMap<>();
    private final Map<String, TextureAtlasSprite> modelSpriteMap = new HashMap<>();
    private final Map<String, Integer> modelColorMap = new HashMap<>();
    private final Map<String, HashSet<String>> resourceModelMap = new HashMap<>();

    private final Minecraft mc = Minecraft.getMinecraft();
    private final BlockRendererDispatcher blockRenderer = mc.getBlockRendererDispatcher();
    private final BlockModelShapes blockModelShapes = blockRenderer.getBlockModelShapes();
    private final BlockColors blockColors = mc.getBlockColors();
    private final static Logger logger = LogManager.getLogger(WorldExporter.MODID);

    private final BufferBuilder fluidBuilder = new BufferBuilder(2048);
    private static final VertexFormat fluidFormat = new VertexFormat();

    private static final String f = "#.#####";
    private static final DecimalFormat df = new DecimalFormat(f);
    private static BufferedImage atlasImage;

    static {
        fluidFormat.addElement(DefaultVertexFormats.POSITION_3F);
        fluidFormat.addElement(DefaultVertexFormats.COLOR_4UB);
        fluidFormat.addElement(DefaultVertexFormats.TEX_2F);
        fluidFormat.addElement(DefaultVertexFormats.TEX_2S);
    }

    public ObjExporter() {
        df.setRoundingMode(RoundingMode.HALF_UP);

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

    public void buildObjData(EntityPlayer player, int radius) {
        fluidBuilder.setTranslation((int) -player.posX, 0, (int) -player.posZ);

        double playerX = player.posX;
        double playerZ = player.posZ;
        World world = player.getEntityWorld();
        for (int x = -radius; x <= radius; ++x) {
            for (int z = -radius; z <= radius; ++z) {
                for (int y = 255; y >= 0; --y) {
                    Vec3i offsets = new Vec3i(x, y, z);
                    BlockPos blockPos = new BlockPos(x + playerX, y, z + playerZ);
                    // TODO: need to pass in specific faces to always render instead of the entire block
                    //  since this causes sides to be forcibly rendered even when they should not be
                    parseBlock(world, blockPos, x == radius || x == -radius || z == radius || z == -radius || y == 255 || y == 0, offsets);
                }
            }
        }
    }

    private void parseBlock(World world, BlockPos blockPos, boolean isEdge, Vec3i offsets) {
        IBlockState actualState = world.getBlockState(blockPos).getActualState(world, blockPos);
        if (actualState.getBlock().isAir(actualState, world, blockPos)) {
            return;
        }

        switch (actualState.getRenderType()) {
            case MODEL:
                IBakedModel model = blockRenderer.getModelForState(actualState);
                // A few mods may throw a NullPointerException trying to obtain quads with side null
                List<BakedQuad> quads = new ArrayList<>();
                try {
                    quads.addAll(model.getQuads(actualState, null, 0));
                } catch (NullPointerException ignored) {}

                for (EnumFacing facing : EnumFacing.values()) {
                    BlockPos testBlock = blockPos.offset(facing);
                    if (!isEdge && (world.getBlockState(testBlock).getActualState(world, testBlock).isOpaqueCube() || !actualState.shouldSideBeRendered(world, blockPos, facing))) {
                        continue;
                    }
                    quads.addAll(model.getQuads(actualState, facing, 0));
                }
                if (quads.size() == 0) {
                    return;
                }
                addQuads(quads, blockPos, actualState, world, offsets);
                break;
            case LIQUID:  // TODO: fluid rendering
                fluidBuilder.begin(7, fluidFormat);
                if (blockRenderer.fluidRenderer.renderFluid(world, actualState, blockPos, fluidBuilder)) {
                    int newVertexCount = fluidBuilder.getVertexCount();
                    int[] vertexData = new int[7 * newVertexCount];
                    fluidBuilder.getByteBuffer().asIntBuffer().get(vertexData, 0, 7 * newVertexCount);
                    TextureAtlasSprite sprite = blockModelShapes.getTexture(actualState);
                    int color = blockColors.colorMultiplier(actualState, world, blockPos, 0);
                    addData(vertexData, sprite, color, newVertexCount, null);
                }
                fluidBuilder.finishDrawing();
                break;
            case ENTITYBLOCK_ANIMATED:  // TODO: determine if quads/textures can be obtained from animated blocks
            case INVISIBLE:   // TODO: INVISIBLE blocks with possible collision skipped?
            default:
        }
    }

// TODO: to add light: look at:
//  actualState.getBlock().getLightValue();

    private void addQuads(List<BakedQuad> quads, BlockPos pos, IBlockState actualState, World world, Vec3i offsets) {
        for (BakedQuad quad : quads) {
            int tintIndex = quad.getTintIndex();
            int color = tintIndex == -1 ? -1 : blockColors.colorMultiplier(actualState, world, pos, tintIndex);
            addData(quad.getVertexData(), quad.getSprite(), color, 4, offsets);
        }
    }

    private void addData(int[] vertexData, TextureAtlasSprite sprite, int color, int vertCount, @Nullable Vec3i offsets) {
        String resource = sprite.getIconName();

        String model = color == -1 ? resource : resource + color;
        HashSet<String> modelsForResource = resourceModelMap.computeIfAbsent(resource, k -> new HashSet<>());
        modelsForResource.add(model);

        List<Quad> storedQuads = modelQuadsMap.get(model);
        if (storedQuads == null) {
            List<Quad> newResourceList = new ArrayList<>();
            modelQuadsMap.put(model, newResourceList);
            storedQuads = newResourceList;

            modelSpriteMap.put(model, sprite);

            modelColorMap.put(model, color);
        }

        storedQuads.addAll(Quad.getQuads(vertexData, sprite, vertCount, offsets));
    }

    public void exportAllData(String objFilenameIn, String mtlFilenameIn) throws IOException {
        File baseDir = new File(Minecraft.getMinecraft().mcDataDir, "worldexporter/worlddump" + java.time.LocalDateTime.now().toString().replace(':', '-'));
        String textureDirName = "/t";
        File texturePath = new File(baseDir, textureDirName);
        Files.createDirectories(texturePath.toPath());
        File objFile = new File(baseDir, objFilenameIn);
        File mtlFile = new File(baseDir, mtlFilenameIn);

        int vertices = 1;
        int textureCoords = 1;
        try (FileWriter objWriter = new FileWriter(objFile.getPath()); BufferedWriter objbw = new BufferedWriter(objWriter);
             FileWriter mtlWriter = new FileWriter(mtlFile.getPath()); BufferedWriter mtlbw = new BufferedWriter(mtlWriter)) {

            objbw.write("mtllib " + mtlFilenameIn + "\n\n");

            for (String resource : resourceModelMap.keySet()) {
                for (String modelName : resourceModelMap.get(resource)) {
                    List<Quad> quadsList = modelQuadsMap.get(modelName);

                    int splitIndex = modelName.indexOf(':');
                    String partialTextureFilename = resource.equals("missingno") || splitIndex == -1 ? "missingno.png" : modelName.substring(0, splitIndex) + "/textures/" + modelName.substring(splitIndex + 1)  + ".png";
                    File fullTextureFilename = new File(texturePath, partialTextureFilename);

                    // write mtl information to .mtl file
                    mtlbw.write("newmtl " + modelName + "\n");
                    boolean hadTransparency = writeTexture(fullTextureFilename, modelName, modelColorMap.get(modelName));
                    if (hadTransparency) {
                        mtlbw.write("map_d " + textureDirName + '/' + partialTextureFilename + '\n');
                    }
                    mtlbw.write("map_Kd " + textureDirName + '/' + partialTextureFilename + "\n\n");

                    // write all related quads for that material to .obj file
                    objbw.write("usemtl " + modelName + '\n');
                    for (Quad quad : quadsList) {
                        objbw.write(quad.toObj(vertices, textureCoords));
                        vertices += 4;
                        textureCoords += 4;
                    }

                    // Model information can now be removed from the maps for garbage collection
                    modelQuadsMap.remove(modelName);
                    modelSpriteMap.remove(modelName);
                    modelColorMap.remove(modelName);
                }
            }

        } catch (IOException e) {
            logger.debug("Unable to export world data");
        }
    }

    // Return value: true if texture has transparency else false
    private boolean writeTexture(File outputFile, String modelName, int color) {
        TextureAtlasSprite sprite = modelSpriteMap.get(modelName);
        BufferedImage textureImg = atlasImage.getSubimage(sprite.getOriginX(), sprite.getOriginY(), sprite.getIconWidth(), sprite.getIconHeight());

        if (color != -1) {
            textureImg = tintImage(textureImg, color);
        }

        String outputFileStr = outputFile.toString();
        int slashIndex = outputFileStr.lastIndexOf(File.separatorChar);
        slashIndex = slashIndex != -1 ? slashIndex : outputFileStr.length();
        String fullRelativeDirectory = outputFileStr.substring(0, slashIndex);  // substring end is exclusive

        try {
            Files.createDirectories(new File(fullRelativeDirectory).toPath());
            ImageIO.write(textureImg, "png", outputFile);
        }
        catch (IOException e) {
            logger.debug("Could not save resource texture: " + modelName);
        }

        try {
            return imageHasTransparency(textureImg);
        } catch (InterruptedException e) {
            logger.debug("Could not determine if texture has transparency, defaulting to NO: " + modelName);
        }

        return false;
    }

    private BufferedImage tintImage(BufferedImage image, int color) {
        // https://forge.gemwire.uk/wiki/Tinted_Textures
        float[] offsets = new float[] {0, 0, 0, 0};
        float[] rgbaFactors = new float[4];
        rgbaFactors[0] = ((color >> 16) & 255) / 255.0f;
        rgbaFactors[1] = ((color >> 8) & 255) / 255.0f;
        rgbaFactors[2] =  (color & 255) / 255.0f;
        rgbaFactors[3] = 1.0f;
        RenderingHints hints = new RenderingHints(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        hints.add(new RenderingHints(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_DISABLE));
        return new RescaleOp(rgbaFactors, offsets, hints).filter(image, null);
    }

    private boolean imageHasTransparency(BufferedImage image) throws InterruptedException {
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
