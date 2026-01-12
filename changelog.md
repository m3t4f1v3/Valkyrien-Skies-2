## VS 2.4.7
Air pocket buoyancy, improved assembly, drag fixes, no-collide blocks, and connectivity!

#### Changes:
- Splitting has been un-hard disabled
- Air pockets now occlude water in ships
- Air pockets now provide buoyancy based on their volume
- Vastly improved assembly methods (courtesy of G_Mungus and SpaceEye)
- New assembly API features (copyable blocks / attachments) for modders
- Drag calculations improved
- `no-collide` blocks added to VS Blockstates, default Leaves, Redstone Links, and other empty blocks
- Improved Debug items (Better functionality, textures, better feedback) by Zaafonin
- Removed Herobrine

#### Bugfixes:
- Fixed broken distance check causing things like controller lecterns to not work on ships
- Use safe cast for IEntityDraggingProvider
- Connectivity... like all of it
- Client connectivity not syncing
- Fixed echo chest compat
- Fixed doppler effect and added config
