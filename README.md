# world_exporter
A Minecraft Forge mod for exporting parts of a world to a Wavefront .obj file.

![Render of a world export in Blender](../assets/images/atm3-orthographic.png?raw=true)

# Usage and More Information

Install the mod jar like you would any other forge mod. 

When in game, run: `/worldexport {radius} {lower} {upper}` where each argument is an integer. 

`radius`: the radius of the export extending from your current position, in blocks

`lower`: optional lower height limit (y level)

`upper`: optional upper height limit (y level)

Example usage:

`/worldexport 128 50 255`

Note: Due to clientside command limitations with 1.15.2, 
the /worldexport command will not show up as a registered command, but will work fine.

A few key features:
* Resource/texture packs are supported
* Modded blocks are supported including tile entity renderers
* Entities like beds, chests, item frames, mobs, players, dropped items, etc. are supported

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
