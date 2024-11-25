package bret.worldexporter;

import bret.worldexporter.lwjgl.Vector2f;
import bret.worldexporter.lwjgl.Vector3f;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;


public class ObjExporter extends Exporter {
    private final static int TYPE = 0;
    private final static boolean EXPORT_INVISIBLE = true;
    private final static String TEXTURE_DIR = "tex";
    private final File baseDir = new File(
            Minecraft.getMinecraft().mcDataDir,
            "worldexporter/worlddump" +
                    java.time.LocalDateTime.now().toString().replace(':', '-'));
    private final File texturePath = new File(baseDir, TEXTURE_DIR);
    // geometric vertices cache (tag v) for the .obj output which maps the vertex to its number in the file
    private final Map<Vector3f, Integer> verticesCache = new LRUCache<>(40000);
    // uv texture coordinates cache (tag vt) for the .obj output which maps the uv value to its number in the file
    private final Map<Vector2f, Integer> uvCache = new LRUCache<>(20000);
    private final int[] vertUVIndices = new int[8];
    private final Map<Triple<ResourceLocation, Integer, Integer>, Integer> modelToIdMap = new HashMap<>();
    private final Map<Pair<Integer, Integer>, Integer> colorLightToIdMap = new HashMap<>();
    private final Map<Integer, String> modelIdToName = new HashMap<>();
    private BufferedWriter lastObjWriter = null;
    private int modelCount = 0;
    private int vertCount = 0;
    private int uvCount = 0;

    public ObjExporter(EntityPlayer player, int radius, int lower, int upper, boolean optimizeMesh, boolean randomize) {
        super(player, radius, lower, upper, optimizeMesh, randomize);
    }

    // returns a String of relevant .obj file lines that represent the quad
    private synchronized String quadToObj(Quad quad) {
        boolean hasUV = quad.hasUV();
        StringBuilder result = new StringBuilder(256);
        // loop through the quad vertices, calculating the .obj file index for position and uv coordinates
        for (int i = 0; i < 4; ++i) {
            Vertex vertex = quad.getVertices()[i];
            Vector3f position = vertex.getPosition();
            int vertIndex;
            if (verticesCache.containsKey(position)) {
                vertIndex = verticesCache.get(position);
            } else {
                vertIndex = ++vertCount;
                verticesCache.put(position, vertIndex);
                result.append("v ").append(position.x).append(' ').append(position.y).append(' ').append(position.z).append('\n');
            }
            vertUVIndices[i] = vertIndex;

            if (hasUV) {
                Vector2f uv = vertex.getUv();
                int uvIndex;
                if (uvCache.containsKey(uv)) {
                    uvIndex = uvCache.get(uv);
                } else {
                    uvIndex = ++uvCount;
                    uvCache.put(uv, uvIndex);
                    result.append("vt ").append(uv.x).append(' ').append(uv.y).append('\n');
                }
                vertUVIndices[i + 4] = uvIndex;
            }
        }

        if (hasUV) {
            // use the indices to write the quad's face information with the format: f v1/vt1 v2/vt2 v3/vt3 v4/vt4
            result.append("f ").append(vertUVIndices[0]).append('/').append(vertUVIndices[4]).append(' ');
            result.append(vertUVIndices[1]).append('/').append(vertUVIndices[5]).append(' ');
            result.append(vertUVIndices[2]).append('/').append(vertUVIndices[6]).append(' ');
            result.append(vertUVIndices[3]).append('/').append(vertUVIndices[7]).append('\n');
        } else {
            result.append("f ").append(vertUVIndices[0]).append(' ');
            result.append(vertUVIndices[1]).append(' ');
            result.append(vertUVIndices[2]).append(' ');
            result.append(vertUVIndices[3]).append('\n');
        }

        return result.toString();
    }

    private void writeTexture(File outputFile, BufferedImage image) {
        String outputFileStr = outputFile.toString();
        int slashIndex = outputFileStr.lastIndexOf(File.separatorChar);
        slashIndex = slashIndex != -1 ? slashIndex : outputFileStr.length();
        String fullRelativeDirectory = outputFileStr.substring(0, slashIndex);  // substring end is exclusive

        try {
            Files.createDirectories(new File(fullRelativeDirectory).toPath());
            ImageIO.write(image, "png", outputFile);
        } catch (IOException e) {
            logger.error("Could not save resource texture: " + outputFile);
        }
    }

