## VS 2.5.0.0

Proper air pockets! As well as a new version scheme, and many small fixes.

**Physx is still not the default, we chose to use 2.5 to mark the occasion of air pockets instead**

---

The new version scheme is:

`a.b.c.d`

Which will allow us to better separate minor changes
(`c`) from just bug fixes (`d`), instead of before where we had 
`a.b.cd` where `cd` could mean anything from a medium update to a small bugfix.

---

#### Changes:
- Old (buggy) air pocket system has been entirely replaced by an incredibly impressive system.
  We owe a huge thanks to GigaBrawler for making this new system, with additional thanks to 
  Copper, Brickyboy, and Zyxkad for helping.
- Atmospheric max has been changed from 1000 to 2000. This should make propeller planes 
  much more controllable at altitudes above the clouds, where before they would stall pretty suddenly.
- Added `/vs dry` command to help dry your flooded ships

#### API changes:
- Fixed `transformFromWorldToNearbyShipsAndWorld` util function
- Added `getAllConnectedShips` to GTPA
- Added liquid overlap to GTPA
- Moved command related classes (aka `ShipArgument` and others). 
  **Breaking change**, but only imports will need changing

#### Bugfixes:
- Fixed buoyancy on large ships not being quite right
- Fixed an issue where Connectivity would still cause lag even if disabled
- Fixed a crash with Real Camera
- Fixed hexcasting and hexal compat issues
- Fixed copycats duping on assembly
- Fixed CBC autocannons breaking on VS ships
- Fixed a crash with Create: Hypertubes (sorry it took so long Rok!)
- Fixed visual artifacts with the ship AABB when at large coordinates
