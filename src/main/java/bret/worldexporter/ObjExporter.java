package bret.worldexporter;

import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;
import javax.vecmath.Vector2f;
import javax.vecmath.Vector3f;
import java.awt.image.BufferedImage;
import java.awt.image.RasterFormatException;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ObjExporter extends Exporter {
    // A set of UV bounds uniquely identifies a texture, then an integer color for the texture identifies a list of related quads
    private final Map<UVBounds, Map<Integer, List<Quad>>> uvColorQuadMap = new HashMap<>();

    public ObjExporter() {
    }

    // TODO: build a map<UVBound, resourceName> to write more descriptive file and model names
    public void exportAllData(String objFilenameIn, String mtlFilenameIn) throws IOException {
        convertQuads();
        mergeColors();

        File baseDir = new File(Minecraft.getMinecraft().mcDataDir, "worldexporter/worlddump" + java.time.LocalDateTime.now().toString().replace(':', '-'));
        String textureDirName = "/t";
        File texturePath = new File(baseDir, textureDirName);
        Files.createDirectories(texturePath.toPath());
        File objFile = new File(baseDir, objFilenameIn);
        File mtlFile = new File(baseDir, mtlFilenameIn);

        int verticesCount = 1;
        int textureUvCount = 1;
        try (FileWriter objWriter = new FileWriter(objFile.getPath()); BufferedWriter objbw = new BufferedWriter(objWriter, 131072);
             FileWriter mtlWriter = new FileWriter(mtlFile.getPath()); BufferedWriter mtlbw = new BufferedWriter(mtlWriter, 32768)) {

            objbw.write("mtllib " + mtlFilenameIn + "\n\n");

            int textureCount = 0;
            for (UVBounds uvbound : uvColorQuadMap.keySet()) {
                int colorCount = 0;

                int width = Math.round(atlasImage.getWidth() * uvbound.uDist());
                int height = Math.round(atlasImage.getHeight() * uvbound.vDist());
                int startX = Math.round(atlasImage.getWidth() * uvbound.uMin);
                int startY = Math.round(atlasImage.getHeight() * uvbound.vMin);
                BufferedImage textureImg;
                try {
                    textureImg = atlasImage.getSubimage(startX, startY, width, height);
                } catch (RasterFormatException exception) {
                    logger.warn("Unable to write the texture for uvbounds: " + width + "w, " + height + "h, " + startX + "x, " + startY + "y, " + "with Uv bounds: " + String.join(",", String.valueOf(uvbound.uMin), String.valueOf(uvbound.uMax), String.valueOf(uvbound.vMin), String.valueOf(uvbound.vMax)));
                    continue;
                }

                Map<Integer, List<Quad>> colorQuadMap = uvColorQuadMap.get(uvbound);
                for (int color : colorQuadMap.keySet()) {
                    List<Quad> quadsForColor = colorQuadMap.get(color);
                    String modelName = String.valueOf(textureCount) + '-' + colorCount;
                    File fullTextureFilename = new File(texturePath, modelName + ".png");
                    BufferedImage coloredTextureImg = color == -1 ? textureImg : tintImage(textureImg, color);

                    // write mtl information to .mtl file
                    mtlbw.write("newmtl " + modelName + "\n");
                    writeTexture(fullTextureFilename, coloredTextureImg);
                    try {
                        if (imageHasTransparency(textureImg)) {
                            mtlbw.write("map_d " + textureDirName + '/' + modelName + ".png" + '\n');
                        }
                    } catch (InterruptedException ignored) {
                    }
                    mtlbw.write("map_Kd " + textureDirName + '/' + modelName + ".png" + "\n\n");

                    // write all related quads for that material to .obj file
                    objbw.write("usemtl " + modelName + '\n');
                    for (Quad quad : quadsForColor) {
                        objbw.write(quadToObj(quad, verticesCount, textureUvCount, uvbound));
                        verticesCount += 4;
                        textureUvCount += 4;
                    }

                    colorCount += 1;
                }
                textureCount += 1;
            }

        } catch (IOException e) {
            logger.debug("Unable to export world data");
        }
    }

    // TODO: Find a better way to resolve diffuse lighting for block faces
    //  This is a hacky way to remove the diffuse lighting by merging colors
    //   with the same tint but different brightness values with the highest brightness one
    private void mergeColors() {
//        for (UVBounds uvbound : uvColorQuadMap.keySet()) {
//            Map<Integer, List<Quad>> colorQuadMap = uvColorQuadMap.get(uvbound);
//            Set<Integer> colorSet = colorQuadMap.keySet();
//        }
    }

    // Convert the list of quads to the output format
    private void convertQuads() {
        for (Quad quad : quads) {
            Map<Integer, List<Quad>> colorQuadMap = uvColorQuadMap.computeIfAbsent(quad.getUvBounds(), k -> new HashMap<>());
            List<Quad> quadsForColor = colorQuadMap.computeIfAbsent(quad.getColor(), k -> new ArrayList<>());
            quadsForColor.add(quad);
        }
    }

    // returns a String of relevant .obj file lines that represent the quad
    private String quadToObj(Quad quad, int vertCount, int uvCount, UVBounds uvBounds) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 4; ++i) {
            Vertex vertex = quad.getVertices()[i];
            Vector3f position = vertex.getPosition();

            // scale global texture atlas UV coordinates to single texture image based UV coordinates (and flip the V)
            Vector2f uv = vertex.getUv();
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
}
