# world_exporter
A Minecraft Forge mod for exporting parts of a world to a Wavefront .obj file.

![Render of a world export in Blender](../assets/images/atm3-orthographic.png?raw=true)

# Features

* Resource/texture pack support, including PBR packs (requires Optifine and a LAB-PBR 1.3 pack)
    * Capable of exporting normal, roughness, metallic, height, and ambient occlusion maps
* Modded blocks are supported, including tile entity renderers
* Entities like beds, chests, item frames, mobs, players, dropped items, etc. are supported
* Includes mesh optimizations with texture tiling for smaller file sizes and fewer vertices/faces

# Usage

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

Example usage with default values:

`/worldexport 64 0 255 true false 4`
or simply `/worldexport 64`

Note:
* Due to clientside command limitations with 1.16.5, the /worldexport command will not show up as a registered command. Regardless, it functions properly.
* Due to limitations with the Wavefront .obj format, a new texture must be created for each different texture color. Consider turning **Biome Blend**, found 
in Minecraft's video settings, to a lower value (or off) to reduce the number of textures required for biome color transitions.
* If you have Optifine installed and a compatible resource pack with connected textures, the exporter will use the current Optifine connected textures setting. If enabled, many textures may be exported.

<br />

To export areas larger than your render distance, chunks within a provided radius can be held in memory instead of unloading when they leave your render distance.
Run `/worldexport keepradius {radius}` where radius is an integer (in chunks, not blocks).
Use `-1` for the radius to revert back to default and forget the chunks that are currently stored.

# Configuration

The mod has multiple options that can be changed using Forge's configuration system.

You can either install [Configured](https://www.curseforge.com/minecraft/mc-mods/configured) for a config GUI or edit the CLIENT configuration .toml manually:
run `/config showfile worldexporter CLIENT` to get the config location and then `/reload` once changes are made and saved

# Renders of an Exported World

![Castle render](../assets/images/castle.png?raw=true)

![Bridge render](../assets/images/bridge.png?raw=true)

![Golden Cat render](../assets/images/golden-cat.png?raw=true)

![Golden Cat render](../assets/images/golden-cat2.png?raw=true)

![Basement render](../assets/images/bath-basement.png?raw=true)

These renders were created by Familycreature4 using both this tool and Blender.

A python script for fixing texture issues after importing into Blender can be found in the scripts folder.

# TODO:
Current limitations:
* The export radius is limited by your render distance (see the `/worldexport keepradius` command as a temporary workaround)
* End portal effect and enchantment glint is not properly exported

