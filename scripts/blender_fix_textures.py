# After importing the .obj into blender, open and run this script
# This will fix blurry textures and improper transparent material behavior

import bpy

ALL_INTERPOLATION_METHODS = ('Linear', 'Cubic', 'Closest', 'Smart')
backface_culling = False  # optional
cycles = True
interpolation = ALL_INTERPOLATION_METHODS[2]
visible_emissive_strength = 30
# TODO
actual_emissive_strength = 30

for mat in bpy.data.materials:
    if not mat.node_tree:
        continue

    if not cycles and backface_culling:
        mat.backface_culling = backface_culling
        
    for node in mat.node_tree.nodes:
        if node.type == 'BSDF_PRINCIPLED':
            print(node)
            node.inputs['Emission Strength'].default_value = visible_emissive_strength
            
            if node.inputs['Alpha'].is_linked:
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
            
            # Blender's OBJ importer incorrectly uses sRGB color space for metallic, roughness, and normal maps when they should 
            # instead be set to Non-Color
            if cycles:
                if node.inputs['Roughness'].is_linked:
                    from_node = node.inputs['Roughness'].links[0].from_node
                    if from_node.type == 'TEX_IMAGE':
                        from_node.image.colorspace_settings.name = 'Non-Color'
                    
                if node.inputs['Metallic'].is_linked:
                    from_node = node.inputs['Metallic'].links[0].from_node
                    if from_node.type == 'TEX_IMAGE':
                        from_node.image.colorspace_settings.name = 'Non-Color'
                    
                if node.inputs['Normal'].is_linked:
                    from_node = node.inputs['Normal'].links[0].from_node
                    skip = False
                    if from_node.type != 'NORMAL_MAP':
                        skip = True
                        
                    normal_input = None
                    if skip is False:
                        test_input = from_node.inputs['Color'].links[0].from_node
                        if test_input.type == 'TEX_IMAGE':
                            normal_input = test_input
                    
                    if normal_input is not None:
                        normal_input.image.colorspace_settings.name = 'Non-Color'
                
        elif node.type == 'TEX_IMAGE':
            node.interpolation = interpolation
