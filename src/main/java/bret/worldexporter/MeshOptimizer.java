package bret.worldexporter;

import bret.worldexporter.legacylwjgl.Vector2f;
import bret.worldexporter.legacylwjgl.Vector3f;
import org.apache.commons.lang3.tuple.Triple;

import java.util.*;

public class MeshOptimizer {
    private final static int ROUND_BITS = 11;
    private static final Comparator<Quad> quadComparator = getQuadComparator();
    private final Map<Edge, List<Quad>> edgeQuadsMap = new HashMap<>();

    // returns true if a quad can be tiled for mesh optimization
    private static boolean canTile(Quad quad) {
        if (!quad.hasUV()) return false;

        // must have UV coordinates capable of tiling (0/1)
        UVBounds uvbounds = quad.getUvBounds();
        if (!floatEq(uvbounds.uMin, 0.0f, ROUND_BITS)) return false;
        if (!floatEq(uvbounds.uMax, 1.0f, ROUND_BITS)) return false;
        if (!floatEq(uvbounds.vMin, 0.0f, ROUND_BITS)) return false;
        if (!floatEq(uvbounds.vMax, 1.0f, ROUND_BITS)) return false;

        // must be a rectangle (equal height/width distances between vertices, right angle corners)
        Vertex[] v = quad.getVertices();
        float widthTop = v[0].getPosition().distanceSq(v[3].getPosition());
        float widthBottom = v[1].getPosition().distanceSq(v[2].getPosition());
        if (!floatEq(widthBottom, widthTop, ROUND_BITS)) return false;
        float heightLeft = v[0].getPosition().distanceSq(v[1].getPosition());
        float heightRight = v[2].getPosition().distanceSq(v[3].getPosition());
        if (!floatEq(heightLeft, heightRight, ROUND_BITS)) return false;
        Vector3f v0v1 = Vector3f.sub(v[1].getPosition(), v[0].getPosition(), null);
        Vector3f v0v3 = Vector3f.sub(v[3].getPosition(), v[0].getPosition(), null);
        return floatEq(Vector3f.dot(v0v1, v0v3), 0.0f, ROUND_BITS);
    }

    // Defines a total ordering for the quads to pick which ones to start at first
    private static Comparator<Quad> getQuadComparator() {
        return (quad1, quad2) -> {
            Vector3f pos1 = quad1.getVertices()[0].getPosition();
            Vector3f pos2 = quad2.getVertices()[0].getPosition();
            if (pos1.x != pos2.x) return Float.compare(pos1.x, pos2.x);
            if (pos1.y != pos2.y) return Float.compare(pos1.y, pos2.y);
            if (pos1.z != pos2.z) return Float.compare(pos1.z, pos2.z);
            float heightSq1 = pos1.distanceSq(quad1.getVertices()[1].getPosition());
            float heightSq2 = pos2.distanceSq(quad2.getVertices()[1].getPosition());
            if (heightSq1 != heightSq2) return Float.compare(heightSq2, heightSq1);
            float widthSq1 = pos1.distanceSq(quad1.getVertices()[3].getPosition());
            float widthSq2 = pos2.distanceSq(quad2.getVertices()[3].getPosition());
            return Float.compare(widthSq2, widthSq1);
        };
    }

    // Compares for approximate equality by rounding bits off the end of both floats
    public static boolean floatEq(float num1, float num2, int bits) {
        return roundBits(num1, bits) == roundBits(num2, bits);
    }

    // Effectively rounds a given number of bits off the end of a floating point number
    public static float roundBits(float number, int bits) {
        float temp = number * ((2 << bits) + 1);
        return number - temp + temp;
    }

