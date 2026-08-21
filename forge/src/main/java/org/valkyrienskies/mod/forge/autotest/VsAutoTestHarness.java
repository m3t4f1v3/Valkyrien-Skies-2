package org.valkyrienskies.mod.forge.autotest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.util.datastructures.DenseBlockPosSet;
import org.valkyrienskies.mod.common.assembly.ShipAssemblyKt;

/**
 * Script-driven in-game test harness with screenshot capture, for driving the dev client without a human (or a
 * pile of xdotool). Inert unless the JVM property {@code vs.autotest} names a script file, wired through the
 * {@code vs_autotest} Gradle property — see {@code forge/build.gradle} and {@code autotest/run.sh}.
 *
 * <p>Ported from the Simulated-Project harness of the same design, trimmed to what VS itself needs and with
 * lighting-specific hooks added. The point of running here rather than there is Sodium: the ship-to-world light
 * path only exists behind Sodium's chunk-shader swap, and that project has no Sodium at runtime.
 *
 * <p>The script is line-based; {@code #} starts a comment. One instruction runs per client tick (except waits):
 * <pre>
 *   load-world &lt;dir&gt;      open an existing save by directory name
 *   world &lt;name&gt;         create a fresh superflat creative world
 *   wait-level             block until the player exists and no screen is open
 *   wait &lt;ticks&gt;         idle N client ticks
 *   cmd &lt;command&gt;        run a command as the player (no leading slash)
 *   look &lt;yaw&gt; &lt;pitch&gt; set the client player's rotation
 *   hud on|off            show/hide the HUD — off makes every pixel of a screenshot world content,
 *                         which matters when a shot is going to be measured rather than eyeballed
 *   run &lt;hook args...&gt;   invoke a named Java hook (below)
 *   screenshot &lt;name&gt;    capture the framebuffer to screenshots/&lt;name&gt;.png
 *   log &lt;message&gt;        marker line into the log
 *   quit                   stop the client
 * </pre>
 *
 * <p>Hooks:
 * <pre>
 *   light_ship &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;emitter&gt;   a 3x3x3 hull with an emitter block sealed at its centre,
 *                                                assembled into a ship and parked (static) at that world position
 *   hull open|closed                            remove / restore the centre block of that ship's hull floor
 *   ship_light_info                             log the ship's shipyard origin and world transform
 *   rotate_ship &lt;yaw&gt; &lt;pitch&gt; &lt;roll&gt;   turn the ship in place, in degrees
 *   move_ship &lt;dx&gt; &lt;dy&gt; &lt;dz&gt;          park the ship at spawn+delta, keeping its rotation;
 *                                                fractional deltas take it off the world block lattice
 * </pre>
 */
public final class VsAutoTestHarness {
    private static final Logger LOGGER = LoggerFactory.getLogger("VS2-autotest");
    private static final int WAIT_LEVEL_TIMEOUT_TICKS = 20 * 60 * 5;
    private static final long EXIT_GRACE_MILLIS = 10_000L;

    private List<String[]> instructions;
    private Path scriptPath;
    private int pc;
    private int waitTicks;
    private int burstRemaining;
    private int burstIndex;
    private String burstPrefix = "burst";
    private boolean waitingForLevel;
    private int waitedForLevel;
    private boolean started;
    private boolean done;

    /** Shipyard position of the emitter at the centre of the light-test ship, for the {@code hull} hook. */
    private static BlockPos lightShipCentre;
    private static ServerShip lightShip;
    private static Block lightShipEmitter;
    /** Where light_ship parked the ship, so move_ship can offset from it instead of accumulating. */
    private static org.joml.Vector3dc lightShipSpawnPos;
    /**
     * Scene ships for the AO fixtures. The seam-AO situations in claude-scratchpad (verify4/verify5,
     * scenes.py) are all comparisons between arrangements of SEVERAL small ships -- cross-ship splits
     * of one layout, a pair at sub-block lateral offsets, one ship yawed against a straight one -- so
     * they need per-ship control that the single `lightShip` slot cannot express.
     */
    private static final ServerShip[] sceneShips = new ServerShip[8];
    private static final org.joml.Vector3dc[] sceneSpawnPos = new org.joml.Vector3dc[8];
    /** Per-tick drag delta, applied every tick while non-null; see {@link #dragShip}. */
    private static org.joml.Vector3dc dragDelta;

    public static void install() {
        final String script = System.getProperty("vs.autotest");
        if (script == null || script.isBlank()) {
            return;
        }
        final VsAutoTestHarness harness = new VsAutoTestHarness();
        harness.scriptPath = Path.of(script);
        try {
            final List<String[]> parsed = new ArrayList<>();
            for (String line : Files.readAllLines(harness.scriptPath)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                parsed.add(line.split("\\s+"));
            }
            harness.instructions = parsed;
        } catch (final IOException e) {
            throw new IllegalStateException("autotest script unreadable: " + script, e);
        }
        MinecraftForge.EVENT_BUS.addListener(harness::onClientTick);
        LOGGER.info("[autotest] installed with {} instructions from {}", harness.instructions.size(), script);
    }

