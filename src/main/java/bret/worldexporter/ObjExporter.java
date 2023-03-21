package bret.worldexporter;

import bret.worldexporter.legacylwjgl.Vector2f;
import bret.worldexporter.legacylwjgl.Vector3f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.renderer.Atlases;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.RasterFormatException;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public class ObjExporter extends Exporter {
    private static final int OTHER_ORDER = 3;
    private static final Map<RenderType, Integer> renderOrder = new HashMap<RenderType, Integer>() {{
        put(RenderType.solid(), 0);
        put(Atlases.solidBlockSheet(), 0);
        put(RenderType.cutout(), 1);
        put(Atlases.cutoutBlockSheet(), 1);
        put(RenderType.cutoutMipped(), 2);
        put(RenderType.tripwire(), Integer.MAX_VALUE - 1);
        put(RenderType.translucent(), Integer.MAX_VALUE);
        put(RenderType.translucentMovingBlock(), Integer.MAX_VALUE);
        put(RenderType.translucentNoCrumbling(), Integer.MAX_VALUE);
        put(Atlases.translucentItemSheet(), Integer.MAX_VALUE);
        put(Atlases.translucentCullBlockSheet(), Integer.MAX_VALUE);
    }};
    private final ArrayList<Quad> allQuads = new ArrayList<>();
    private final Map<Pair<ResourceLocation, UVBounds>, Float> uvTransparencyCache = new HashMap<>();
    private final Comparator<Quad> quadComparator = getQuadSort();
    // geometric vertices cache (tag v) for the .obj output which maps the vertex to its number in the file
    private final Map<Vector3f, Integer> verticesCache = new LRUCache<>(20000);
    // uv texture coordinates cache (tag vt) for the .obj output which maps the uv value to its number in the file
    private final Map<Vector2f, Integer> uvCache = new LRUCache<>(5000);
    private final int[] vertUVIndices = new int[8];
    private int vertCount = 0;
    private int uvCount = 0;

    public ObjExporter(ClientPlayerEntity player, int radius, int lower, int upper) {
        super(player, radius, lower, upper);
    }

    public void export(String objFilenameIn, String mtlFilenameIn) throws IOException {
        File baseDir = new File(Minecraft.getInstance().gameDirectory, "worldexporter/worlddump" + java.time.LocalDateTime.now().toString().replace(':', '-'));
        String textureDirName = "t";
        File texturePath = new File(baseDir, textureDirName);
        Files.createDirectories(texturePath.toPath());
        File objFile = new File(baseDir, objFilenameIn);
        File mtlFile = new File(baseDir, mtlFilenameIn);

        int modelCount = 0;
        Map<Triple<ResourceLocation, UVBounds, Integer>, Integer> modelToIdMap = new HashMap<>();
        try (FileWriter objWriter = new FileWriter(objFile.getPath()); BufferedWriter objbw = new BufferedWriter(objWriter, 32 * (int) Math.pow(2, 20));
             FileWriter mtlWriter = new FileWriter(mtlFile.getPath()); BufferedWriter mtlbw = new BufferedWriter(mtlWriter, (int) Math.pow(2, 10))) {
            objbw.write("mtllib " + mtlFilenameIn + "\n\n");

            while (getNextChunkData()) {
                // fix quad data issues and add to allQuads
                fixOverlaps(blockQuadsMap.values());
                fixOverlaps(entityUUIDQuadsMap.values());
                blockQuadsMap.values().forEach(allQuads::addAll);
                entityUUIDQuadsMap.values().forEach(allQuads::addAll);

                Map<Integer, ArrayList<Quad>> quadsForModel = new HashMap<>();
                for (Quad quad : allQuads) {
                    Triple<ResourceLocation, UVBounds, Integer> model = Triple.of(quad.getResource(), quad.getUvBounds(), quad.getColor());

                    int modelId;
                    if (!modelToIdMap.containsKey(model)) {
                        BufferedImage image = getImageFromUV(quad.getResource(), quad.getUvBounds(), quad.getColor());
                        if (image == null || ImgUtils.isCompletelyTransparent(image)) {
                            modelToIdMap.put(model, -1);
                            continue;
                        }

                        modelId = modelCount++;
                        modelToIdMap.put(model, modelId);

                        File fullTextureFilename = new File(texturePath, modelId + ".png");
                        writeTexture(fullTextureFilename, image);

                        // write material information to .mtl file
                        mtlbw.write("newmtl " + modelId + "\n");
                        try {
                            if (ImgUtils.imageHasTransparency(image)) {
                                mtlbw.write("map_d " + textureDirName + '/' + modelId + ".png" + '\n');
                            }
                        } catch (InterruptedException ignored) {
                        }
                        mtlbw.write("map_Kd " + textureDirName + '/' + modelId + ".png" + "\n\n");
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
                    objbw.write("usemtl " + modelId + '\n');
                    for (Quad quad : quadsForModel.get(modelId)) {
                        objbw.write(quadToObj(quad));
                    }
                }

                // one chunk of data has been consumed: clear it to prepare for the next chunk
                allQuads.clear();
            }
        } catch (IOException e) {
            LOGGER.debug("Unable to export world data");
        }
    }

    private void removeDuplicateQuads(Collection<ArrayList<Quad>> quadsArrays) {
        for (ArrayList<Quad> quads : quadsArrays) {
            Set<Integer> added = new HashSet<>();
            ArrayList<Quad> uniqueQuads = new ArrayList<>();
            for (int i = 0; i < quads.size(); ++i) {
                if (added.contains(i)) {
                    continue;
                }

                Quad q1 = quads.get(i);
                uniqueQuads.add(q1);
                added.add(i);

                for (int j = i + 1; j < quads.size(); ++j) {
                    if (added.contains(j)) {
                        continue;
                    }

                    if (q1.isEquivalentTo(quads.get(j))) {
                        added.add(j);  // treat the quad as already added since it is equivalent to one that has already been added
                    }
                }
            }

            quads.clear();
            quads.addAll(uniqueQuads);
        }
    }

    // update any quads that overlap by translating by a small multiple of their normal
    private void fixOverlaps(Collection<ArrayList<Quad>> quadsArrays) {
        removeDuplicateQuads(quadsArrays);
        for (ArrayList<Quad> quads : quadsArrays) {
            sortQuads(quads);
            Set<Integer> toCheck = new HashSet<>();
            for (int i = 0, size = quads.size(); i < size; ++i) {
                toCheck.add(i);
            }
            boolean reCheck = !toCheck.isEmpty();
            while (reCheck) {
                Set<Integer> newToCheck = new HashSet<>();
                for (int quadIndex : toCheck) {
                    Quad first = quads.get(quadIndex);
                    ArrayList<Integer> overlapsWithFirst = new ArrayList<>();
                    for (int j = quadIndex + 1; j < quads.size(); ++j) {
                        if (first.overlaps(quads.get(j))) {
                            overlapsWithFirst.add(j);
                        }
                    }

                    if (overlapsWithFirst.isEmpty()) {
                        continue;
                    }

                    Vector3f posTranslate = (Vector3f) first.getNormal().scale(0.00075f);
                    for (int overlapQuad : overlapsWithFirst) {
                        quads.get(overlapQuad).translate(posTranslate);
                        newToCheck.add(overlapQuad);
                    }
                }

                reCheck = !newToCheck.isEmpty();
                toCheck = newToCheck;
            }
        }
    }

    // returns a String of relevant .obj file lines that represent the quad
    private String quadToObj(Quad quad) {
        StringBuilder result = new StringBuilder(128);
        // loop through the quad vertices, calculating the .obj file index for position and uv coordinates
        UVBounds uvBounds = quad.getUvBounds();
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
            // relative UV coordinates [0-1] are calculated using the respective u/v distances, and the v is flipped
            Vector2f relativeUV = new Vector2f((uv.x - uvBounds.uMin) / uvBounds.uDist(), 1 - ((uv.y - uvBounds.vMin) / uvBounds.vDist()));
            int uvIndex;
            if (uvCache.containsKey(relativeUV)) {
                uvIndex = uvCache.get(relativeUV);
            } else {
                uvIndex = ++uvCount;
                uvCache.put(relativeUV, uvIndex);
                // scale global texture atlas UV coordinates to single texture image based UV coordinates (and flip the V)
                result.append("vt ").append(relativeUV.x).append(' ').append(relativeUV.y).append('\n');
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

    private BufferedImage getImageFromUV(ResourceLocation resource, UVBounds uvbound, int color) {
        BufferedImage baseImage;
        try {
            baseImage = getAtlasImage(resource);
        } catch (NullPointerException exception) {
            return null;
        }

        uvbound = uvbound.clamped();

        if (uvbound.uDist() <= 0.000001f || uvbound.vDist() <= 0.000001f) return null;

        int width = Math.max(1, Math.round(baseImage.getWidth() * uvbound.uDist()));
        int height = Math.max(1, Math.round(baseImage.getHeight() * uvbound.vDist()));
        int startX = Math.round(baseImage.getWidth() * uvbound.uMin);
        int startY = Math.round(baseImage.getHeight() * uvbound.vMin);
        BufferedImage textureImg = null;
        try {
            textureImg = baseImage.getSubimage(startX, startY, width, height);
        } catch (RasterFormatException exception) {
            LOGGER.warn("Unable to get the texture for uvbounds: " + width + "w, " + height + "h, " + startX + "x, " + startY + "y, " + "with Uv bounds: " +
                    String.join(",", String.valueOf(uvbound.uMin), String.valueOf(uvbound.uMax), String.valueOf(uvbound.vMin), String.valueOf(uvbound.vMax)));
        }
        if (textureImg != null && color != -1) {
            textureImg = ImgUtils.tintImage(textureImg, color);
        }
        return textureImg;
    }

    private void sortQuads(ArrayList<Quad> quads) {
        quads.sort(quadComparator);
    }

    private Comparator<Quad> getQuadSort() {
        return (quad1, quad2) -> {
            RenderType quad1Layer = quad1.getType();
            RenderType quad2Layer = quad2.getType();
            if (quad1Layer == quad2Layer) {
                float avg1 = uvTransparencyCache.computeIfAbsent(Pair.of(quad1.getResource(), quad1.getUvBounds()), k -> ImgUtils.averageTransparencyValue(getImageFromUV(quad1.getResource(), quad1.getUvBounds(), -1)));
                float avg2 = uvTransparencyCache.computeIfAbsent(Pair.of(quad2.getResource(), quad2.getUvBounds()), k -> ImgUtils.averageTransparencyValue(getImageFromUV(quad2.getResource(), quad2.getUvBounds(), -1)));
                return Float.compare(avg1, avg2);
            } else {
                return Integer.compare(renderOrder.getOrDefault(quad1Layer, OTHER_ORDER), renderOrder.getOrDefault(quad2Layer, OTHER_ORDER));
            }
        };
    }
}
