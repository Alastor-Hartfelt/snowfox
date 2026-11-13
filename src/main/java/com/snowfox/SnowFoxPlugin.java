package com.snowfox;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import java.io.*;
import java.util.*;

public final class SnowFoxPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    static final UUID ORIGINAL = UUID.fromString("3c2db120-d8f9-3718-bdc3-45e02d25bb6b");
    static final class Pet {
        final UUID owner;
        UUID fox;
        String name = "Snowy";
        boolean stay, particles;
        Location last, seat, previous;
        int stuck, repath;
        Pet(UUID owner) { this.owner = owner; }
    }
    record Pending(UUID owner, long expires) {}
    final Map<UUID, Pet> pets = new HashMap<>();
    final Map<UUID, UUID> owners = new HashMap<>();
    final Map<UUID, Pending> dropped = new HashMap<>();
    File file;
    long ticks;

    @Override public void onEnable() {
        getDataFolder().mkdirs();
        file = new File(getDataFolder(), "pets.yml");
        load();
        Bukkit.getPluginManager().registerEvents(this, this);
        for (String name : List.of("snowfox","snowfoxrename","snowfoxstay","snowfoxadopt","snowfoxfix","snowfoxtrust","snowfoxreset")) {
            PluginCommand command = getCommand(name);
            if (command != null) { command.setExecutor(this); command.setTabCompleter(this); }
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 10, 10);
        Bukkit.getScheduler().runTaskTimer(this, this::save, 1200, 1200);
    }
    @Override public void onDisable() { save(); }
    void tell(Player p, String s) { p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&bSnowFox &7» " + s)); }
    boolean allowed(Player p) { return p.getUniqueId().equals(ORIGINAL) || p.hasPermission("snowfox.use"); }
    Fox find(Pet pet, boolean loadChunk) {
        if (pet == null || pet.fox == null) return null;
        Entity e = Bukkit.getEntity(pet.fox);
        if (!(e instanceof Fox) && loadChunk && pet.last != null && pet.last.getWorld() != null) {
            pet.last.getChunk().load();
            e = Bukkit.getEntity(pet.fox);
        }
        return e instanceof Fox fox && fox.isValid() ? fox : null;
    }
    void protect(Fox fox, Pet pet) {
        fox.setInvulnerable(true);
        fox.setPersistent(true);
        fox.setRemoveWhenFarAway(false);
        fox.setCanPickupItems(false);
        fox.setAge(-24000);
        fox.setAgeLock(true);
        fox.setFoxType(Fox.Type.SNOW);
        fox.setCustomName(pet.name);
        fox.setCustomNameVisible(true);
        fox.setTarget(null);
        fox.setAI(true);
        Player p = Bukkit.getPlayer(pet.owner);
        if (p != null) fox.setFirstTrustedPlayer(p);
    }
    void sit(Fox fox, Pet pet, boolean value) {
        pet.stay = value;
        pet.seat = value ? fox.getLocation().clone() : null;
        pet.previous = null; pet.stuck = 0; pet.repath = 0;
        fox.getPathfinder().stopPathfinding();
        fox.setSitting(value);
        save();
    }
    void summon(Player p) {
        Pet pet = pets.computeIfAbsent(p.getUniqueId(), Pet::new);
        Fox fox = find(pet, true);
        if (pet.fox != null && fox == null) {
            tell(p, "&cRegistered fox missing. Use /snowfoxreset only if it was deleted."); return;
        }
        if (fox == null) {
            fox = p.getWorld().spawn(p.getLocation(), Fox.class);
            pet.fox = fox.getUniqueId();
            owners.put(pet.fox, pet.owner);
        } else fox.teleport(p.getLocation());
        protect(fox, pet);
        sit(fox, pet, false);
        pet.last = fox.getLocation().clone();
        tell(p, "&aYour snow fox is here!");
    }
    void tick() {
        ticks += 10;
        dropped.entrySet().removeIf(e -> ticks >= e.getValue().expires());
        for (Pet pet : pets.values()) {
            Fox fox = find(pet, false);
            if (fox == null) continue;
            protect(fox, pet);
            Player owner = Bukkit.getPlayer(pet.owner);
            pet.last = fox.getLocation().clone();
            if (owner == null || pet.stay) {
                fox.getPathfinder().stopPathfinding();
                fox.setSitting(true);
                Location seat = pet.stay ? pet.seat : pet.last;
                if (seat != null && seat.getWorld() == fox.getWorld() && seat.distanceSquared(fox.getLocation()) > 0.3) {
                    fox.teleport(seat);
                }
                if (owner != null && owner.getWorld() == fox.getWorld() && owner.getLocation().distanceSquared(fox.getLocation()) < 64) {
                    try { fox.lookAt(owner); } catch (Exception ignored) {}
                }
            } else {
                fox.setSitting(false);
                if (owner.getWorld() != fox.getWorld()) {
                    fox.teleport(owner.getLocation()); pet.repath = 0;
                } else {
                    double distance = fox.getLocation().distanceSquared(owner.getLocation());
                    if (distance > 22 * 22) {
                        fox.teleport(owner.getLocation()); pet.stuck = 0; pet.repath = 0;
                    } else if (distance < 2.5 * 2.5) {
                        fox.getPathfinder().stopPathfinding(); pet.stuck = 0; pet.repath = 0;
                    } else if (distance > 4 * 4) {
                        if (pet.repath-- <= 0 || !fox.getPathfinder().hasPath()) {
                            fox.getPathfinder().moveTo(owner, 1.2);
                            pet.repath = 6;
                        }
                        if (ticks % 40 == 0) {
                            if (pet.previous != null && pet.previous.getWorld() == fox.getWorld() && pet.previous.distanceSquared(fox.getLocation()) < 0.25 && distance > 36) {
                                pet.stuck++;
                                fox.getPathfinder().moveTo(owner, 1.2);
                            } else pet.stuck = 0;
                            pet.previous = fox.getLocation().clone();
                            if (pet.stuck >= 5) {
                                fox.teleport(owner.getLocation()); pet.stuck = 0; pet.repath = 0;
                            }
                        }
                    }
                }
            }
            if (pet.particles && ticks % 20 == 0) fox.getWorld().spawnParticle(Particle.SNOWFLAKE, fox.getLocation().add(0, 0.5, 0), 1, .15, .15, .15, .005);
        }
        for (UUID id : new ArrayList<>(dropped.keySet())) {
            Pending pending = dropped.get(id);
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Item item) || !item.isValid()) { dropped.remove(id); continue; }
            Fox fox = find(pets.get(pending.owner()), false);
            if (fox == null || fox.getWorld() != item.getWorld()) continue;
            BoundingBox box = fox.getBoundingBox().expand(.12, .15, .12);
            if (!box.overlaps(item.getBoundingBox())) continue;
            ItemStack incoming = item.getItemStack().clone();
            ItemStack old = fox.getEquipment().getItemInMainHand().clone();
            fox.getEquipment().setItemInMainHand(incoming);
            item.remove();
            dropped.remove(id);
            if (!old.getType().isAir()) {
                Item returned = fox.getWorld().dropItemNaturally(fox.getLocation(), old);
                returned.setPickupDelay(20);
            }
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Pet pet = pets.get(e.getPlayer().getUniqueId());
        if (pet != null && pet.fox != null) dropped.put(e.getItemDrop().getUniqueId(), new Pending(pet.owner, ticks + 100));
    }
    @EventHandler public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Fox fox && owners.containsKey(fox.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Fox fox && owners.containsKey(fox.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler public void onTarget(EntityTargetEvent e) {
        if (e.getEntity() instanceof Fox fox && owners.containsKey(fox.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler public void onInteract(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Fox fox)) return;
        UUID id = owners.get(fox.getUniqueId());
        if (id == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!id.equals(p.getUniqueId())) { tell(p, "&cThis fox belongs to another player."); return; }
        if (!p.getInventory().getItemInMainHand().getType().isAir()) return;
        Pet pet = pets.get(id);
        sit(fox, pet, !pet.stay);
        tell(p, pet.stay ? "&eSnowy is sitting!" : "&aSnowy is following!");
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Pet pet = pets.get(e.getPlayer().getUniqueId());
        Fox fox = find(pet, false);
        if (fox != null) { fox.getPathfinder().stopPathfinding(); fox.setSitting(true); pet.last = fox.getLocation().clone(); }
        save();
    }
    @EventHandler public void onDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Fox fox)) return;
        UUID id = owners.remove(fox.getUniqueId());
        if (id != null) {
            Pet pet = pets.get(id);
            if (pet != null) pet.fox = null;
            save();
        }
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (!allowed(p)) { tell(p, "&cYou need snowfox.use permission."); return true; }
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        String action = cmd.equals("snowfox") ? (args.length == 0 ? "summon" : args[0].toLowerCase(Locale.ROOT)) : cmd.substring(7);
        Pet pet = pets.get(p.getUniqueId());
        Fox fox = find(pet, true);
        switch (action) {
            case "summon" -> summon(p);
            case "tp" -> {
                if (fox == null) { tell(p, "&cFox unavailable."); break; }
                fox.teleport(p.getLocation()); sit(fox, pet, false); tell(p, "&aSnowy teleported!");
            }
            case "stay" -> {
                if (fox == null) { tell(p, "&cSummon your fox first."); break; }
                sit(fox, pet, !pet.stay); tell(p, pet.stay ? "&eSnowy is sitting!" : "&aSnowy is following!");
            }
            case "rename" -> {
                String name = cmd.equals("snowfox") ? (args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "") : String.join(" ", args);
                if (name.isBlank() || name.length() > 32) { tell(p, "&cName must be 1-32 characters."); break; }
                if (pet == null || pet.fox == null) { tell(p, "&cSummon your fox first."); break; }
                pet.name = name.replace('§', ' ');
                if (fox != null) fox.setCustomName(pet.name);
                save(); tell(p, "&aRenamed to &f" + pet.name);
            }
            case "particles" -> {
                if (pet == null) { tell(p, "&cSummon first."); break; }
                pet.particles = !pet.particles; save();
                tell(p, pet.particles ? "&bSnowflakes enabled!" : "&7Snowflakes disabled.");
            }
            case "fix" -> {
                if (fox == null) { tell(p, "&cFox unavailable."); break; }
                protect(fox, pet); tell(p, "&aSnowy repaired!");
            }
            case "trust" -> {
                if (fox == null) { tell(p, "&cFox unavailable."); break; }
                tell(p, "&eYour UUID: &f" + p.getUniqueId());
                tell(p, "&eFox trusts: &f" + fox.getFirstTrustedPlayer());
            }
            case "adopt" -> {
                if (pet != null && pet.fox != null) { tell(p, "&cYou already own a fox."); break; }
                Fox nearest = null; double best = 25;
                for (Entity entity : p.getNearbyEntities(5, 5, 5)) {
                    if (entity instanceof Fox candidate && !owners.containsKey(candidate.getUniqueId())) {
                        double d = candidate.getLocation().distanceSquared(p.getLocation());
                        if (d < best) { best = d; nearest = candidate; }
                    }
                }
                if (nearest == null) { tell(p, "&cNo unowned fox nearby."); break; }
                Pet adopted = pets.computeIfAbsent(p.getUniqueId(), Pet::new);
                adopted.fox = nearest.getUniqueId();
                adopted.last = nearest.getLocation().clone();
                owners.put(adopted.fox, adopted.owner);
                protect(nearest, adopted); sit(nearest, adopted, false);
                tell(p, "&aFox adopted!");
            }
            case "reset" -> {
                if (pet == null || pet.fox == null) { tell(p, "&cNo registered fox."); break; }
                if (fox != null) { tell(p, "&cYour fox still exists. Use /snowfox tp."); break; }
                owners.remove(pet.fox); pet.fox = null; pet.stay = false; pet.seat = null;
                save(); tell(p, "&aRegistration reset; name and particles preserved.");
            }
            default -> tell(p, "&f/snowfox summon, tp, stay, rename <name>, particles, fix, trust, adopt, reset");
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("snowfox") && args.length == 1)
            return List.of("summon","tp","stay","rename","particles","fix","trust","adopt","reset").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        return Collections.emptyList();
    }
    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
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
                pet.last = y.getLocation("pets." + key + ".last");
                pet.seat = y.getLocation("pets." + key + ".seat");
                pets.put(pet.owner, pet);
                if (pet.fox != null) owners.put(pet.fox, pet.owner);
            } catch (IllegalArgumentException ex) { getLogger().warning("Invalid pet record " + key); }
        }
    }
    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Pet pet : pets.values()) {
            String path = "pets." + pet.owner;
            y.set(path + ".fox", pet.fox == null ? null : pet.fox.toString());
            y.set(path + ".name", pet.name);
            y.set(path + ".stay", pet.stay);
            y.set(path + ".particles", pet.particles);
            y.set(path + ".last", pet.last);
            y.set(path + ".seat", pet.seat);
        }
        try { y.save(file); } catch (IOException e) { getLogger().severe("Could not save pets.yml: " + e.getMessage()); }
    }
}