    // Overall approach: Loop through all quads, considering only the quads which are eligible
    // (meaning they have uv coordinates of only 0/1 and are rectangular):
    // First, break the quads into non-overlapping subsets of quads where each quad in a subset
    // has the same texture, uv coordinates, and face normal. Then, optimize each subset by
    // merging quads that share an edge by adding the UV coordinate difference to the resulting quad UV's
    // and merging the proper position vertices.
    public ArrayList<Quad> optimize(List<Quad> quads) {
        Map<Triple<String, Long, Vector3f>, TreeSet<Quad>> subSets = new HashMap<>();
        ArrayList<Quad> optimizedQuads = new ArrayList<>();
        for (Quad quad : quads) {
            if (!canTile(quad)) {
                // quad will not be considered for tiling/mesh optimization
                optimizedQuads.add(quad);
                continue;
            }

            // an ordered UV hash is needed here, since even the same vertex ordered quad with the same texture and UVs
            // can have differing UV orders per vertex (Minecraft has multiple different UV texture orders for some blocks).
            long UVHash = 0;
            for (Vertex v : quad.getVertices()) {
                UVHash = 31 * UVHash + v.getUv().hashCode();
            }
            String texture = quad.getResource().toString();
            Vector3f normal = quad.getNormal();
            normal.x = roundBits(normal.x, ROUND_BITS);
            normal.y = roundBits(normal.y, ROUND_BITS);
            normal.z = roundBits(normal.z, ROUND_BITS);
            Triple<String, Long, Vector3f> key = Triple.of(texture, UVHash, normal);
            subSets.computeIfAbsent(key, k -> new TreeSet<>(quadComparator)).add(quad);
        }

        for (Set<Quad> quadSubset : subSets.values()) {
            populateEdges(quadSubset);
            optimizeSubset(quadSubset);
            optimizedQuads.addAll(quadSubset);
            edgeQuadsMap.clear();
        }

        return optimizedQuads;
    }

    // Modifies the provided Set by updating it to contain merged quads equivalent to the provided ones
    private void optimizeSubset(Set<Quad> quadSubset) {
        if (quadSubset.size() <= 1) return;

        Set<Quad> reAdd = new HashSet<>();
        Set<Quad> skip = new HashSet<>();  // set of quads to skip if they occur during iteration of the main loop, since they have already been used
        boolean didMerge;
        do {
            // update state for each quad that was merged last iteration
            quadSubset.addAll(reAdd);
            reAdd.forEach(this::addEdges);
            reAdd.clear();
            skip.clear();
            didMerge = false;

            for (Iterator<Quad> iter = quadSubset.iterator(); iter.hasNext(); ) {
                Quad quad = iter.next();
                if (skip.remove(quad)) {
                    iter.remove();
                    continue;
                }

                boolean skipEdges = false;
                for (Edge edge : getEdges(quad)) {
                    for (Quad quad2 : edgeQuadsMap.getOrDefault(edge, Collections.emptyList())) {
                        if (quad.equals(quad2)) continue;

                        // at this point, the quads must be merge-able, so merge them and update optimizer state
                        didMerge = true;
                        skipEdges = true;
                        removeEdges(quad);
                        removeEdges(quad2);
                        skip.add(quad2);
                        iter.remove();  // set modification only allowed here using iterator
                        merge(quad, quad2, edge);
                        reAdd.add(quad);
                        break;
                    }
                    if (skipEdges) {
                        break;  // quad already merged, edges no longer valid
                    }
                }
            }
        } while (reAdd.size() > 0 || (didMerge && quadSubset.size() > 0));
    }

