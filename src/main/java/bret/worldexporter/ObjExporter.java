package bret.worldexporter;

import bret.worldexporter.legacylwjgl.Vector2f;
import bret.worldexporter.legacylwjgl.Vector3f;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.BlockRenderLayer;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

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
    private final ArrayList<Quad> allQuads = new ArrayList<>();
    private final Map<UVBounds, Float> uvTransparencyCache = new HashMap<>();
    private final Comparator<Quad> quadComparator = getQuadSort();

    public ObjExporter(ServerPlayerEntity player, int radius, int lower, int upper) {
        super(player, radius, lower, upper);
    }

    private void removeDuplicateQuads() {
        for (ArrayList<Quad> quads : blockQuadsMap.values()) {
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
    private void fixOverlaps() {
        removeDuplicateQuads();
        for (ArrayList<Quad> quads : blockQuadsMap.values()) {
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

                    Vector3f posTranslate = (Vector3f) first.getNormal().scale(0.0005f);
                    for (int overlapQuad : overlapsWithFirst) {
                        quads.get(overlapQuad).translate(posTranslate);
                        newToCheck.add(overlapQuad);
                    }
                }

                reCheck = !newToCheck.isEmpty();
                toCheck = newToCheck;
            }

            allQuads.addAll(quads);
        }
    }

    // returns a String of relevant .obj file lines that represent the quad
    private String quadToObj(Quad quad, int vertCount, int uvCount) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 4; ++i) {
            Vertex vertex = quad.getVertices()[i];
            Vector3f position = vertex.getPosition();

            // scale global texture atlas UV coordinates to single texture image based UV coordinates (and flip the V)
            Vector2f uv = vertex.getUv();
            UVBounds uvBounds = quad.getUvBounds();
            uv.x = (uv.x - uvBounds.uMin) / uvBounds.uDist();
            uv.y = 1 - ((uv.y - uvBounds.vMin) / uvBounds.vDist());

            result.append("v ").append(position.x).append(' ').append(position.y).append(' ').append(position.z).append('\n');
            result.append("vt ").append(uv.x).append(' ').append(uv.y).append('\n');
        }

        result.append("f ").append(vertCount).append('/').append(uvCount).append(' ');
        result.append(vertCount + 1).append('/').append(uvCount + 1).append(' ');
        result.append(vertCount + 2).append('/').append(uvCount + 2).append(' ');
        result.append(vertCount + 3).append('/').append(uvCount + 3).append("\n\n");

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
            logger.debug("Could not save resource texture: " + outputFile);
        }
    }

    public void export(String objFilenameIn, String mtlFilenameIn) throws IOException {
        File baseDir = new File(Minecraft.getInstance().gameDir, "worldexporter/worlddump" + java.time.LocalDateTime.now().toString().replace(':', '-'));
        String textureDirName = "t";
        File texturePath = new File(baseDir, textureDirName);
        Files.createDirectories(texturePath.toPath());
        File objFile = new File(baseDir, objFilenameIn);
        File mtlFile = new File(baseDir, mtlFilenameIn);

        int verticesCount = 1;
        int textureUvCount = 1;
        int modelCount = 0;
        Map<Pair<UVBounds, Integer>, Integer> modelToIdMap = new HashMap<>();
        try (FileWriter objWriter = new FileWriter(objFile.getPath()); BufferedWriter objbw = new BufferedWriter(objWriter, 32 * (int) Math.pow(2, 20));
             FileWriter mtlWriter = new FileWriter(mtlFile.getPath()); BufferedWriter mtlbw = new BufferedWriter(mtlWriter, (int) Math.pow(2, 10))) {
            objbw.write("mtllib " + mtlFilenameIn + "\n\n");

            while (getNextChunkData()) {
                fixOverlaps();
                Map<Integer, ArrayList<Quad>> quadsForModel = new HashMap<>();
                for (Quad quad : allQuads) {
                    Pair<UVBounds, Integer> model = new ImmutablePair<>(quad.getUvBounds(), quad.getColor());

                    int modelId;
                    if (!modelToIdMap.containsKey(model)) {
                        BufferedImage image = getImageFromUV(quad.getUvBounds(), quad.getColor());
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
                        objbw.write(quadToObj(quad, verticesCount, textureUvCount));
                        verticesCount += 4;
                        textureUvCount += 4;
                    }
                }

                // one chunk of data has been consumed: clear it to prepare for the next chunk
                allQuads.clear();
            }
        } catch (IOException e) {
            logger.debug("Unable to export world data");
        }
    }

    private BufferedImage getImageFromUV(UVBounds uvbound, int color) {
        int width = Math.round(atlasImage.getWidth() * uvbound.uDist());
        int height = Math.round(atlasImage.getHeight() * uvbound.vDist());
        int startX = Math.round(atlasImage.getWidth() * uvbound.uMin);
        int startY = Math.round(atlasImage.getHeight() * uvbound.vMin);
        BufferedImage textureImg = null;
        try {
            textureImg = atlasImage.getSubimage(startX, startY, width, height);
        } catch (RasterFormatException exception) {
            logger.warn("Unable to get the texture for uvbounds: " + width + "w, " + height + "h, " + startX + "x, " + startY + "y, " + "with Uv bounds: " +
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
            BlockRenderLayer quad1Layer = quad1.getType();
            BlockRenderLayer quad2Layer = quad2.getType();
            if (quad1Layer == quad2Layer) {
                float avg1 = uvTransparencyCache.computeIfAbsent(quad1.getUvBounds(), k -> ImgUtils.averageTransparencyValue(getImageFromUV(quad1.getUvBounds(), -1)));
                float avg2 = uvTransparencyCache.computeIfAbsent(quad2.getUvBounds(), k -> ImgUtils.averageTransparencyValue(getImageFromUV(quad2.getUvBounds(), -1)));
                return Float.compare(avg1, avg2);
            } else {
                return quad1Layer.compareTo(quad2Layer);
            }
        };
    }
}