    public boolean export(String objBaseFilename, String mtlBaseFilename) throws IOException {
        setup();

        Files.createDirectories(texturePath.toPath());
        String fullMtlFilename = mtlBaseFilename + ".mtl";
        File mtlFile = new File(baseDir, fullMtlFilename);
        boolean success = true;


        try (FileWriter mtlWriter = new FileWriter(mtlFile.getPath()); BufferedWriter mtlBWriter = new BufferedWriter(mtlWriter, 8 << 20)) {  // 8 MB buffer
            long chunkCount = 0L;
            ExportChunk nextChunk;
            while ((nextChunk = getNextChunkData()) != null) {
                try {
                    BufferedWriter objWriter = getObjWriter(objBaseFilename, fullMtlFilename, nextChunk);
                    writeChunk(nextChunk, objWriter, mtlBWriter);
                    String chunkNum = String.format("%,d", ++chunkCount);
                    int paddingCount = Math.max(15 - chunkNum.length(), 0);
                    String paddedNum = chunkNum + (new String(new char[paddingCount]).replace("\0", " "));
                    logger.info(String.format("Exported chunk %s At x: %d\tz: %d", paddedNum, nextChunk.xChunkPos, nextChunk.zChunkPos));
                } catch (Exception e) {
                    logger.error("Unable to write chunk to the obj/mtl file: ", e);
                    throw new RuntimeException(e);
                }
            }
        } catch (IOException e) {
            success = false;
        } finally {
            if (lastObjWriter != null) {
                lastObjWriter.close();
            }
        }

        finish();
        return success;
    }


    private synchronized void writeChunk(ExportChunk exportChunk, Writer objWriter, Writer mtlWriter) throws IOException {
        Map<Integer, ArrayList<Quad>> quadsForModel = new HashMap<>();
        for (Quad quad : exportChunk.quads) {
            if (!quad.hasUV()) {
                int color = quad.getColor();
                if ((color & 0xFF000000) == 0 && !EXPORT_INVISIBLE) {
                    logger.warn("Skipped color-only face because it was completely transparent with color: " + Integer.toHexString(color));
                    continue;
                }

                int modelId;
                int light = quad.getLightValue();
                Pair<Integer, Integer> colorModel = Pair.of(color, light);
                if (!colorLightToIdMap.containsKey(colorModel)) {
                    modelId = modelCount++;
                    colorLightToIdMap.put(colorModel, modelId);
                    BufferedImage image = generatePixelImage(color);
                    String modelName = "colorABGR_" + Integer.toHexString(color) + (light == 0 ? "" : "_light_" + light) + '_' + modelId;
                    modelIdToName.put(modelId, modelName);
                    writeTextureOnThread(new File(texturePath, modelName + ".png"), image);
                    mtlWriter.write("newmtl " + modelName + '\n');
                    if (ImgUtils.imageHasTransparency(image)) {
                        mtlWriter.write("map_d " + TEXTURE_DIR + '/' + modelName + ".png" + '\n');
                    }

                    if (light != 0) {
                        String subpath = modelName + "_e.png";
                        int lightValue = Math.max(0, Math.min(255, light * 17));
                        lightValue = (lightValue << 24) | 0x00FFFFFF;  // alpha value to "dim" the image by
                        BufferedImage emissive = ImgUtils.tintImage(image, lightValue);
                        writeTextureOnThread(new File(texturePath, subpath), emissive);
                        mtlWriter.write("map_Ke " + TEXTURE_DIR + '/' + subpath + '\n');
                    }
                    mtlWriter.write("map_Kd " + TEXTURE_DIR + '/' + modelName + ".png" + "\n\n");
                } else {
                    modelId = colorLightToIdMap.get(colorModel);
                }

                quadsForModel.computeIfAbsent(modelId, k -> new ArrayList<>()).add(quad);
                continue;
            }

            Triple<ResourceLocation, Integer, Integer> model = Triple.of(quad.mostSpecificResource(), quad.getColor(), quad.getLightValue());
            int modelId;
            if (!modelToIdMap.containsKey(model)) {
                BufferedImage image = getImage(quad);
                if (image == null) {
                    logger.warn("Skipped face with texture: " + quad.mostSpecificResource() + " because Image was null");
                    modelToIdMap.put(model, -1);
                    continue;
                }
                if (!EXPORT_INVISIBLE && ImgUtils.isCompletelyTransparent(image)) {
                    logger.info("Skipped face with texture: " + quad.mostSpecificResource() + " because Image was completely transparent");
                    modelToIdMap.put(model, -1);
                    continue;
                }

                ResourceLocation quadResource = quad.mostSpecificResource();
                modelId = modelCount++;
                modelToIdMap.put(model, modelId);
                String modelName = quadResource.toString().replaceAll("[^a-zA-Z0-9.-]", "-") + '_' + modelId;
                modelIdToName.put(modelId, modelName);

                String baseTextureName = modelName + ".png";
                File fullTextureFilename = new File(texturePath, baseTextureName);
                writeTextureOnThread(fullTextureFilename, image);

                // write material information to .mtl file
                mtlWriter.write("newmtl " + modelName + '\n');
                if (ImgUtils.imageHasTransparency(image)) {
                    mtlWriter.write("map_d " + TEXTURE_DIR + '/' + modelName + ".png" + '\n');
                }

                if (quad.getLightValue() != 0) {
                    String subpath = modelName + "_e.png";
                    String emissiveTextureName = TEXTURE_DIR + '/' + subpath;
                    int color = Math.max(0, Math.min(255, quad.getLightValue() * 17));
                    color = (color << 24) | 0x00FFFFFF;  // alpha value to "dim" the image by
                    BufferedImage emissive = ImgUtils.tintImage(image, color);
                    // If the emissive image is equal to the base image, don't write a duplicate texture
                    // This is disabled because it breaks Blender's OBJ importer
//                        if (ImgUtils.compareImages(image, emissive)) {
//                            emissiveTextureName = baseTextureName;  // modelName + ".png"
//                        } else {
//                            writeTextureOnThread(new File(texturePath, subpath), emissive);
//                        }
                    writeTextureOnThread(new File(texturePath, subpath), emissive);
                    try {
                        mtlWriter.write("map_Ke " + emissiveTextureName + '\n');
                    } catch (IOException e) {
                        logger.error("Failed to write emissive data for texture: " + emissiveTextureName, e);
                    }
                }

                mtlWriter.write("map_Kd " + TEXTURE_DIR + '/' + modelName + ".png" + "\n\n");
            } else {
                modelId = modelToIdMap.get(model);
            }

            if (modelId == -1) {
                continue;
            }

            quadsForModel.computeIfAbsent(modelId, k -> new ArrayList<>()).add(quad);
        }

        // write all related quads for each material/model id to .obj file
        for (int modelId : quadsForModel.keySet()) {
            objWriter.write("usemtl " + modelIdToName.get(modelId) + '\n');
            for (Quad quad : quadsForModel.get(modelId)) {
                objWriter.write(quadToObj(quad));
            }
        }
    }

