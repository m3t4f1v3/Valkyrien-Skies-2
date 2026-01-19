## VS 2.4.9
More buoyancy fixes, connectivity fixes, 

#### Changes:
- Datapackable fluid values (for buoyancy in non-vanilla fluids)
- Removed Herobrine

#### Bugfixes:
- Joining Multiplayer is no longer cooked if you have a ship in the world
- Connectivity no longer incorrectly caches components (should fix excessive buoyancy)
- Connectivity no longer allows buoyancy to use the world air component
- Avoid crashing with latest forge
- Fix compat with create tree fertilizer and supplementaries
- Forge air pocket breathing
- Connectible chains no longer leave behind their hitbox
- Fixed Mekanism multiblock crash
- Fixed extreme slowdown caused while recalculating pockets in client connectivity