    private void onClientTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || this.done) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        // Hold until the game is interactive (loading overlay gone) so `world` fires from the title screen.
        if (!this.started) {
            if (minecraft.getOverlay() != null) {
                return;
            }
            this.started = true;
            LOGGER.info("[autotest] starting script");
        }
        try {
            this.step(minecraft);
        } catch (final Throwable t) {
            LOGGER.error("[autotest] FAILED at instruction {} of {}", this.pc, this.instructions.size(), t);
            this.finish(minecraft, "FAIL instruction " + this.pc + ": " + t);
        }
    }

    private void step(final Minecraft minecraft) {
        applyDrag(minecraft);
        // A burst grabs one frame per tick with nothing else running in between, so the frames form a
        // real time series. Flicker is a frame-to-frame property and cannot be seen in settled shots
        // taken seconds apart -- the first attempt at a motion test did exactly that and found nothing.
        if (this.burstRemaining > 0) {
            this.burstRemaining--;
            screenshot(minecraft, String.format("%s_%03d", this.burstPrefix, this.burstIndex++));
            return;
        }
        if (this.waitTicks > 0) {
            this.waitTicks--;
            return;
        }
        if (this.waitingForLevel) {
            // A save made with mods this dev instance does not have stops on the datapack-failure
            // prompt. Its first button is "proceed anyway", which is what a human would click; without
            // this the harness just times out staring at the screen.
            if (minecraft.screen instanceof net.minecraft.client.gui.screens.DatapackLoadFailureScreen) {
                for (final net.minecraft.client.gui.components.events.GuiEventListener child
                    : minecraft.screen.children()) {
                    if (child instanceof net.minecraft.client.gui.components.Button button) {
                        LOGGER.info("[autotest] datapack failure screen: pressing '{}'",
                            button.getMessage().getString());
                        button.onPress();
                        break;
                    }
                }
                return;
            }
            if (minecraft.player != null && minecraft.level != null && minecraft.screen == null) {
                this.waitingForLevel = false;
                LOGGER.info("[autotest] level ready after {} ticks", this.waitedForLevel);
            } else if (++this.waitedForLevel > WAIT_LEVEL_TIMEOUT_TICKS) {
                throw new IllegalStateException("world never became ready (screen=" + minecraft.screen + ")");
            } else {
                return;
            }
        }
        if (this.pc >= this.instructions.size()) {
            this.finish(minecraft, "OK");
            return;
        }

        final String[] inst = this.instructions.get(this.pc++);
        LOGGER.info("[autotest] [{}] {}", this.pc - 1, String.join(" ", inst));
        switch (inst[0].toLowerCase(Locale.ROOT)) {
            case "world" -> this.createWorld(minecraft, inst[1]);
            case "load-world" -> this.loadWorld(minecraft, join(inst, 1));
            case "wait-level" -> {
                this.waitingForLevel = true;
                this.waitedForLevel = 0;
            }
            case "wait" -> this.waitTicks = Integer.parseInt(inst[1]);
            case "burst" -> {
                this.burstRemaining = Integer.parseInt(inst[1]);
                this.burstPrefix = inst.length > 2 ? inst[2] : "burst";
                this.burstIndex = 0;
            }
            case "cmd" -> minecraft.player.connection.sendCommand(join(inst, 1));
            case "look" -> {
                minecraft.player.setYRot(Float.parseFloat(inst[1]));
                minecraft.player.setXRot(Float.parseFloat(inst[2]));
            }
            case "run" -> runHook(minecraft, inst);
            // Flipping the config in game rather than relaunching: the shader programs are cached by
            // feature bits, so the next draw compiles the variant for the new setting.
            case "aoinfo" -> LOGGER.info(
                "[autotest] aoinfo: config={} occluderListSize={} emitterListSize={} floodActive={}"
                    + " seamRuns={} boundsCenter=({},{},{}) boundsRadius={}",
                org.valkyrienskies.mod.common.config.VSGameConfig.CLIENT.getShipAmbientOcclusion(),
                org.valkyrienskies.mod.common.render.light.VsDynamicLight.getShipOccluderList().size(),
                org.valkyrienskies.mod.common.render.light.VsDynamicLight.getShipEmitterList().size(),
                org.valkyrienskies.mod.common.render.light.VsDynamicLight.isGpuFloodActive(),
                org.valkyrienskies.mod.common.render.light.VsDynamicLight.getShipOccluderList().headerCount(),
                org.valkyrienskies.mod.common.render.light.VsDynamicLight.getShipOccluderList().boundsCenterX(),
                org.valkyrienskies.mod.common.render.light.VsDynamicLight.getShipOccluderList().boundsCenterY(),
                org.valkyrienskies.mod.common.render.light.VsDynamicLight.getShipOccluderList().boundsCenterZ(),
                org.valkyrienskies.mod.common.render.light.VsDynamicLight.getShipOccluderList().boundsRadius());
            case "ao" -> {
                org.valkyrienskies.mod.common.config.VSGameConfig.CLIENT
                    .setShipAmbientOcclusion("on".equalsIgnoreCase(inst[1]));
                LOGGER.info("[autotest] shipAmbientOcclusion -> {}",
                    org.valkyrienskies.mod.common.config.VSGameConfig.CLIENT.getShipAmbientOcclusion());
            }
            case "hud" -> minecraft.options.hideGui = !"on".equalsIgnoreCase(inst[1]);
            // Switch the debug paint at runtime. Fixtures use this to take a masking shot (paint 0,
            // ordinary render, ships identifiable as stone) and a measuring shot (paint 5, the AO loss
            // field) of the SAME frame, so the ships' own pixels can be excluded from a floor integral.
            case "paint" -> {
                org.valkyrienskies.mod.common.config.VSGameConfig.CLIENT
                    .setDebugFloodPaint(Integer.parseInt(inst[1]));
                LOGGER.info("[autotest] debugFloodPaint -> {}",
                    org.valkyrienskies.mod.common.config.VSGameConfig.CLIENT.getDebugFloodPaint());
            }
            case "screenshot" -> screenshot(minecraft, inst[1]);
            case "log" -> LOGGER.info("[autotest] MARK: {}", join(inst, 1));
            case "quit" -> this.finish(minecraft, "OK");
            default -> throw new IllegalArgumentException("unknown instruction: " + inst[0]);
        }
    }

    private static void runHook(final Minecraft minecraft, final String[] inst) {
        switch (inst[1].toLowerCase(Locale.ROOT)) {
            case "light_ship" -> spawnLightShip(minecraft,
                Integer.parseInt(inst[2]), Integer.parseInt(inst[3]), Integer.parseInt(inst[4]),
                blockByName(inst[5]));
            case "hull" -> setHull(minecraft, "open".equalsIgnoreCase(inst[2]));
            case "ship_light_info" -> logShipLightInfo(minecraft);
            case "rotate_ship" -> rotateShip(minecraft,
                Double.parseDouble(inst[2]), Double.parseDouble(inst[3]), Double.parseDouble(inst[4]));
            case "light_probe" -> lightProbe(minecraft);
            case "emitter" -> setEmitter(minecraft, "on".equalsIgnoreCase(inst[2]));
            case "plate_ship" -> spawnPlateShip(minecraft, Integer.parseInt(inst[2]),
                Integer.parseInt(inst[3]), Integer.parseInt(inst[4]), Integer.parseInt(inst[5]),
                inst.length > 6 ? Double.parseDouble(inst[6]) : 0.0,
                inst.length > 7 ? Double.parseDouble(inst[7]) : 0.0);
            case "light_shaft" -> spawnShaftShip(minecraft, Integer.parseInt(inst[2]),
                Integer.parseInt(inst[3]), Integer.parseInt(inst[4]), Integer.parseInt(inst[5]),
                blockByName(inst[6]));
            case "fps" -> LOGGER.info("[autotest] fps {}: {}", inst.length > 2 ? inst[2] : "", minecraft.getFps());
            case "drag_ship" -> dragDelta = new org.joml.Vector3d(
                Double.parseDouble(inst[2]), Double.parseDouble(inst[3]), Double.parseDouble(inst[4]));
            case "drag_stop" -> dragDelta = null;
            case "ao_ship" -> spawnSceneShip(minecraft, Integer.parseInt(inst[2]),
                Integer.parseInt(inst[3]), Integer.parseInt(inst[4]), Integer.parseInt(inst[5]),
                inst[6]);
            case "ao_pose" -> poseSceneShip(minecraft, Integer.parseInt(inst[2]),
                Double.parseDouble(inst[3]), Double.parseDouble(inst[4]), Double.parseDouble(inst[5]),
                inst.length > 6 ? Double.parseDouble(inst[6]) : 0.0);
            case "ao_clear" -> clearSceneShips(minecraft);
            case "drift_ship" -> driftShip(minecraft,
                Double.parseDouble(inst[2]), Double.parseDouble(inst[3]), Double.parseDouble(inst[4]));
            case "move_ship" -> moveShip(minecraft,
                Double.parseDouble(inst[2]), Double.parseDouble(inst[3]), Double.parseDouble(inst[4]));
            default -> throw new IllegalArgumentException("unknown hook: " + inst[1]);
        }
    }

    private static Block blockByName(final String name) {
        return BuiltInRegistries.BLOCK.get(new ResourceLocation(name));
    }

    /**
     * Builds a 3x3x3 hull with {@code emitter} sealed at its centre and assembles it into a ship.
     *
     * <p>The whole box is laid down in world space and assembled in one go, so every block lands in the ship's own
     * shipyard chunks. Building part of it afterwards would put blocks outside the chunks the client is told
     * about, and the client would then only ever see a fragment of the ship.
     */
    private static void spawnLightShip(final Minecraft minecraft, final int x, final int y, final int z,
        final Block emitter) {
        final MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("light_ship: no integrated server");
        }
        final ResourceKey<Level> dimension = minecraft.level.dimension();
        server.execute(() -> {
            final ServerLevel level = server.getLevel(dimension);
            final DenseBlockPosSet blocks = new DenseBlockPosSet();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        final BlockPos pos = new BlockPos(x + dx, y + dy, z + dz);
                        final boolean centre = dx == 0 && dy == 0 && dz == 0;
                        level.setBlock(pos, (centre ? emitter : Blocks.STONE).defaultBlockState(), 3);
                        blocks.add(pos.getX(), pos.getY(), pos.getZ());
                    }
                }
            }
            final ServerShip ship =
                ShipAssemblyKt.createNewShipWithBlocks(new BlockPos(x, y, z), blocks, level);
            ship.setStatic(true);
            lightShip = ship;
            lightShipEmitter = emitter;
            lightShipSpawnPos = new org.joml.Vector3d(ship.getTransform().getPositionInWorld());
            // Find the emitter by scanning the ship's shipyard AABB rather than deriving it from the
            // centre of rotation. The centre of rotation is the centre of MASS, which is only the same
            // block by coincidence for a uniform box, and is not the same block at all once the ship is
            // built differently — deriving it that way made the `hull` hook edit an arbitrary block and
            // silently do nothing, so every sealed/open screenshot pair came out identical.
            lightShipCentre = null;
            final org.joml.primitives.AABBic aabb = ship.getShipAABB();
            if (aabb != null) {
                outer:
                for (int sx = aabb.minX(); sx < aabb.maxX(); sx++) {
                    for (int sy = aabb.minY(); sy < aabb.maxY(); sy++) {
                        for (int sz = aabb.minZ(); sz < aabb.maxZ(); sz++) {
                            final BlockPos p = new BlockPos(sx, sy, sz);
                            if (level.getBlockState(p).is(emitter)) {
                                lightShipCentre = p;
                                break outer;
                            }
                        }
                    }
                }
            }
            LOGGER.info("[autotest] light_ship id={} world=({},{},{}) emitterShipyardPos={} emitter={}",
                ship.getId(), x, y, z, lightShipCentre, emitter);
            if (lightShipCentre == null) {
                LOGGER.error("[autotest] light_ship: emitter {} not found in the ship's shipyard AABB {}",
                    emitter, aabb);
            }
        });
    }

    /** Opens or closes the centre block of the light ship's hull floor, in shipyard space. */
    private static void setHull(final Minecraft minecraft, final boolean open) {
        final MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null || lightShipCentre == null) {
            throw new IllegalStateException("hull: no light ship spawned yet");
        }
        final ResourceKey<Level> dimension = minecraft.level.dimension();
        server.execute(() -> {
            final ServerLevel level = server.getLevel(dimension);
            final BlockPos floor = lightShipCentre.below();
            final var before = level.getBlockState(floor).getBlock();
            level.setBlock(floor, (open ? Blocks.AIR : Blocks.STONE).defaultBlockState(), 3);
            final var after = level.getBlockState(floor).getBlock();
            LOGGER.info("[autotest] hull {} at shipyard {}: {} -> {}{}",
                open ? "OPEN" : "CLOSED", floor, before, after,
                before == after ? "  *** NO-OP: the hull did not change ***" : "");
        });
    }

    /**
     * Yaw/pitch/roll the light ship in degrees, keeping its position, to exercise the rotated path.
     *
     * <p>Takes exactly the route {@code /vs teleport} takes — build a {@code ShipTeleportData}, hand it
     * to {@code vsCore.teleportShip} — and touches nothing else, {@code setStatic} included.
     *
     * <p>Two things make this hook easy to believe wrongly. A teleport is applied by the PHYSICS tick,
     * not by this call, so the rotation read back here is still the old one; only a {@code wait} plus
     * {@code ship_light_info} proves the ship turned. And anything thrown in here would otherwise take
     * the server thread down quietly while the client kept rendering a perfectly normal-looking frame,
     * so every measurement after it would be of an unrotated ship. Hence the catch-and-log.
     */
    private static void rotateShip(final Minecraft minecraft, final double yawDeg, final double pitchDeg,
        final double rollDeg) {
        final MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null || lightShip == null) {
            throw new IllegalStateException("rotate_ship: no light ship spawned yet");
        }
        final ResourceKey<Level> dimension = minecraft.level.dimension();
        server.execute(() -> {
            try {
                final ServerLevel serverLevel = server.getLevel(dimension);
                final String dimensionId = org.valkyrienskies.mod.common.VSGameUtilsKt.getDimensionId(serverLevel);
                final org.joml.Quaterniond rot = new org.joml.Quaterniond()
                    .rotateY(java.lang.Math.toRadians(yawDeg))
                    .rotateX(java.lang.Math.toRadians(pitchDeg))
                    .rotateZ(java.lang.Math.toRadians(rollDeg));
                final org.valkyrienskies.core.api.world.ServerShipWorld shipWorld =
                    (org.valkyrienskies.core.api.world.ServerShipWorld)
                        org.valkyrienskies.mod.common.VSGameUtilsKt.getShipObjectWorld(server);
                final var core = org.valkyrienskies.mod.common.VSGameUtilsKt.getVsCore();
                LOGGER.info("[autotest] rotate_ship id={} static={} yaw={} pitch={} roll={} rotBefore={}",
                    lightShip.getId(), lightShip.isStatic(), yawDeg, pitchDeg, rollDeg,
                    lightShip.getTransform().getShipToWorldRotation());
                // newVel and newOmega are @NotNull in the core API even though the Kotlin declaration
                // gives them defaults, so a Java caller passing null gets an NPE out of the intrinsic
                // null check -- which is what silently defeated this hook. Zero is the right value for
                // a test ship anyway; the trailing three are genuinely nullable.
                core.teleportShip(shipWorld, lightShip, core.newShipTeleportData(
                    lightShip.getTransform().getPositionInWorld(), rot,
                    new org.joml.Vector3d(), new org.joml.Vector3d(), dimensionId, null, null));
                LOGGER.info("[autotest] rotate_ship queued; applies on a later physics tick");
            } catch (final Throwable t) {
                LOGGER.error("[autotest] rotate_ship FAILED -- every later measurement is of an "
                    + "UNROTATED ship", t);
            }
        });
    }

    /**
     * Park the ship at its spawn position plus {@code (dx, dy, dz)}, keeping whatever rotation it has.
     *
     * <p>Fractional deltas are the point: they take the ship off the world block lattice, which is the
     * other case where mapping ship voxels onto world cells stops being one-to-one. Offsets are applied
     * to the SPAWN position rather than the current one so repeated calls in a script cannot drift.
     */
    private static void moveShip(final Minecraft minecraft, final double dx, final double dy,
        final double dz) {
        final MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null || lightShip == null || lightShipSpawnPos == null) {
            throw new IllegalStateException("move_ship: no light ship spawned yet");
        }
        final ResourceKey<Level> dimension = minecraft.level.dimension();
        server.execute(() -> {
            try {
                final ServerLevel serverLevel = server.getLevel(dimension);
                final String dimensionId = org.valkyrienskies.mod.common.VSGameUtilsKt.getDimensionId(serverLevel);
                final org.joml.Vector3d target = new org.joml.Vector3d(lightShipSpawnPos).add(dx, dy, dz);
                final org.valkyrienskies.core.api.world.ServerShipWorld shipWorld =
                    (org.valkyrienskies.core.api.world.ServerShipWorld)
                        org.valkyrienskies.mod.common.VSGameUtilsKt.getShipObjectWorld(server);
                final var core = org.valkyrienskies.mod.common.VSGameUtilsKt.getVsCore();
                core.teleportShip(shipWorld, lightShip, core.newShipTeleportData(
                    target, lightShip.getTransform().getShipToWorldRotation(),
                    new org.joml.Vector3d(), new org.joml.Vector3d(), dimensionId, null, null));
                LOGGER.info("[autotest] move_ship id={} delta=({} {} {}) -> target={} queued",
                    lightShip.getId(), dx, dy, dz, target);
            } catch (final Throwable t) {
                LOGGER.error("[autotest] move_ship FAILED -- every later measurement is of a ship that "
                    + "did NOT move", t);
            }
        });
    }

    /**
     * Set the ship moving under its own steam at {@code (vx, vy, vz)} blocks/second, dropping static so
     * the physics pipeline will actually carry it.
     *
     * <p>Real velocity rather than a teleport per tick: a teleport every tick moves the ship in visible
     * 20 Hz steps, which is itself stepped motion and would be indistinguishable from the flicker the
     * test is looking for. Under velocity the render transform interpolates every frame, which is the
     * condition a player actually sees.
     */
    private static void driftShip(final Minecraft minecraft, final double vx, final double vy,
        final double vz) {
        final MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null || lightShip == null) {
            throw new IllegalStateException("drift_ship: no light ship spawned yet");
        }
        final ResourceKey<Level> dimension = minecraft.level.dimension();
        server.execute(() -> {
            try {
                final ServerLevel serverLevel = server.getLevel(dimension);
                final String dimensionId = org.valkyrienskies.mod.common.VSGameUtilsKt.getDimensionId(serverLevel);
                final var core = org.valkyrienskies.mod.common.VSGameUtilsKt.getVsCore();
                final org.valkyrienskies.core.api.world.ServerShipWorld shipWorld =
                    (org.valkyrienskies.core.api.world.ServerShipWorld)
                        org.valkyrienskies.mod.common.VSGameUtilsKt.getShipObjectWorld(server);
                lightShip.setStatic(false);
                core.teleportShip(shipWorld, lightShip, core.newShipTeleportData(
                    lightShip.getTransform().getPositionInWorld(),
                    lightShip.getTransform().getShipToWorldRotation(),
                    new org.joml.Vector3d(vx, vy, vz), new org.joml.Vector3d(),
                    dimensionId, null, null));
                LOGGER.info("[autotest] drift_ship id={} vel=({} {} {}) static now false",
                    lightShip.getId(), vx, vy, vz);
            } catch (final Throwable t) {
                LOGGER.error("[autotest] drift_ship FAILED -- the ship is NOT moving", t);
            }
        });
    }

    /**
     * Dump vanilla's BLOCK light through the ship's shipyard volume, on the CLIENT.
     *
     * <p>Everything downstream assumes the vanilla light engine has already flooded light inside a ship
     * -- through its apertures, stopped by its hull, in the ship's own grid. Shipyard chunks sit
     * millions of blocks from the player, so that is worth confirming rather than assuming: if these
     * read 0, the light engine is not running there and nothing built on it can work.
     */
    private static void lightProbe(final Minecraft minecraft) {
        if (lightShip == null || lightShipCentre == null) {
            LOGGER.info("[autotest] light_probe: no ship");
            return;
        }
        final ClientLevel level = minecraft.level;
        final BlockPos c = lightShipCentre;
        LOGGER.info("[autotest] light_probe: client BLOCK light around shipyard emitter {}", c);
        for (int dy = 2; dy >= -2; dy--) {
            final StringBuilder row = new StringBuilder();
            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) {
                    final BlockPos p = c.offset(dx, dy, dz);
                    row.append(String.format("%2d", level.getBrightness(LightLayer.BLOCK, p)));
                    row.append(level.getBlockState(p).isAir() ? "." : "#");
                }
                row.append(" | ");
            }
            LOGGER.info("[autotest] light_probe: dy={} {}", dy, row);
        }
    }

    /**
     * Move the ship by a fixed delta every tick, keeping it static -- i.e. dragging it, the way a
     * player does.
     *
     * <p>This exists because {@code drift_ship}, which hands the ship a velocity and lets physics carry
     * it, does not stay in motion: the ship is no longer static, so it falls the few blocks to the
     * ground and stops within about six ticks. Every flicker measurement taken with it was therefore of
     * a ship sitting still -- the lit pool was byte-identical across frames 14 to 24 while the numbers
     * suggested motion. A position-controlled drag keeps moving for as long as the script says.
     */
    private static void applyDrag(final Minecraft minecraft) {
        final org.joml.Vector3dc delta = dragDelta;
        if (delta == null || lightShip == null || minecraft.level == null) {
            return;
        }
        final MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }
        final ResourceKey<Level> dimension = minecraft.level.dimension();
        server.execute(() -> {
            try {
                final ServerLevel serverLevel = server.getLevel(dimension);
                final String dimensionId = org.valkyrienskies.mod.common.VSGameUtilsKt.getDimensionId(serverLevel);
                final var core = org.valkyrienskies.mod.common.VSGameUtilsKt.getVsCore();
                final org.valkyrienskies.core.api.world.ServerShipWorld shipWorld =
                    (org.valkyrienskies.core.api.world.ServerShipWorld)
                        org.valkyrienskies.mod.common.VSGameUtilsKt.getShipObjectWorld(server);
                final org.joml.Vector3d target =
                    new org.joml.Vector3d(lightShip.getTransform().getPositionInWorld()).add(delta);
                core.teleportShip(shipWorld, lightShip, core.newShipTeleportData(
                    target, lightShip.getTransform().getShipToWorldRotation(),
                    new org.joml.Vector3d(), new org.joml.Vector3d(), dimensionId, null, null));
            } catch (final Throwable t) {
                LOGGER.error("[autotest] drag_ship FAILED -- the ship is NOT moving", t);
                dragDelta = null;
            }
        });
    }

    /**
     * A solid stone block with a one-wide HORIZONTAL shaft bored through it, an emitter sealed at the
     * inner end, and the shaft opening on one side face.
     *
     * <p>Sized so light arrives at the mouth with strength {@code 14 - shaftLen}: at 14 it reaches open
     * air with exactly nothing left. Anything visible outside the hull in that case is light the
     * propagation says does not exist.
     *
     * <p>Horizontal and viewed from above on purpose. A vertical shaft photographed from overhead just
     * looks down the bore at the glowstone, which says nothing about what escapes; with the shaft
     * horizontal, an overhead shot sees the hull's top face and the ground around it -- the surfaces
     * that must stay black -- while the mouth is edge-on.
     */
    private static void spawnShaftShip(final Minecraft minecraft, final int x, final int y, final int z,
        final int shaftLen, final Block emitter) {
        final MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("light_shaft: no integrated server");
        }
        final ResourceKey<Level> dimension = minecraft.level.dimension();
        server.execute(() -> {
            final ServerLevel level = server.getLevel(dimension);
            final DenseBlockPosSet blocks = new DenseBlockPosSet();
            for (int dx = 0; dx <= shaftLen + 2; dx++) {
                for (int dy = 0; dy <= 4; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        final BlockPos pos = new BlockPos(x + dx, y + dy, z + dz);
                        // The emitter sits at dx=1 with a stone wall at dx=0 in front of it, so it is
                        // enclosed on every side except the shaft. Putting it at dx=0 -- on the box's
                        // own outer face -- leaves it exposed to open air, and its light then walks
                        // straight out of the hull without ever entering the shaft: every shaft length
                        // reported the same ~250 exit cells at strength 14 and the same glow at the
                        // emitter end, which is a broken fixture, not a broken flood.
                        final boolean source = dx == 1 && dy == 2 && dz == 0;
                        final boolean shaft = dx > 1 && dy == 2 && dz == 0;
                        if (shaft) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                        } else {
                            level.setBlock(pos,
                                (source ? emitter : Blocks.STONE).defaultBlockState(), 3);
                        }
                        blocks.add(pos.getX(), pos.getY(), pos.getZ());
                    }
                }
            }
            final ServerShip ship =
                ShipAssemblyKt.createNewShipWithBlocks(new BlockPos(x, y, z), blocks, level);
            ship.setStatic(true);
            lightShip = ship;
            lightShipEmitter = emitter;
            lightShipSpawnPos = new org.joml.Vector3d(ship.getTransform().getPositionInWorld());
            lightShipCentre = null;
            final org.joml.primitives.AABBic aabb = ship.getShipAABB();
            if (aabb != null) {
                outer:
                for (int sx = aabb.minX(); sx < aabb.maxX(); sx++) {
                    for (int sy = aabb.minY(); sy < aabb.maxY(); sy++) {
                        for (int sz = aabb.minZ(); sz < aabb.maxZ(); sz++) {
                            final BlockPos pp = new BlockPos(sx, sy, sz);
                            if (level.getBlockState(pp).is(emitter)) {
                                lightShipCentre = pp;
                                break outer;
                            }
                        }
                    }
                }
            }
            LOGGER.info("[autotest] light_shaft id={} at ({} {} {}) horizontal shaftLen={} -> light "
                + "reaches the mouth at strength {}; emitter at shipyard {}", ship.getId(), x, y, z,
                shaftLen, Math.max(0, 14 - shaftLen), lightShipCentre);
        });
    }

    /**
     * Swap the ship's emitter block for stone and back.
     *
     * <p>Overhead shots of a ship sitting in a superflat world are dominated by moonlight on its top
     * face -- bright enough (max 252 at midnight) to bury anything the ship itself contributes. A pair
     * of shots differing only in whether the emitter exists cancels all of that, leaving exactly the
     * ship's own light.
     */
    private static void setEmitter(final Minecraft minecraft, final boolean on) {
        final MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null || lightShipCentre == null) {
            throw new IllegalStateException("emitter: no ship with a known emitter");
        }
        final ResourceKey<Level> dimension = minecraft.level.dimension();
        final Block emitter = lightShipEmitter;
        server.execute(() -> {
            final ServerLevel level = server.getLevel(dimension);
            final Block before = level.getBlockState(lightShipCentre).getBlock();
            level.setBlock(lightShipCentre,
                (on ? emitter : Blocks.STONE).defaultBlockState(), 3);
            final Block after = level.getBlockState(lightShipCentre).getBlock();
            LOGGER.info("[autotest] emitter {} at shipyard {}: {} -> {}{}", on ? "ON" : "OFF",
                lightShipCentre, before, after,
                before == after ? "  *** NO-OP ***" : "");
        });
    }

    /**
     * A flat unlit stone plate assembled as its OWN ship — the receiver for ship-to-ship lighting.
     *
     * <p>It has to be a separate ship, not more blocks on the lit one: light reaching a ship's own
     * blocks goes through the self-lighting path, and what is under test here is the ship-on-ship path,
     * which only runs for light arriving from elsewhere. Deliberately holds no emitter, so anything
     * that lights it came from another ship.
     */
    private static void spawnPlateShip(final Minecraft minecraft, final int x, final int y, final int z,
        final int halfSize, final double yawDeg, final double fracOffset) {
        final MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("plate_ship: no integrated server");
        }
        final ResourceKey<Level> dimension = minecraft.level.dimension();
        server.execute(() -> {
            final ServerLevel level = server.getLevel(dimension);
            final DenseBlockPosSet blocks = new DenseBlockPosSet();
            for (int dx = -halfSize; dx <= halfSize; dx++) {
                for (int dz = -halfSize; dz <= halfSize; dz++) {
                    final BlockPos pos = new BlockPos(x + dx, y, z + dz);
                    level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
                    blocks.add(pos.getX(), pos.getY(), pos.getZ());
                }
            }
            final ServerShip ship =
                ShipAssemblyKt.createNewShipWithBlocks(new BlockPos(x, y, z), blocks, level);
            ship.setStatic(true);
            // Optionally taken OFF the block lattice and turned, because a receiver sitting exactly on
            // integer coordinates hides every quantisation effect the grid can produce -- which is the
            // condition worth testing, not the tidy one.
            if (yawDeg != 0.0 || fracOffset != 0.0) {
                try {
                    final String dimensionId =
                        org.valkyrienskies.mod.common.VSGameUtilsKt.getDimensionId(level);
                    final var core = org.valkyrienskies.mod.common.VSGameUtilsKt.getVsCore();
                    final org.valkyrienskies.core.api.world.ServerShipWorld shipWorld =
                        (org.valkyrienskies.core.api.world.ServerShipWorld)
                            org.valkyrienskies.mod.common.VSGameUtilsKt.getShipObjectWorld(server);
                    final org.joml.Vector3d target =
                        new org.joml.Vector3d(ship.getTransform().getPositionInWorld())
                            .add(fracOffset, fracOffset * 0.5, fracOffset * 0.75);
                    core.teleportShip(shipWorld, ship, core.newShipTeleportData(target,
                        new org.joml.Quaterniond().rotateY(java.lang.Math.toRadians(yawDeg)),
                        new org.joml.Vector3d(), new org.joml.Vector3d(), dimensionId, null, null));
                } catch (final Throwable t) {
                    LOGGER.error("[autotest] plate_ship offset FAILED -- receiver is still lattice "
                        + "aligned", t);
                }
            }
            LOGGER.info("[autotest] plate_ship id={} at ({} {} {}) half={} yaw={} frac={} "
                + "(receiver, no emitter)", ship.getId(), x, y, z, halfSize, yawDeg, fracOffset);
        });
    }

    private static void logShipLightInfo(final Minecraft minecraft) {
        if (lightShip == null) {
            LOGGER.info("[autotest] ship_light_info: no ship");
            return;
        }
        LOGGER.info("[autotest] ship_light_info: id={} shipyardCentre={} posInWorld={} aabb={} serverRot={}",
            lightShip.getId(), lightShipCentre, lightShip.getTransform().getPositionInWorld(),
            lightShip.getWorldAABB(), lightShip.getTransform().getShipToWorldRotation());
        // The light flood reads the CLIENT ship's RENDER transform, so that is the only rotation that
        // can explain what a screenshot shows. A server ship that turned while the client copy had not
        // yet been told would look exactly like a shader that fails under rotation.
        if (minecraft.level == null) {
            return;
        }
        for (final org.valkyrienskies.core.api.ships.ClientShip ship
            : org.valkyrienskies.mod.common.VSGameUtilsKt.getShipObjectWorld(minecraft.level).getLoadedShips()) {
            if (ship.getId() != lightShip.getId()) {
                continue;
            }
            LOGGER.info("[autotest] ship_light_info: clientRot={} renderRot={} renderAabb={}",
                ship.getTransform().getShipToWorldRotation(),
                ship.getRenderTransform().getShipToWorldRotation(), ship.getRenderAABB());
        }
    }

    /**
     * Open an EXISTING save by its directory name, rather than generating a fresh flat world.
     *
     * <p>For reproducing something reported in a real world: the ships, their positions and their
     * shapes are the report, and a synthetic 3x3x3 box cannot stand in for them.
     */
    private void loadWorld(final Minecraft minecraft, final String dirName) {
        LOGGER.info("[autotest] loading existing save '{}'", dirName);
        minecraft.createWorldOpenFlows().loadLevel(minecraft.screen, dirName);
        this.waitingForLevel = true;
        this.waitedForLevel = 0;
    }

    private void createWorld(final Minecraft minecraft, final String name) {
        final LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
            new GameRules(), WorldDataConfiguration.DEFAULT);
        final WorldOptions options = new WorldOptions(0L, false, false);
        minecraft.createWorldOpenFlows().createFreshLevel(name, settings, options,
            access -> access.registryOrThrow(Registries.WORLD_PRESET)
                .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
        this.waitingForLevel = true;
        this.waitedForLevel = 0;
    }

    private static void screenshot(final Minecraft minecraft, final String name) {
        Screenshot.grab(minecraft.gameDirectory, name + ".png", minecraft.getMainRenderTarget(),
            component -> LOGGER.info("[autotest] screenshot {}: {}", name, component.getString()));
    }

    private void finish(final Minecraft minecraft, final String result) {
        if (this.done) {
            return;
        }
        this.done = true;
        LOGGER.info("[autotest] finished: {}", result);
        if (!result.startsWith("OK")) {
            screenshot(minecraft, "error");
        }
        try {
            Files.writeString(minecraft.gameDirectory.toPath().resolve("autotest-result.txt"), result + "\n");
        } catch (final IOException e) {
            LOGGER.error("[autotest] could not write result file", e);
        }
        minecraft.execute(minecraft::stop);
        forceExitAfterGracePeriod();
    }

    /**
     * Minecraft's own exit regularly never completes here: {@code Minecraft#stop()} calls {@code System.exit}, the
     * VM starts its exit sequence and then blocks forever on a thread — usually in the GL driver — that never
     * reaches a safepoint. Nothing is logged from the watchdog on purpose: Forge routes stdout through log4j, and
     * a wedged shutdown is exactly the state where writing to it blocks too.
     */
    private static void forceExitAfterGracePeriod() {
        final Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(EXIT_GRACE_MILLIS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Runtime.getRuntime().halt(0);
        }, "vs-autotest-exit-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static String join(final String[] parts, final int from) {
        return String.join(" ", java.util.Arrays.copyOfRange(parts, from, parts.length));
    }
    /**
     * Build a scene ship out of an explicit cell list, so the fixtures can reproduce the exact
     * layouts the scratchpad verifies (single, s_s gap, row3, L, 2x2) and split them across ships.
     *
     * <p>{@code cells} is a comma-separated list of {@code dx:dy:dz} offsets from (ox, oy, oz), e.g.
     * {@code 0:0:0,1:0:0} for a two-block ship. Stone throughout -- these fixtures measure AO, not
     * light, so no emitter is placed.
     */
    private static void spawnSceneShip(final Minecraft minecraft, final int slot, final int ox,
        final int oy, final int oz, final String cells) {
        if (slot < 0 || slot >= sceneShips.length) {
            throw new IllegalArgumentException("ao_ship: slot out of range: " + slot);
        }
        final MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("ao_ship: no integrated server");
        }
        final ResourceKey<Level> dimension = minecraft.level.dimension();
        final String cellSpec = cells;
        server.execute(() -> {
            final ServerLevel level = server.getLevel(dimension);
            final DenseBlockPosSet blocks = new DenseBlockPosSet();
            int count = 0;
            for (final String cell : cellSpec.split(",")) {
                final String[] parts = cell.split(":");
                if (parts.length != 3) {
                    throw new IllegalArgumentException("ao_ship: bad cell '" + cell + "' (want dx:dy:dz)");
                }
                final BlockPos pos = new BlockPos(ox + Integer.parseInt(parts[0]),
                    oy + Integer.parseInt(parts[1]), oz + Integer.parseInt(parts[2]));
                level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
                blocks.add(pos.getX(), pos.getY(), pos.getZ());
                count++;
            }
            final ServerShip ship =
                ShipAssemblyKt.createNewShipWithBlocks(new BlockPos(ox, oy, oz), blocks, level);
            ship.setStatic(true);
            sceneShips[slot] = ship;
            sceneSpawnPos[slot] = new org.joml.Vector3d(ship.getTransform().getPositionInWorld());
            LOGGER.info("[autotest] ao_ship slot={} id={} origin=({},{},{}) cells={} spawnPos={}",
                slot, ship.getId(), ox, oy, oz, count, sceneSpawnPos[slot]);
        });
    }

    /**
     * Offset a scene ship from where it spawned and yaw it. Sub-block offsets are the whole point:
     * the scratchpad's rigidity checks sweep 0 / 0.25 / 0.5 of a block and require the shadow to
     * translate rigidly rather than redistribute over world vertices.
     */
    private static void poseSceneShip(final Minecraft minecraft, final int slot, final double dx,
        final double dy, final double dz, final double yawDeg) {
        if (slot < 0 || slot >= sceneShips.length || sceneShips[slot] == null) {
            throw new IllegalStateException("ao_pose: nothing in slot " + slot);
        }
        final MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("ao_pose: no integrated server");
        }
        final ResourceKey<Level> dimension = minecraft.level.dimension();
        final ServerShip ship = sceneShips[slot];
        final org.joml.Vector3dc spawn = sceneSpawnPos[slot];
        server.execute(() -> {
            try {
                final ServerLevel serverLevel = server.getLevel(dimension);
                final String dimensionId =
                    org.valkyrienskies.mod.common.VSGameUtilsKt.getDimensionId(serverLevel);
                final org.joml.Vector3d target = new org.joml.Vector3d(spawn).add(dx, dy, dz);
                final org.joml.Quaterniond rot =
                    new org.joml.Quaterniond().rotateY(java.lang.Math.toRadians(yawDeg));
                final org.valkyrienskies.core.api.world.ServerShipWorld shipWorld =
                    (org.valkyrienskies.core.api.world.ServerShipWorld)
                        org.valkyrienskies.mod.common.VSGameUtilsKt.getShipObjectWorld(server);
                final var core = org.valkyrienskies.mod.common.VSGameUtilsKt.getVsCore();
                // newVel / newOmega are @NotNull in the core API: passing null throws inside the
                // teleport and every later screenshot silently measures the un-posed ship.
                core.teleportShip(shipWorld, ship, core.newShipTeleportData(
                    target, rot, new org.joml.Vector3d(), new org.joml.Vector3d(),
                    dimensionId, null, null));
                LOGGER.info("[autotest] ao_pose slot={} id={} delta=({},{},{}) yaw={} -> {}",
                    slot, ship.getId(), dx, dy, dz, yawDeg, target);
            } catch (final Throwable t) {
                LOGGER.error("[autotest] ao_pose FAILED -- later screenshots are of an un-posed ship", t);
                throw t;
            }
        });
    }

    /**
     * Take every scene ship out of the frame, so consecutive cases in one fixture cannot contaminate
     * each other.
     *
     * <p>The core API exposes no ship deletion, so this teleports them far away instead. That is
     * genuinely sufficient here rather than a fudge: population is filtered by
     * {@code isShipRelevantToWorldFromShipFrame}, which drops any ship whose render AABB is outside
     * the viewport, so a moved-away ship contributes no occluders at all -- it cannot widen the seam
     * global bounds or the spatial grid either.
     */
    private static void clearSceneShips(final Minecraft minecraft) {
        final MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("ao_clear: no integrated server");
        }
        final ResourceKey<Level> dimension = minecraft.level.dimension();
        server.execute(() -> {
            final ServerLevel serverLevel = server.getLevel(dimension);
            final String dimensionId =
                org.valkyrienskies.mod.common.VSGameUtilsKt.getDimensionId(serverLevel);
            final org.valkyrienskies.core.api.world.ServerShipWorld shipWorld =
                (org.valkyrienskies.core.api.world.ServerShipWorld)
                    org.valkyrienskies.mod.common.VSGameUtilsKt.getShipObjectWorld(server);
            final var core = org.valkyrienskies.mod.common.VSGameUtilsKt.getVsCore();
            int moved = 0;
            for (int i = 0; i < sceneShips.length; i++) {
                if (sceneShips[i] == null) {
                    continue;
                }
                final org.joml.Vector3d away =
                    new org.joml.Vector3d(sceneSpawnPos[i]).add(0.0, 0.0, 4096.0);
                core.teleportShip(shipWorld, sceneShips[i], core.newShipTeleportData(
                    away, sceneShips[i].getTransform().getShipToWorldRotation(),
                    new org.joml.Vector3d(), new org.joml.Vector3d(), dimensionId, null, null));
                sceneShips[i] = null;
                sceneSpawnPos[i] = null;
                moved++;
            }
            LOGGER.info("[autotest] ao_clear moved {} scene ships out of frame", moved);
        });
    }

}