    private synchronized BufferedWriter getObjWriter(String objBaseName, String fullMtlFilename, ExportChunk chunk) throws IOException {
        BufferedWriter writer;
        switch (TYPE) {
            case 0:  // single file single object
                writer = getBufferedWriter(objBaseName + ".obj", fullMtlFilename);
                break;
            case 1:  // single file multiple objects
                writer = getBufferedWriter(objBaseName + ".obj", fullMtlFilename);
                // define a new object for the chunk in the single obj file
                writer.write("o " + "chunk_" + chunk.xChunkPos + '_' + chunk.zChunkPos + '\n');
                break;
            case 2:  // multiple files
                if (lastObjWriter != null) {
                    lastObjWriter.close();
                }
                File objFile = new File(baseDir, objBaseName + "_chunk_" + chunk.xChunkPos + '_' + chunk.zChunkPos + ".obj");
                writer = new BufferedWriter(new FileWriter(objFile.getPath()), 4 << 20);  // 4 MB buffer since chunks are usually small
                writer.write("mtllib " + fullMtlFilename + "\n\n");

                // reset vertex and uv counts and their cached values since we are now using a new obj file
                vertCount = 0;
                uvCount = 0;
                verticesCache.clear();
                uvCache.clear();
                break;
            default:
                throw new IllegalStateException("Unexpected chunk export type");
        }

        lastObjWriter = writer;
        return writer;
    }

    private BufferedWriter getBufferedWriter(String objFullFilename, String mtlFullFilename) throws IOException {
        BufferedWriter writer;
        if (lastObjWriter == null) {
            File objFile = new File(baseDir, objFullFilename);
            writer = new BufferedWriter(new FileWriter(objFile.getPath()), 32 << 20);  // 32 MB buffer
            writer.write("mtllib " + mtlFullFilename + "\n\n");
        } else {
            writer = lastObjWriter;
        }
        lastObjWriter = writer;
        return writer;
    }

    private void writeTextureOnThread(File outputFile, BufferedImage image) {
        addThreadTask(() -> writeTexture(outputFile, image));
    }
}
