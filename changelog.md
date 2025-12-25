## VS 2.4

Massive backend, api, and QOL update. 

**Most notable changes:**
- Updated (Krunch) backend to be more performant
- Added PhysX backend option, currently a bit unstable but very performant. Stability will be increased in the future. 
- Revamped API for VS addons, with lots more documentation and requested additions
- Many, many, many bug fixes. Like so many. We've actually lost track.  

**NOTE: ALL VS Addons will need to update to the new api. Allow time for them to update before updating your pack**

But here are the bug fixes we do remember (in no specific order):
- Added block shape based default masses
- Inter-ship frogport functionality
- Added a default assembly blacklist (for addons)
- New config system (accessible in create config viewer, or any other forge-config viewer)
- Many new language files
- Revamped /vs commands, including better autocomplete and new commands for changing backend settings
- Added atmospheric drag for ships, with datapackable values
- Added datapackable dimension gravity
- Some reworked mass values  
- Improved aerodynamic system, including better wings/flaps and shape-based drag+lift
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