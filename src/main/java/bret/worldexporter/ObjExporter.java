package bret.worldexporter;

import bret.worldexporter.legacylwjgl.Vector2f;
import bret.worldexporter.legacylwjgl.Vector3f;
import bret.worldexporter.util.ImgUtils;
import bret.worldexporter.util.LABPBRParser;
import bret.worldexporter.util.LRUCache;
import bret.worldexporter.util.OptifineReflector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.lang3.tuple.Triple;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class ObjExporter extends Exporter {
    private final static String TEXTURE_DIR = "tex";
    private final File baseDir = new File(Minecraft.getInstance().gameDirectory, "worldexporter/worlddump" + java.time.LocalDateTime.now().toString().replace(':', '-'));
    private final File texturePath = new File(baseDir, TEXTURE_DIR);
    // geometric vertices cache (tag v) for the .obj output which maps the vertex to its number in the file
    private final Map<Vector3f, Integer> verticesCache = new LRUCache<>(20000);
    // uv texture coordinates cache (tag vt) for the .obj output which maps the uv value to its number in the file
    private final Map<Vector2f, Integer> uvCache = new LRUCache<>(5000);
    private final int[] vertUVIndices = new int[8];
    private final Map<Triple<ResourceLocation, Integer, Integer>, Integer> modelToIdMap = new HashMap<>();
    private final Map<ResourceLocation, String> resourceToNormalMap = new HashMap<>();
    private final Map<ResourceLocation, String> resourceToHeightMap = new HashMap<>();
    private final Map<ResourceLocation, String> resourceToMetalMap = new HashMap<>();
    private final Map<ResourceLocation, String> resourceToRoughnessMap = new HashMap<>();
    private final Map<Triple<ResourceLocation, Integer, Integer>, String> modelToEmissiveMap = new HashMap<>();
    private final Map<Integer, ResourceLocation> modelIdToLocation = new HashMap<>();
    private int modelCount = 0;
    private int vertCount = 0;
    private int uvCount = 0;
    // TODO: add options for these
    private final static boolean squareEmissivity = false;
    private final static boolean forceEnableResourceEmissivity = true;

    public ObjExporter(ClientPlayerEntity player, int radius, int lower, int upper, boolean optimizeMesh, boolean randomize, int threads) {
        super(player, radius, lower, upper, optimizeMesh, randomize, threads);
    }

    public boolean export(String objFilenameIn, String mtlFilenameIn) throws IOException {
        setup();
        Files.createDirectories(texturePath.toPath());
        File objFile = new File(baseDir, objFilenameIn);
        File mtlFile = new File(baseDir, mtlFilenameIn);
        boolean success = true;

        try (FileWriter objWriter = new FileWriter(objFile.getPath()); BufferedWriter objBWriter = new BufferedWriter(objWriter, 32 * (1 << 20));  // 32 MB buffer
             FileWriter mtlWriter = new FileWriter(mtlFile.getPath()); BufferedWriter mtlBWriter = new BufferedWriter(mtlWriter, 1 << 10)) {  // 1 KB buffer
            objBWriter.write("mtllib " + mtlFilenameIn + "\n\n");
            Consumer<ArrayList<Quad>> quadConsumer = (quads) -> {
                try {
                    writeQuads(quads, objBWriter, mtlBWriter);
                } catch (Exception e) {
                    LOGGER.error("Unable to write quads to the obj/mtl file: ", e);
                    throw new RuntimeException(e);
                }
            };
            exportQuads(quadConsumer);
        } catch (IOException | InterruptedException e) {
            success = false;
        }

        finish();
        return success;
    }

    private synchronized void writeQuads(ArrayList<Quad> quads, Writer objWriter, Writer mtlWriter) throws IOException {
        Map<Integer, ArrayList<Quad>> quadsForModel = new HashMap<>();
        for (Quad quad : quads) {
            Triple<ResourceLocation, Integer, Integer> model = Triple.of(quad.getResource(), quad.getColor(), quad.getLightValue());

            int modelId;
            if (!modelToIdMap.containsKey(model)) {
                BufferedImage image = getImage(quad);
                if (image == null || ImgUtils.isCompletelyTransparent(image)) {
                    String reason = image == null ? " because Image was null" : " because Image was completely transparent";
                    LOGGER.warn("Skipped face with texture: " + quad.getResource() + reason);
                    modelToIdMap.put(model, -1);
                    continue;
                }

                ResourceLocation quadResource = quad.getResource();
                modelId = modelCount++;
                modelToIdMap.put(model, modelId);
                modelIdToLocation.put(modelId, quadResource);
                String modelName = quadResource.toString().replaceAll("[^a-zA-Z0-9.-]", "-") + '_' + modelId;

                String baseTextureName = modelName + ".png";
                File fullTextureFilename = new File(texturePath, baseTextureName);
                writeTextureOnThread(fullTextureFilename, image);

                // write material information to .mtl file
                mtlWriter.write("newmtl " + modelName + '\n');
                if (ImgUtils.imageHasTransparency(image)) {
                    mtlWriter.write("map_d " + TEXTURE_DIR + '/' + modelName + ".png" + '\n');
                }

                Runnable emissiveFallback = () -> {
                    if (quad.getLightValue() != 0) {
                        String subpath = modelName + "_e.png";
                        String emissiveTextureName = TEXTURE_DIR + '/' + subpath;
                        int color = Math.max(0, Math.min(255, quad.getLightValue() * 17));
                        color = squareEmissivity ? (color * color) / 255 : color;
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
                            modelToEmissiveMap.put(model, emissiveTextureName);
                        } catch (IOException ignored) {
                        }
                    }
                };

                if (OptifineReflector.validOptifine) {
                    NormalData nd = null;
                    if (!resourceToNormalMap.containsKey(quadResource) || !resourceToHeightMap.containsKey(quadResource)) {
                        // TODO: specify that normal map is exported in OpenGL format in README, or add option?
                        nd = getNormalData(quad, true);
                    }

                    String normalTextureName = null;
                    if (!resourceToNormalMap.containsKey(quadResource) && nd != null) {
                        BufferedImage normal = LABPBRParser.getNormalImage(nd.x, nd.y, nd.z, nd.cols_width);
                        if (LABPBRParser.hasNonDefaultNormal(normal)) {
                            String subpath = modelName + "_n.png";
                            normalTextureName = TEXTURE_DIR + '/' + subpath;
                            resourceToNormalMap.put(quadResource, normalTextureName);
                            writeTextureOnThread(new File(texturePath, subpath), normal);
                        }
                    } else {
                        normalTextureName = resourceToNormalMap.getOrDefault(quadResource, null);
                    }
                    if (normalTextureName != null) {
                        mtlWriter.write("map_Kn " + normalTextureName + '\n');
                        mtlWriter.write("norm " + normalTextureName + '\n');
                        mtlWriter.write("map_bump -bm 1.0 " + normalTextureName + '\n');
                    }

                    // TODO: add option to export heightmap image
                    // I don't know of any OBJ importers that support heightmaps, but write it anyway if enabled
                    // I could add support for adding the heightmap as displacement in the Blender script eventually
//                    String heightTextureName = null;
//                    if (!resourceToHeightMap.containsKey(quadResource) && nd != null) {
//                        BufferedImage height = LABPBRParser.getHeightmapImage(nd.height, nd.cols_width);
//                        if (LABPBRParser.hasHeight(height)) {
//                            String subpath = modelName + "_h.png";
//                            heightTextureName = TEXTURE_DIR + '/' + subpath;
//                            resourceToHeightMap.put(quadResource, heightTextureName);
//                            writeTextureOnThread(new File(texturePath, subpath), height);
//                        }
//                    }
//                    else {
//                        heightTextureName = resourceToHeightMap.getOrDefault(quadResource, null);
//                    }
//                    if (heightTextureName != null) {}

                    SpecularData sd = null;
                    if (!resourceToMetalMap.containsKey(quadResource)
                            || !resourceToRoughnessMap.containsKey(quadResource)
                            || !modelToEmissiveMap.containsKey(model)) {
                        sd = getSpecularData(quad);
                    }

                    String metalTextureName = null;
                    if (!resourceToMetalMap.containsKey(quadResource) && sd != null) {
                        BufferedImage metal = LABPBRParser.getMetalImage(sd.metallic, sd.cols_width);
                        if (LABPBRParser.hasMetal(metal)) {
                            String subpath = modelName + "_m.png";
                            metalTextureName = TEXTURE_DIR + '/' + subpath;
                            resourceToMetalMap.put(quadResource, metalTextureName);
                            writeTextureOnThread(new File(texturePath, subpath), metal);
                        }
                    } else {
                        metalTextureName = resourceToMetalMap.getOrDefault(quadResource, null);
                    }
                    if (metalTextureName != null) {
                        mtlWriter.write("map_Pm " + metalTextureName + '\n');
                    }

                    String roughnessTextureName = null;
                    if (!resourceToRoughnessMap.containsKey(quadResource) && sd != null) {
                        BufferedImage roughness = LABPBRParser.getRoughnessImage(sd.roughness, sd.cols_width);
                        if (LABPBRParser.hasRoughness(roughness)) {
                            String subpath = modelName + "_r.png";
                            roughnessTextureName = TEXTURE_DIR + '/' + subpath;
                            resourceToRoughnessMap.put(quadResource, roughnessTextureName);
                            writeTextureOnThread(new File(texturePath, subpath), roughness);
                        }
                    } else {
                        roughnessTextureName = resourceToRoughnessMap.getOrDefault(quadResource, null);
                    }
                    if (roughnessTextureName != null) {
                        mtlWriter.write("map_Pr " + roughnessTextureName + '\n');
                    }

                    // Can this be made more accurate? How?
                    // Currently, if specular map has no meaningful data whatsoever -> use fallback
                    String emissiveTextureName;
                    if (modelToEmissiveMap.containsKey(model)) {
                        emissiveTextureName = modelToEmissiveMap.get(model);
                        mtlWriter.write("map_Ke " + emissiveTextureName + '\n');
                    } else if (sd != null) {
                        BufferedImage emissive = LABPBRParser.getEmissiveImage(sd.emissiveness, sd.cols_width, squareEmissivity);
                        if (LABPBRParser.hasEmissive(emissive)) {
                            String subpath = modelName + "_e.png";
                            emissiveTextureName = TEXTURE_DIR + '/' + subpath;
                            BufferedImage newEmissive = ImgUtils.mergeTransparency(image, emissive);
                            // If the emissive image is equal to the base image, don't write a duplicate texture
                            // This is disabled because it breaks Blender's OBJ importer
//                            if (ImgUtils.compareImages(image, newEmissive)) {
//                                emissiveTextureName = baseTextureName;  // modelName + ".png"
//                            } else {
//                                writeTextureOnThread(new File(texturePath, subpath), newEmissive);
//                            }
                            writeTextureOnThread(new File(texturePath, subpath), newEmissive);
                            mtlWriter.write("map_Ke " + emissiveTextureName + '\n');
                            modelToEmissiveMap.put(model, emissiveTextureName);
                        } else if (!forceEnableResourceEmissivity) {
                            emissiveFallback.run();
                        }
                    } else {
                        emissiveFallback.run();
                    }
                } else {
                    emissiveFallback.run();
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
            objWriter.write("usemtl " + modelIdToLocation.get(modelId).toString().replaceAll("[^a-zA-Z0-9.-]", "-") + '_' + modelId + '\n');
            for (Quad quad : quadsForModel.get(modelId)) {
                objWriter.write(quadToObj(quad));
            }
        }
    }

    // returns a String of relevant .obj file lines that represent the quad
    private synchronized String quadToObj(Quad quad) {
        StringBuilder result = new StringBuilder(128);
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

        // use the indices to write the quad's face information with the format: f v1/vt1 v2/vt2 v3/vt3 v4/vt4
        result.append("f ").append(vertUVIndices[0]).append('/').append(vertUVIndices[4]).append(' ');
        result.append(vertUVIndices[1]).append('/').append(vertUVIndices[5]).append(' ');
        result.append(vertUVIndices[2]).append('/').append(vertUVIndices[6]).append(' ');
        result.append(vertUVIndices[3]).append('/').append(vertUVIndices[7]).append("\n\n");
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
            LOGGER.error("Could not save resource texture: " + outputFile);
        }
    }

    private void writeTextureOnThread(File outputFile, BufferedImage image) {
        addThreadTask(() -> writeTexture(outputFile, image));
    }
}
