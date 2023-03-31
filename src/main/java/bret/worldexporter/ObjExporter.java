package bret.worldexporter;

import bret.worldexporter.legacylwjgl.Vector2f;
import bret.worldexporter.legacylwjgl.Vector3f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.lang3.tuple.Pair;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class ObjExporter extends Exporter {
    private final static String TEXTURE_DIR = "t";
    private final File baseDir = new File(Minecraft.getInstance().gameDirectory, "worldexporter/worlddump" + java.time.LocalDateTime.now().toString().replace(':', '-'));
    private final File texturePath = new File(baseDir, TEXTURE_DIR);
    // geometric vertices cache (tag v) for the .obj output which maps the vertex to its number in the file
    private final Map<Vector3f, Integer> verticesCache = new LRUCache<>(20000);
    // uv texture coordinates cache (tag vt) for the .obj output which maps the uv value to its number in the file
    private final Map<Vector2f, Integer> uvCache = new LRUCache<>(5000);
    private final int[] vertUVIndices = new int[8];
    private final Map<Pair<ResourceLocation, Integer>, Integer> modelToIdMap = new HashMap<>();
    private final Map<Integer, ResourceLocation> modelIdToLocation = new HashMap<>();
    private int modelCount = 0;
    private int vertCount = 0;
    private int uvCount = 0;

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
                } catch (IOException e) {
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
            Pair<ResourceLocation, Integer> model = Pair.of(quad.getResource(), quad.getColor());

            int modelId;
            if (!modelToIdMap.containsKey(model)) {
                BufferedImage image = getImage(quad);
                if (image == null || ImgUtils.isCompletelyTransparent(image)) {
                    String reason = image == null ? " because Image was null" : " because Image was completely transparent";
                    LOGGER.warn("Skipped face with texture: " + quad.getResource() + reason);
                    modelToIdMap.put(model, -1);
                    continue;
                }

                modelId = modelCount++;
                modelToIdMap.put(model, modelId);
                modelIdToLocation.put(modelId, quad.getResource());
                String modelName = quad.getResource().toString().replaceAll("[^a-zA-Z0-9.-]", "-") + '_' + modelId;

                File fullTextureFilename = new File(texturePath, modelName + ".png");
                writeTexture(fullTextureFilename, image);

                // write material information to .mtl file
                mtlWriter.write("newmtl " + modelName + "\n");
                try {
                    if (ImgUtils.imageHasTransparency(image)) {
                        mtlWriter.write("map_d " + TEXTURE_DIR + '/' + modelName + ".png" + '\n');
                    }
                } catch (InterruptedException ignored) {
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
            LOGGER.debug("Could not save resource texture: " + outputFile);
        }
    }
}
