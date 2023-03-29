# world_exporter
A Minecraft Forge mod for exporting parts of a world to a Wavefront .obj file.

![Render of a world export in Blender](../assets/images/atm3-orthographic.png?raw=true)

# Usage and More Information

Install the mod jar like you would any other forge mod, by copying the .jar file into your forge mods directory. 

When in game, run: `/worldexport {radius} {lower} {upper} {optimizeMesh} {randomizeTextures} {threads}`

`radius`: the radius of the export extending from your current position, in blocks (integer) (required)

`lower`: lower height y level (integer) (optional:default=0)

`upper`: upper height y level (integer) (optional:default=255)

`optimizeMesh`: if the resulting mesh should be optimized (true/false) (optional:default=true)

`randomizeTextures`: if the program should export textures exactly as they appear in Minecraft, with slight differences in textures patterning for some blocks (true/false) (optional:default=false)

`threads`: how many threads to use when exporting (integer) (optional:default=4,max=8)

Using `true` for `randomizeTextures` will significantly reduce how well the mesh optimization performs due to slight differences in texture patterns between some blocks.

Using a value higher than `1` for `threads` may result in a slightly higher vertex count and will require more memory. 
The offset order for overlapping faces may also be slightly worse, but this is highly unlikely to have any noticeable effect.

<br />
Example usage with default values:

`/worldexport 64 0 255 true false 4`

Note: Due to clientside command limitations with 1.16.5, the /worldexport command will not show up as a registered command. Regardless, it functions properly.

Note: Due to limitations with the Wavefront .obj format, a new texture must be created for each different texture color. Consider turning **Biome Blend**, found 
in Minecraft's video settings, to a lower value (or off) to reduce the number of textures required for biome color transitions.

A few key features:
* Resource/texture packs are supported
* Modded blocks are supported including tile entity renderers
* Entities like beds, chests, item frames, mobs, players, dropped items, etc. are supported
* Includes mesh optimizations with texture tiling for smaller file sizes and fewer vertices/faces

Current limitations:
* The export radius is limited by your render distance

# Renders of an Exported World

![Castle render](../assets/images/castle.png?raw=true)

![Bridge render](../assets/images/bridge.png?raw=true)

![Golden Cat render](../assets/images/golden-cat.png?raw=true)

![Golden Cat render](../assets/images/golden-cat2.png?raw=true)

![Basement render](../assets/images/bath-basement.png?raw=true)

These renders were created by Familycreature4 using both this tool and Blender.

A python script for fixing texture issues after importing into Blender can be found in the scripts folder.
