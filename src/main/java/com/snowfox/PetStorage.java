package com.snowfox;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.util.UUID;

final class PetStorage {
    private final SnowFoxPlugin plugin;
    PetStorage(SnowFoxPlugin plugin) { this.plugin = plugin; }
    void load() {
        if (!plugin.file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(plugin.file);
        ConfigurationSection section = y.getConfigurationSection("pets");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                Pet pet = new Pet(UUID.fromString(key));
                String fox = y.getString("pets." + key + ".fox");
                if (fox != null) pet.fox = UUID.fromString(fox);
                pet.name = y.getString("pets." + key + ".name", "Snowy");
                pet.stay = y.getBoolean("pets." + key + ".stay");
                pet.particles = y.getBoolean("pets." + key + ".particles");
                pet.movementMessages = y.getBoolean("pets." + key + ".messages.movement", true);
                pet.itemMessages = y.getBoolean("pets." + key + ".messages.items", true);
                pet.last = y.getLocation("pets." + key + ".last");
                pet.seat = y.getLocation("pets." + key + ".seat");
                plugin.pets.put(pet.owner, pet);
                if (pet.fox != null) plugin.owners.put(pet.fox, pet.owner);
            } catch (IllegalArgumentException ex) { plugin.getLogger().warning("Invalid pet record " + key); }
        }
    }
    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Pet pet : plugin.pets.values()) {
            String path = "pets." + pet.owner;
            y.set(path + ".fox", pet.fox == null ? null : pet.fox.toString());
            y.set(path + ".name", pet.name);
            y.set(path + ".stay", pet.stay);
            y.set(path + ".particles", pet.particles);
            y.set(path + ".messages.movement", pet.movementMessages);
            y.set(path + ".messages.items", pet.itemMessages);
            y.set(path + ".last", pet.last);
            y.set(path + ".seat", pet.seat);
        }
        try { y.save(plugin.file); } catch (IOException e) { plugin.getLogger().severe("Could not save pets.yml: " + e.getMessage()); }
    }
}
