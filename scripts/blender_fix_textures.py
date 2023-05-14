# After importing the .obj into blender, open and run this script with the imported object selected.
# This fixes a variety of things including blurry textures, improper transparent material behavior,
# and incorrect colorspace for PBR texture maps. 
# It also allows you to modify normal strength and material emission strength. 
# This script is designed specifically for Blender's Cycles render engine, but may help with other renderers too.

import bpy


ALL_INTERPOLATION_METHODS = ('Linear', 'Cubic', 'Closest', 'Smart')
cycles = True  # highly recommended


# change any of these values
interpolation = ALL_INTERPOLATION_METHODS[1]  # Cubic generally works well
backface_culling = False  # optional, turning this on can make some textures appear incorrectly
preserve_lowres_textures = True  # keeps the pixel art look of lowres textures instead of blurring them
# if a texture width or height is <= this value and preserve_lowres_textures is True,
# the texture's interpolation will be forced to 'Closest' to preserve the pixel art look of lowres textures
lowres_threshold = 64  # you may find better results with a value of 32
visible_emissive_strength = 5
# actual_emissive_strength = 5  # TODO: actual_emissive_strength implementation
normal_strength = 2.0
# Do not edit anything below this line unless you know what you're doing


for selected_object in bpy.context.selected_objects:
    for mat in selected_object.data.materials:
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
                        
                        from_node.inputs['Strength'].default_value = normal_strength
                        normal_input = None
                        if skip is False:
                            test_input = from_node.inputs['Color'].links[0].from_node
                            if test_input.type == 'TEX_IMAGE':
                                normal_input = test_input
                        
                        if normal_input is not None:
                            normal_input.image.colorspace_settings.name = 'Non-Color'
                    
            elif node.type == 'TEX_IMAGE':
                res = node.image.size[:]
                if preserve_lowres_textures and (res[0] <= lowres_threshold or res[1] <= lowres_threshold):
                    node.interpolation = 'Closest'
                else:
                    node.interpolation = interpolation
