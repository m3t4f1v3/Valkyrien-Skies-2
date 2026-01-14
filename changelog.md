## VS 2.4.8
Assembly fixes, seamless assembly, improved buoyancy updating, lift improvements, etc.

#### Changes:
- Air pockets now update all pockets on ship and don't update a tick late
- Re-added removed assembly methods as redirects to prevent addon breaking
- Visually seamless assembly
- Lift improved and now configurable
- More commands
- Improved split detection
- `/vs set-splitting` command added to enable/disable splitting per ship
- Removed Herobrine

#### Bugfixes:
- Splitting config now works
- Contraptions should no longer split on assembly
- Drag uses the correct face direction
- Explosions split ships properly
- Fabric breathing in sealed pockets fixed
- Hexcasting compat fixes
- ICopyableBlock/ICopyableAttachment now use immutable vectors
- Deployers can place blocks in ships again
- Hypertube compat fixed
- Connectible Chains compat fixed
