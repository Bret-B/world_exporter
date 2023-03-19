# After importing the .obj into blender, open and run this script
# This will fix blurry textures and improper transparent material behavior

import bpy

backface_culling = True  # optional
cycles = True

for mat in bpy.data.materials:
    if not mat.node_tree:
        continue

    mat.use_backface_culling = backface_culling
    for node in mat.node_tree.nodes:
        if node.type == 'BSDF_PRINCIPLED':
            if node.inputs["Alpha"].is_linked:
                mat.blend_method = 'HASHED'
                mat.shadow_method = 'HASHED'
                
                if cycles and backface_culling:
                    mix_shader = mat.node_tree.nodes.new('ShaderNodeMixShader')
                    mix_shader.location = (250, 500)
                    geometry = mat.node_tree.nodes.new('ShaderNodeNewGeometry')
                    geometry.location = (0, 700)
                    transparent = mat.node_tree.nodes.new('ShaderNodeBsdfTransparent')
                    transparent.location = (0, 430)
                
                    mat.node_tree.links.remove(mat.node_tree.nodes['Material Output'].inputs[0].links[0])
                    mat.node_tree.links.new(geometry.outputs['Backfacing'], mix_shader.inputs['Fac'])
                    mat.node_tree.links.new(transparent.outputs[0], mix_shader.inputs[2])
                    mat.node_tree.links.new(node.outputs[0], mix_shader.inputs[1])
                    mat.node_tree.links.new(mix_shader.outputs[0], mat.node_tree.nodes['Material Output'].inputs[0])
                    
        elif node.type == 'TEX_IMAGE':
            node.interpolation = 'Closest'
