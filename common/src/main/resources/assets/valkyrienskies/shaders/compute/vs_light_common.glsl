// Shared declarations for the VS dynamic-light compute passes.
//
// Every pass declares the same SSBO set at the same binding points; a pass that never touches a given
// buffer simply leaves that binding inactive, which costs nothing and keeps the Java-side binding
// table identical for all five programs.
//
// The PACKED section layout below must stay in sync with VsWorldFromShipLightStorage (and
// VsShipLightStorage, which uses the identical layout): 18^3 voxels per section, a solid bitmap
// followed by one light byte per voxel, low nibble = block light, high nibble = occluder strength.
//
// The WORKING layout is what the compute passes actually flood in: 16^3 core voxels per section, one
// uint each, no halo. Neighbours outside the core are reached through the LUT. Keeping the working
// values unpacked is what makes atomicMax legal during the stamp — a max over a byte-packed word is
// not a per-byte max.

const uint VS_BLOCKS_PER_SECTION = 18u * 18u * 18u;  // 5832
const uint VS_SOLID_SIZE_INTS    = 183u;             // ceil(5832 / 32)
const uint VS_LIGHT_START_INTS   = 183u;             // 732 bytes of solid bitmap
const uint VS_SECTION_SIZE_INTS  = 1641u;            // 183 + 5832/4

const uint VS_CORE_VOXELS     = 4096u;               // 16^3
const uint VS_CORE_SOLID_INTS = 128u;                // 4096 / 32

// The working light buffers hold light in 1/16ths of a level, not whole levels.
//
// Whole levels make a seed all-or-nothing: a cell the emitter barely overlaps still receives FULL
// strength, so as a ship drifts sub-block the seed set flips cell by cell and the entire flood pattern
// jumps a whole cell at a time. That is the flicker a moving ship shows. In 16ths a seed can encode
// how far into the cell the emitter actually is, so the pattern slides instead of snapping, and the
// pack pass divides back down to the nibble the fragment shaders sample.
const uint VS_LIGHT_SCALE = 16u;

// How ship coverage (0-15) attenuates light crossing a cell, in 16ths, above a threshold.
//
// The coverage field is a trilinear splat, so it has a SKIRT: cells just outside the hull carry a
// partial value even though nothing is really there. Charging for coverage linearly therefore killed
// light exactly where it is born -- exit cells sit against the hull, in the thickest part of the
// skirt, and the flood died before it left the ship.
//
// Only cells more than half filled are wall. Below the threshold light passes freely; above it the
// cost ramps steeply enough that a fully covered cell stops light dead. Still continuous in coverage,
// so a drifting ship changes attenuation smoothly instead of flipping a bit.
const uint VS_COVERAGE_SOLID = 8u;
const uint VS_COVERAGE_COST = 32u;

layout(std430, binding = 0) buffer VsLightSrc { uint vs_lightSrc[]; };
layout(std430, binding = 1) buffer VsLightDst { uint vs_lightDst[]; };
layout(std430, binding = 2) buffer VsOccl     { uint vs_occl[]; };
layout(std430, binding = 3) buffer VsSolid    { uint vs_solid[]; };
layout(std430, binding = 4) readonly buffer VsLut      { uint vs_lut[]; };
layout(std430, binding = 5) writeonly buffer VsSections { uint vs_sections[]; };
layout(std430, binding = 6) readonly buffer VsWorldLut      { uint vs_worldLut[]; };
layout(std430, binding = 7) readonly buffer VsWorldSections { uint vs_worldSections[]; };
// One entry per section live this frame: (arenaSlot, sectionX, sectionY, sectionZ).
layout(std430, binding = 8) readonly buffer VsSlotPos { ivec4 vs_slotPos[]; };
layout(std430, binding = 9) readonly buffer VsVoxels  { uvec2 vs_voxels[]; };
// Slot index of each active section's six face neighbours, -1 where absent; six ints per section, in
// the direction order the flood uses. Lets a sweep resolve a cross-section neighbour with one lookup
// instead of a three-level LUT walk.
layout(std430, binding = 10) readonly buffer VsSlotNeighbours { int vs_slotNeighbours[]; };

