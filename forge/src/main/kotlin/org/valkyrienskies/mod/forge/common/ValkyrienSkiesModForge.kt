package org.valkyrienskies.mod.forge.common

import net.minecraft.commands.Commands.CommandSelection.ALL
import net.minecraft.commands.Commands.CommandSelection.INTEGRATED
import net.minecraft.commands.synchronization.ArgumentTypeInfos
import net.minecraft.commands.synchronization.SingletonArgumentInfo
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Item.Properties
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntityType
import net.neoforged.fml.ModLoadingContext
import net.neoforged.fml.common.Mod
import net.neoforged.fml.config.ModConfig
import net.neoforged.fml.event.config.ModConfigEvent
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.event.AddReloadListenerEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.TagsUpdatedEvent
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import org.valkyrienskies.mod.client.EmptyRenderer
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.ValkyrienSkiesMod.MOD_ID
import org.valkyrienskies.mod.common.block.TestChairBlock
import org.valkyrienskies.mod.common.block.TestFlapBlock
import org.valkyrienskies.mod.common.block.TestHingeBlock
import org.valkyrienskies.mod.common.block.TestWingBlock
import org.valkyrienskies.mod.common.blockentity.TestHingeBlockEntity
import org.valkyrienskies.mod.common.command.arguments.RelativeVector3Argument
import org.valkyrienskies.mod.common.command.arguments.ShipArgument
import org.valkyrienskies.mod.common.command.VSCommands
import org.valkyrienskies.mod.common.config.MassDatapackResolver
import org.valkyrienskies.mod.common.config.VSConfigUpdater
import org.valkyrienskies.mod.common.config.VSEntityHandlerDataLoader
import org.valkyrienskies.mod.common.config.VSGameConfig
import org.valkyrienskies.mod.common.config.VSKeyBindings
import org.valkyrienskies.mod.common.entity.ShipMountingEntity
import org.valkyrienskies.mod.common.entity.handling.VSEntityManager
import org.valkyrienskies.mod.common.hooks.VSGameEvents
import org.valkyrienskies.mod.common.item.ShipAssemblerItem
import org.valkyrienskies.mod.common.item.ShipCreatorItem
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

