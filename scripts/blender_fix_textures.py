# After importing the .obj into blender, open and run this script
# This will fix blurry textures and improper transparent material behavior

import bpy

for mat in bpy.data.materials:
    if not mat.node_tree:
        continue

    # mat.use_backface_culling = True  # optional
    for node in mat.node_tree.nodes:
        if node.type == 'BSDF_PRINCIPLED':
            if node.inputs["Alpha"].is_linked:
                mat.blend_method = 'HASHED'
                mat.shadow_method = 'HASHED'
        elif node.type == 'TEX_IMAGE':
            node.interpolation = 'Closest'