// ---------------------------------------------------------------------------
// LUT traversal. Same 3-level (Y -> X -> Z) coordinate-span walk as LightLut /
// vs_dynamic_light.glsl, just reading an SSBO instead of a buffer texture.
// ---------------------------------------------------------------------------

bool vs_nextLut(uint base, int coord, out uint next) {
    int start = int(vs_lut[base]);
    uint size = vs_lut[base + 1u];
    int idx = coord - start;
    if (idx < 0 || idx >= int(size)) return true;
    next = vs_lut[base + 2u + uint(idx)];
    return false;
}

/** Arena slot for a section, or -1 when the section isn't tracked. */
int vs_findSection(ivec3 sectionPos) {
    uint first;
    if (vs_nextLut(0u, sectionPos.y, first) || first == 0u) return -1;
    uint second;
    if (vs_nextLut(first, sectionPos.x, second) || second == 0u) return -1;
    uint index;
    if (vs_nextLut(second, sectionPos.z, index) || index == 0u) return -1;
    return int(index) - 1;
}

bool vs_nextWorldLut(uint base, int coord, out uint next) {
    int start = int(vs_worldLut[base]);
    uint size = vs_worldLut[base + 1u];
    int idx = coord - start;
    if (idx < 0 || idx >= int(size)) return true;
    next = vs_worldLut[base + 2u + uint(idx)];
    return false;
}

int vs_findWorldSection(ivec3 sectionPos) {
    uint first;
    if (vs_nextWorldLut(0u, sectionPos.y, first) || first == 0u) return -1;
    uint second;
    if (vs_nextWorldLut(first, sectionPos.x, second) || second == 0u) return -1;
    uint index;
    if (vs_nextWorldLut(second, sectionPos.z, index) || index == 0u) return -1;
    return int(index) - 1;
}

// ---------------------------------------------------------------------------
// Working-buffer addressing
// ---------------------------------------------------------------------------

/** Core voxel index within a section: x fastest, then z, then y (mirrors the packed layout). */
uint vs_coreIndex(ivec3 c) {
    return uint(c.x) | (uint(c.z) << 4u) | (uint(c.y) << 8u);
}

ivec3 vs_coreCoords(uint v) {
    return ivec3(int(v & 15u), int((v >> 8u) & 15u), int((v >> 4u) & 15u));
}

/**
 * Index into the working light/occluder buffers for an absolute world block, or -1 when its section
 * isn't tracked. `>> 4` on a signed int is arithmetic and `& 15` is the positive remainder, so this
 * matches the Java `pos >> 4` / `pos & 15` split for negative coordinates too.
 */
int vs_voxelIndexAt(ivec3 worldBlock) {
    int slot = vs_findSection(worldBlock >> 4);
    if (slot < 0) return -1;
    return slot * int(VS_CORE_VOXELS) + int(vs_coreIndex(worldBlock & 15));
}

bool vs_isSolidAt(int voxelIndex) {
    uint slot = uint(voxelIndex) >> 12u;
    uint v = uint(voxelIndex) & (VS_CORE_VOXELS - 1u);
    return (vs_solid[slot * VS_CORE_SOLID_INTS + (v >> 5u)] & (1u << (v & 31u))) != 0u;
}

/**
 * Working-buffer index for one voxel of a section's PADDED 18^3 volume. Border voxels resolve into
 * the neighbouring section through the LUT; -1 when that neighbour isn't tracked.
 */
int vs_paddedVoxelIndex(ivec3 sectionPos, int slot, uint i) {
    int x = int(i % 18u);
    uint t = i / 18u;
    int z = int(t % 18u);
    int y = int(t / 18u);
    ivec3 c = ivec3(x - 1, y - 1, z - 1);
    if (all(greaterThanEqual(c, ivec3(0))) && all(lessThan(c, ivec3(16)))) {
        return slot * int(VS_CORE_VOXELS) + int(vs_coreIndex(c));
    }
    return vs_voxelIndexAt((sectionPos << 4) + c);
}
