package com.snowfox;

import org.bukkit.Location;
import java.util.UUID;

final class Pet {
    final UUID owner;
    UUID fox;
    String name = "Snowy";
    boolean stay, particles;
    boolean movementMessages = true, itemMessages = true;
    Location last, seat, previous;
    int stuck, repath;
    Location lastGoal;
    Pet(UUID owner) { this.owner = owner; }
}
