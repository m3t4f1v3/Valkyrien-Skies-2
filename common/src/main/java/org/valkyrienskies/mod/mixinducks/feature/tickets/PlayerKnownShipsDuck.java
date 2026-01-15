package org.valkyrienskies.mod.mixinducks.feature.tickets;

public interface PlayerKnownShipsDuck {

    void vs_addKnownShip(long shipId);

    void vs_removeKnownShip(long shipId);

    boolean vs_isKnownShip(long shipId);

}
