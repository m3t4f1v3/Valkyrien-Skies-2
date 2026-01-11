## VS 2.4.6
Performance enhancements and even more configuration... stuff

#### Changes:
- Added `PhysShip.liquidOverlap`
- Added Drag / Lift Configs
- Added config for Drag/Lift Multipliers (default x10)
- Reduced Copycat mass to 50kg
- Added identifiers to wind, so that multiple mods can define wind direction without overwriting eachother
- Significantly improved collision shape algorithm on both engines
- Octree collisions 😳

#### Bugfixes:
- Changed ordering of joints added to the GTPA's list so that it properly updates for callbacks
- Possibly maybe fix a bug, one side effect of which being the Phasometer from CWSM not working
- Fixed ship sleeping on idle in Krunch