@Mod(MOD_ID)
object ValkyrienSkiesModForge {
    private val BLOCKS = DeferredRegister.Blocks.createBlocks(MOD_ID)
    private val ITEMS = DeferredRegister.Items.createItems(MOD_ID)
    private val ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID)
    private val BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID)
    private val DATA_COMPONENTS = DeferredRegister.DataComponents.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID)
    private val COMMAND_ARGUMENT_TYPES = DeferredRegister.create(Registries.COMMAND_ARGUMENT_TYPE, MOD_ID)
    private val TEST_CHAIR_REGISTRY: DeferredBlock<Block>
    private val TEST_HINGE_REGISTRY: DeferredBlock<Block>
    private val TEST_FLAP_REGISTRY: DeferredBlock<Block>
    private val TEST_WING_REGISTRY: DeferredBlock<Block>
    private val CONNECTION_CHECKER_ITEM_REGISTRY: DeferredItem<Item>
    private val SHIP_CREATOR_ITEM_REGISTRY: DeferredItem<Item>
    private val SHIP_CREATOR_SMALLER_ITEM_REGISTRY: DeferredItem<Item>
    private val AREA_ASSEMBLER_ITEM_REGISTRY: DeferredItem<Item>
    private val PHYSICS_ENTITY_CREATOR_ITEM_REGISTRY: DeferredItem<Item>
    private val SHIP_MOUNTING_ENTITY_REGISTRY: DeferredHolder<EntityType<*>, EntityType<ShipMountingEntity>>
    private val SHIP_ASSEMBLER_ITEM_REGISTRY: DeferredItem<Item>
    private val TEST_HINGE_BLOCK_ENTITY_TYPE_REGISTRY: DeferredHolder<BlockEntityType<*>, BlockEntityType<TestHingeBlockEntity>>
    private val BLOCK_POS_COMPONENT: DeferredHolder<DataComponentType<*>, DataComponentType<BlockPos>>

    init {
        val isClient = FMLEnvironment.dist.isClient

        // VS2 1.21.1: set synchronizePhysics=true at mod init, BEFORE any VsiPipeline is
        // created. The pipeline samples this config when it's constructed, so setting it
        // later (e.g. ServerStartedEvent) leaves the pipeline running in async mode and
        // the flag is ignored. Synchronous physics means postTickGame runs
        // physicsTicksPerGameTick (default 3) steps inline per server tick — ships
        // actually move each tick instead of waiting on a wall-clock-scheduled physics
        // thread, which matters for responsiveness and for any workload that ticks the
        // server faster than 20 Hz.
        //
        // Also zero out shipLoadFreezeSeconds. vs-core defaults this to 5.0s, and
        // ShipObjectServerWorld re-arms the freeze on every DenseVoxelShapeUpdate that
        // arrives for a ship. Under heavy voxel streaming these updates keep coming, so
        // the freeze never expires and ship positions get clamped to their spawn pose by
        // effectiveKinematicTarget in VSGamePipelineStage.
        try {
            val pt = org.valkyrienskies.core.impl.config.VSCoreConfig.SERVER.pt
            pt.synchronizePhysics = true
            org.valkyrienskies.core.impl.config.VSCoreConfig.SERVER.physics.shipLoadFreezeSeconds = 0.0
            org.slf4j.LoggerFactory.getLogger("VS2").info(
                "VS2: synchronizePhysics=true, shipLoadFreezeSeconds=0.0 set at mod init, physicsTicksPerGameTick={}",
                pt.physicsTicksPerGameTick)
        } catch (t: Throwable) {
            org.slf4j.LoggerFactory.getLogger("VS2").warn(
                "VS2: failed to set synchronizePhysics at mod init", t)
        }

        ValkyrienSkiesMod.init()
        VSEntityManager.registerContraptionHandler(ContraptionShipyardEntityHandlerForge)

        // Register the ModConfigSpecs with NeoForge so their ConfigValues have a backing
        // Config to read/write. Without this, /vs backend <engine> calls .set(...) on an
        // unbacked ConfigValue and NPEs with "Cannot set config value without assigned
        // Config object present". Each spec lives in its own TOML file named after the
        // spec type to avoid collisions.
        ModLoadingContext.get().activeContainer.apply {
            // Core and mod server configs are both semantically server-side, but NeoForge's
            // ConfigurationScreen labels each tab by Type — two SERVER-typed configs both
            // show up as "Server Settings" buttons with no way to distinguish. Typing the
            // vs-core one as STARTUP sidesteps the collision (tab is labeled "Startup
            // Settings") and is actually a decent semantic fit: physics-backend selection
            // and related fields want to be fixed before the pipeline initializes.
            registerConfig(ModConfig.Type.STARTUP, VSConfigUpdater.CORE_SERVER_SPEC, "valkyrienskies-core-server.toml")
            registerConfig(ModConfig.Type.SERVER, VSConfigUpdater.SERVER_SPEC, "valkyrienskies-server.toml")
            registerConfig(ModConfig.Type.COMMON, VSConfigUpdater.COMMON_SPEC, "valkyrienskies-common.toml")
            registerConfig(ModConfig.Type.CLIENT, VSConfigUpdater.CLIENT_SPEC, "valkyrienskies-client.toml")

            // Wire up the "Config" button in the vanilla Mods menu. NeoForge ships a
            // generic ConfigurationScreen that can render any registered ModConfigSpec,
            // but only activates when a mod registers an IConfigScreenFactory extension
            // point. Without this the button is greyed out. Client-only — guarded by
            // FMLEnvironment.dist so a dedicated server doesn't try to classload Screen.
            if (isClient) {
                registerExtensionPoint(
                    net.neoforged.neoforge.client.gui.IConfigScreenFactory::class.java,
                    net.neoforged.neoforge.client.gui.IConfigScreenFactory { container, parent ->
                        net.neoforged.neoforge.client.gui.ConfigurationScreen(container, parent)
                    }
                )
            }
        }

        // Propagate TOML changes (from disk edits and the in-game config UI) back into the
        // in-memory Kotlin config vars like VSGameConfig.CLIENT.renderDebugText. Without
        // this, toggling anything in the Mods-menu config screen writes to the TOML but
        // the running code keeps reading the default. Handles both first-load and
        // subsequent reloads; Loading fires on mod construction so the bus listener has
        // to be registered here, before we finish constructing.
        MOD_BUS.addListener<ModConfigEvent.Loading> { applyModConfigToVSConfigUpdater(it.config) }
        MOD_BUS.addListener<ModConfigEvent.Reloading> { applyModConfigToVSConfigUpdater(it.config) }

        val modBus = MOD_BUS
        val forgeBus = FORGE_BUS

        BLOCKS.register(modBus)
        ITEMS.register(modBus)
        ENTITIES.register(modBus)
        BLOCK_ENTITIES.register(modBus)
        if (isClient) {
            modBus.addListener(::registerKeyBindings)
            modBus.addListener(::entityRenderers)
        }
        modBus.addListener(::loadComplete)

        forgeBus.addListener(::registerCommands)
        forgeBus.addListener(::tagsUpdated)
        forgeBus.addListener(::registerResourceManagers)
        modBus.addListener(VSForgeNetworking::register)
        DATA_COMPONENTS.register(modBus)
        COMMAND_ARGUMENT_TYPES.register(modBus)

        TEST_CHAIR_REGISTRY = registerBlockAndItem("test_chair") { TestChairBlock() }
        TEST_HINGE_REGISTRY = registerBlockAndItem("test_hinge") { TestHingeBlock }
        TEST_FLAP_REGISTRY = registerBlockAndItem("test_flap") { TestFlapBlock() }
        TEST_WING_REGISTRY = registerBlockAndItem("test_wing") { TestWingBlock() }
        SHIP_CREATOR_ITEM_REGISTRY =
            ITEMS.register("ship_creator") { ->
                ShipCreatorItem(Properties(),
                    { 1.0 },
                    { VSGameConfig.SERVER.minScaling })
            }
        CONNECTION_CHECKER_ITEM_REGISTRY =
            ITEMS.register("connection_checker") { ->
                Item(Properties())
            }
        SHIP_CREATOR_SMALLER_ITEM_REGISTRY =
            ITEMS.register("ship_creator_smaller") { ->
                ShipCreatorItem(
                    Properties(),
                    { VSGameConfig.SERVER.miniShipSize },
                    { VSGameConfig.SERVER.minScaling }
                )
            }
        AREA_ASSEMBLER_ITEM_REGISTRY = ITEMS.register("area_assembler") { ->
            Item(Properties())
        }
        PHYSICS_ENTITY_CREATOR_ITEM_REGISTRY =
            ITEMS.register("physics_entity_creator") { ->
                Item(Properties())
            }

        SHIP_MOUNTING_ENTITY_REGISTRY = ENTITIES.register("ship_mounting_entity") { ->
            EntityType.Builder.of(
                ::ShipMountingEntity,
                MobCategory.MISC
            ).sized(.3f, .3f)
                .build(ResourceLocation.fromNamespaceAndPath(MOD_ID, "ship_mounting_entity").toString())
        }

        SHIP_ASSEMBLER_ITEM_REGISTRY =
            ITEMS.register("ship_assembler") { -> ShipAssemblerItem(Properties()) }

        TEST_HINGE_BLOCK_ENTITY_TYPE_REGISTRY = BLOCK_ENTITIES.register<BlockEntityType<TestHingeBlockEntity>>("test_hinge_block_entity") { ->
            BlockEntityType.Builder.of(::TestHingeBlockEntity, TEST_HINGE_REGISTRY.get()).build(null)
        }

        BLOCK_POS_COMPONENT = DATA_COMPONENTS.register("coordinate") { ->
            DataComponentType.builder<BlockPos>().persistent(BlockPos.CODEC).build()
        }

        COMMAND_ARGUMENT_TYPES.register("ship_argument") { ->
            ArgumentTypeInfos.registerByClass(
                ShipArgument::class.java,
                SingletonArgumentInfo.contextFree(ShipArgument::selectorOnly),
            )
        }

        COMMAND_ARGUMENT_TYPES.register("relative_vector_3") { ->
            ArgumentTypeInfos.registerByClass(
                RelativeVector3Argument::class.java,
                SingletonArgumentInfo.contextFree(RelativeVector3Argument::relativeVector3),
            )
        }

        val deferredRegister = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID)
        deferredRegister.register("general") { ->
            ValkyrienSkiesMod.createCreativeTab()
        }
        deferredRegister.register(modBus)
    }

    private fun registerResourceManagers(event: AddReloadListenerEvent) {
        event.addListener(MassDatapackResolver.loader)
        event.addListener(VSEntityHandlerDataLoader)
    }

    /**
     * Matches the event's ModConfig against one of VSConfigUpdater's four specs and pushes
     * the loaded NightConfig values into the matching VsiConfigModel. Guards the spec check
     * by identity because that's how VSConfigUpdater dispatches internally.
     */
    private fun applyModConfigToVSConfigUpdater(config: ModConfig) {
        val spec = config.spec as? net.neoforged.neoforge.common.ModConfigSpec ?: return
        val loaded = config.loadedConfig?.config() ?: return
        VSConfigUpdater.applyFromConfigLoad(spec) { key -> loaded.get<Any?>(key) }
    }

    private fun registerKeyBindings(event: RegisterKeyMappingsEvent) {
        VSKeyBindings.clientSetup {
            event.register(it)
        }
    }

    private fun entityRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerEntityRenderer(SHIP_MOUNTING_ENTITY_REGISTRY.get(), ::EmptyRenderer)
    }

    private fun registerBlockAndItem(registryName: String, blockSupplier: () -> Block): DeferredBlock<Block> {
        val blockRegistry = BLOCKS.register(registryName, blockSupplier)
        ITEMS.register(registryName) { -> BlockItem(blockRegistry.get(), Properties()) }
        return blockRegistry
    }

    private fun registerCommands(event: RegisterCommandsEvent) {
        VSCommands.registerServerCommands(event.dispatcher)

        if (event.commandSelection == ALL || event.commandSelection == INTEGRATED) {
            VSCommands.registerClientCommands(event.dispatcher)
        }
    }

    private fun tagsUpdated(event: TagsUpdatedEvent) {
        VSGameEvents.tagsAreLoaded.emit(Unit)
    }

    private fun loadComplete(event: FMLLoadCompleteEvent) {
        ValkyrienSkiesMod.TEST_CHAIR = TEST_CHAIR_REGISTRY.get()
        ValkyrienSkiesMod.TEST_HINGE = TEST_HINGE_REGISTRY.get()
        ValkyrienSkiesMod.TEST_FLAP = TEST_FLAP_REGISTRY.get()
        ValkyrienSkiesMod.TEST_WING = TEST_WING_REGISTRY.get()
        ValkyrienSkiesMod.CONNECTION_CHECKER_ITEM = CONNECTION_CHECKER_ITEM_REGISTRY.get()
        ValkyrienSkiesMod.SHIP_CREATOR_ITEM = SHIP_CREATOR_ITEM_REGISTRY.get()
        ValkyrienSkiesMod.SHIP_ASSEMBLER_ITEM = SHIP_ASSEMBLER_ITEM_REGISTRY.get()
        ValkyrienSkiesMod.SHIP_CREATOR_ITEM_SMALLER = SHIP_CREATOR_SMALLER_ITEM_REGISTRY.get()
        ValkyrienSkiesMod.AREA_ASSEMBLER_ITEM = AREA_ASSEMBLER_ITEM_REGISTRY.get()
        ValkyrienSkiesMod.PHYSICS_ENTITY_CREATOR_ITEM = PHYSICS_ENTITY_CREATOR_ITEM_REGISTRY.get()
        ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE = SHIP_MOUNTING_ENTITY_REGISTRY.get()
        ValkyrienSkiesMod.TEST_HINGE_BLOCK_ENTITY_TYPE = TEST_HINGE_BLOCK_ENTITY_TYPE_REGISTRY.get()
        ValkyrienSkiesMod.BLOCK_POS_COMPONENT = BLOCK_POS_COMPONENT.get()
    }
}