    // Merges quad1 and quad2 such that quad1 will contain the combined quad created by eliminating
    // their shared vertices (contained within the provided edge). quad2 remains unmodified
    // The quads must have approximately equal UV values in the same vertex ordering for this to be valid
    private void merge(Quad quad1, Quad quad2, Edge edge) {
        Vector3f edgeVec = Vector3f.sub(edge.p2, edge.p1, null);
        Vertex[] q1Vertices = quad1.getVertices();
        Vertex[] q2Vertices = quad2.getVertices();
        Vector2f q2UVDifference = new Vector2f();
        UVBounds q1UV = quad1.getUvBounds();
        int[] uvOrder = new int[3];  // stores vertex indices for the following pattern: 0,x; x,x; x,0
        for (int i = 0; i < 4; ++i) {
            Vertex q1V = q1Vertices[i];
            Vertex q2V = q2Vertices[i];
            if (edge.hasPoint(q1V.getPosition())) {
                q1V.setPosition(q2V.getPosition().x, q2V.getPosition().y, q2V.getPosition().z);  // replace quad1's relevant vertex values with those of quad2
            }

            Vector2f q1vUV = q1V.getUv();
            if (q1vUV.x == q1UV.uMin && q1vUV.y == q1UV.vMax) uvOrder[0] = i;
            if (q1vUV.x == q1UV.uMax && q1vUV.y == q1UV.vMax) uvOrder[1] = i;
            if (q1vUV.x == q1UV.uMax && q1vUV.y == q1UV.vMin) uvOrder[2] = i;

            // if the vector between this vertex and the next is oriented correctly relative to the provided edge,
            // the difference between quad2's UV coordinates in that direction is recorded to be added to the proper vertices
            int nextIndex = (i + 1) % 4;
            Vector3f vertexVector = Vector3f.sub(q1Vertices[nextIndex].getPosition(), q1V.getPosition(), null);
            if (floatEq(Vector3f.dot(vertexVector, edgeVec), 0.0f, ROUND_BITS)) {
                Vector2f.sub(q2Vertices[nextIndex].getUv(), q2Vertices[i].getUv(), q2UVDifference);
                q2UVDifference.x = Math.abs(q2UVDifference.x);
                q2UVDifference.y = Math.abs(q2UVDifference.y);
            }
        }

        q1Vertices[uvOrder[1]].getUv().translate(q2UVDifference.x, q2UVDifference.y);
        ((q2UVDifference.x > q2UVDifference.y) ? q1Vertices[uvOrder[2]].getUv() : q1Vertices[uvOrder[0]].getUv()).translate(q2UVDifference.x, q2UVDifference.y);
        quad1.updateUvBounds();
    }

    private void populateEdges(Collection<Quad> quads) {
        quads.forEach(this::addEdges);
    }

    private void removeEdges(Quad quad) {
        for (Edge edge : getEdges(quad)) {
            List<Quad> quadsForEdge = edgeQuadsMap.get(edge);
            if (quadsForEdge != null) {
                quadsForEdge.remove(quad);
                if (quadsForEdge.size() == 0) edgeQuadsMap.remove(edge);
            }
        }
    }

    private void addEdges(Quad quad) {
        for (Edge edge : getEdges(quad)) {
            edgeQuadsMap.computeIfAbsent(edge, k -> new ArrayList<>()).add(quad);
        }
    }

    private List<Edge> getEdges(Quad quad) {
        Vertex[] v = quad.getVertices();
        return Arrays.asList(
                new Edge(v[0].getPosition(), v[1].getPosition()),
                new Edge(v[2].getPosition(), v[3].getPosition()),
                new Edge(v[0].getPosition(), v[3].getPosition()),
                new Edge(v[1].getPosition(), v[2].getPosition())
        );
    }

    // An edge is equal to another edge if it has the same 2 points regardless of order
    private static class Edge {
        public Vector3f p1;
        public Vector3f p2;

        public Edge(Vector3f p1, Vector3f p2) {
            this.p1 = new Vector3f(roundBits(p1.x, ROUND_BITS), roundBits(p1.y, ROUND_BITS), roundBits(p1.z, ROUND_BITS));
            this.p2 = new Vector3f(roundBits(p2.x, ROUND_BITS), roundBits(p2.y, ROUND_BITS), roundBits(p2.z, ROUND_BITS));
        }

        // Returns true only if the edge contains a given point within a margin of accuracy defined by ROUND_BITS
        public boolean hasPoint(Vector3f position) {
            float x = roundBits(position.x, ROUND_BITS);
            float y = roundBits(position.y, ROUND_BITS);
            float z = roundBits(position.z, ROUND_BITS);
            return ((x == p1.x && y == p1.y && z == p1.z) || (x == p2.x && y == p2.y && z == p2.z));
        }

        // Not order dependent
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Edge other = (Edge) obj;
            return (p1.equals(other.p1) && p2.equals(other.p2)) || (p1.equals(other.p2) && p2.equals(other.p1));
        }

        // Not order dependent
        @Override
        public int hashCode() {
            return Objects.hash(p1) + Objects.hash(p2);
        }
    }
}
