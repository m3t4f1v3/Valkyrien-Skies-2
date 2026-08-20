package org.valkyrienskies.mod.mixinducks.mod_compat.sodium09;

import java.util.Map;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.compat.sodium09.ShipRenderLists;

public interface RenderSectionManagerDuck {
    Map<ClientShip, ShipRenderLists> vs$getShipRenderLists();

    void vs$updateShipRenderLists(Camera camera, Viewport viewport, int frame, boolean spectator);

    void vs$markShipRenderListsDirty();

    void vs$invalidateShipSectionCache(ClientShip ship);
}
