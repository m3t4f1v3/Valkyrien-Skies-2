## VS 2.4.1
First major patch of 2.4.

#### Changes:
- Added issues link to fabric.mod.json (Credit: millenIumAMbiguity)
- Added comment explaining why forge malds about MixinVibrationSystem (Credit: zaafonin)

#### Bugfixes:
- Fixed an issue where BlockEntityPhysicsListeners wouldn't reload without a blockstate update. (Credit: theplasticpotato)
- Fixed a crash on dedicated servers due to a ClientLevel reference in a mixin. (Credit: zaafonin & m3t4f1v3)
- Fixed polish translations (Credit: KAW0)
- Fixed Create: Stuff & Additions mixins (Credit: zaafonin)
- Fixed EMF/ETF compatibility (Credit: EnderBoy9217)
- Fixed a long standing issue where block mass was calculated at corner rather than block center (Credit: StewStrong) - MAY BREAK THINGS!!
- Fixed joints applying large forces when created (Credit: StewStrong)
- Fixed issue with fan airstreams on ships where the first fan processing type was incorrectly assumed to be null (Credit: Zaafonin)
- Fixed sneaking on ships (Credit: theplasticpotato)
- Fixed placing and breaking blocks on fast moving ships (mostly, still fails in extreme speeds due to zaafonin's pillar fix PR)  (Credit: theplasticpotato)
- Fixed interactions with blocks on ships at high speed  (Credit: theplasticpotato)
- Fixed using valueboxes at high speed  (Credit: theplasticpotato)
- Fixed incorrect eye position and rotation on ships  (Credit: theplasticpotato)
- Fixed incorrect rotation when seated on a ship  (Credit: theplasticpotato)
- Fixed boats or other vehicles causing crashes when riding them onto a ship  (Credit: theplasticpotato)
- Added a missing null check to getLastShipStoodOn  (Credit: theplasticpotato)
- Fixed entity dragging becoming stuck when jumping onto a non full block on ships (Credit: theplasticpotato)

## VS 2.4

Massive backend, api, and QOL update. 

**Most notable changes:**
- Updated (Krunch) backend to be more performant
- Added PhysX backend option, currently a bit unstable but very performant. Stability will be increased in the future. 
- Revamped API for VS addons, with lots more documentation and requested additions
- Ships have aerodynamics now! No longer will they drift forever with zero-G. Shape based lift is also now a thing!
- Updated entity dragging. No longer should you be left behind by a fast ship, or be unable to interact with blocks at high speed. Multiplayer is also fixed, no more will your friends lag behind the ship! (mostly) 
- Many, many, many bug fixes. Like so many. We've actually lost track.  

**NOTE: ALL VS Addons will need to update to the new api. Allow time for them to update before updating your pack**

Other changes:
_non-exhaustive, and in no particular order_
- Added block shape based default masses
- Inter-ship frogport functionality
- Added a default assembly blacklist (for addons)
- New config system (accessible in create config viewer, or any other forge-config viewer)
- Many new language files
- Revamped /vs commands, including better autocomplete and new commands for changing backend settings
- Added atmospheric drag for ships, with datapackable values
- Added datapackable dimension gravity
- Some reworked mass values  
- Fixed players getting shipyarded when respawning on deleted ships
- Fixed toolbox interactions on ships
- Fixed compat with very many players
- Made cbc recoil configurable
- Fixed TerraFirmaCraft compat (again)
- Fixed voxy rendering with ships
- Fixed cbc shells not loading chunks correctly
- Fixed crash with lithum
- Fixed dispenser momentum
- Fixed miniship fog with sodium
- Added ship splitting (disabled by default in config)
