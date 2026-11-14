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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import java.io.*;
import java.util.*;
import org.bukkit.craftbukkit.entity.CraftFox;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowParentGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;

public final class SnowFoxPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    static final UUID ORIGINAL = UUID.fromString("3c2db120-d8f9-3718-bdc3-45e02d25bb6b");
    static final class Pet {
        final UUID owner;
        UUID fox;
        String name = "Snowy";
        boolean stay, particles;
        boolean movementMessages = true, itemMessages = true;
        boolean aging = false;
        Fox.Type variant = Fox.Type.SNOW;
        Location last, seat, previous;
        int stuck, repath;
        Location lastGoal;
        Pet(UUID owner) { this.owner = owner; }
    }
    record Pending(UUID owner, long expires) {}
    final Map<UUID, Pet> pets = new HashMap<>();
    final Map<UUID, UUID> owners = new HashMap<>();
    final Map<UUID, Pending> dropped = new HashMap<>();
    File file;
    double followStart = 4.0, followStop = 2.0, followSpeed = 1.15, teleportDistance = 24.0;
    int followRepathTicks = 10, stuckCheckTicks = 40, stuckChecksBeforeTeleport = 4;
    boolean snowflakeParticlesEnabled = true;
    boolean wolfStyleCombatEnabled = false;
    int particleAmount = 1;
    boolean defaultAging = false;
    void loadSettings() {
        reloadConfig();
        followStop = bounded("following.stop-distance", 2.0, 0.5, 12.0);
        followStart = bounded("following.start-distance", 4.0, followStop + 0.5, 24.0);
        followSpeed = bounded("following.speed", 1.15, 0.1, 3.0);
        teleportDistance = bounded("following.teleport-distance", 24.0, followStart + 1.0, 128.0);
        followRepathTicks = (int) bounded("following.repath-ticks", 10, 1, 100);
        stuckCheckTicks = (int) bounded("following.stuck-check-ticks", 40, 5, 200);
        stuckChecksBeforeTeleport = (int) bounded("following.stuck-checks-before-teleport", 4, 1, 20);
        snowflakeParticlesEnabled = getConfig().getBoolean("effects.allow-snowflake-particles", true);
        wolfStyleCombatEnabled = getConfig().getBoolean("combat.wolf-style-attacks", false);
        particleAmount = Math.max(0, Math.min(100, getConfig().getInt("effects.snowflake-particle-amount", 1)));
        defaultAging = getConfig().getBoolean("fox.allow-natural-aging", false);
    }
    double bounded(String key, double fallback, double min, double max) {
        double value = getConfig().getDouble(key, fallback);
        if (!Double.isFinite(value) || value < min || value > max) {
            getLogger().warning("Invalid config value for " + key + "; using " + fallback);
            return fallback;
        }
        return value;
    }
    long ticks;
    NamespacedKey petKey;
    final Map<UUID, Long> eatingUntil = new HashMap<>();
    final Map<UUID, net.minecraft.world.entity.animal.fox.Fox> wolfGoalsInstalled = new HashMap<>();

    @Override public void onEnable() {
        getDataFolder().mkdirs();
        petKey = new NamespacedKey(this, "owner");
        file = new File(getDataFolder(), "pets.yml");
        saveDefaultConfig();
        loadSettings();
        load();
        Bukkit.getPluginManager().registerEvents(this, this);
        for (String name : List.of("snowfox","snowfoxrename","snowfoxstay","snowfoxadopt","snowfoxfix","snowfoxtrust","snowfoxreset")) {
            PluginCommand command = getCommand(name);
            if (command != null) { command.setExecutor(this); command.setTabCompleter(this); }
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 5, 5);
        Bukkit.getScheduler().runTaskTimer(this, this::save, 1200, 1200);
    }
    @Override public void onDisable() { save(); }
    void tell(Player p, String s) { p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&bSnowFox &7» " + s)); }
    void notifyMovement(Player p, Pet pet, String message) {
        if (pet == null || pet.movementMessages) tell(p, message);
    }
    void notifyItem(Player p, Pet pet, String message) {
        if (pet == null || pet.itemMessages) tell(p, message);
    }
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
        fox.getPersistentDataContainer().set(petKey, PersistentDataType.STRING, pet.owner.toString());
        fox.setInvulnerable(true);
        fox.setPersistent(true);
        fox.setRemoveWhenFarAway(false);
        fox.setCanPickupItems(true);
        fox.setAgeLock(!pet.aging);
        fox.setFoxType(pet.variant);
        fox.setCustomName(pet.name);
        fox.setCustomNameVisible(false);
        installWolfGoals(fox, pet);
        fox.setAI(true);
        fox.setGravity(true);
        Player p = Bukkit.getPlayer(pet.owner);
        if (p != null) fox.setFirstTrustedPlayer(p);
    }
    void installWolfGoals(Fox fox, Pet pet) {
        net.minecraft.world.entity.animal.fox.Fox handle = ((CraftFox) fox).getHandle();
        if (wolfGoalsInstalled.get(fox.getUniqueId()) == handle) return;
        wolfGoalsInstalled.put(fox.getUniqueId(), handle);
        // Baby foxes should follow their player, not the vanilla nearest adult fox.
        // Remove only the vanilla parent-following goal; retain hunting and other fox AI.
        for (var wrapped : new ArrayList<>(handle.goalSelector.getAvailableGoals())) {
            if (wrapped.getGoal() instanceof FollowParentGoal) {
                handle.goalSelector.removeGoal(wrapped.getGoal());
            }
        }
        handle.goalSelector.addGoal(0, new FoxStayGoal(handle, pet));
        handle.goalSelector.addGoal(4, new FoxFollowOwnerGoal(handle, pet));
        handle.goalSelector.addGoal(7, new LookAtPlayerGoal(handle, net.minecraft.world.entity.LivingEntity.class, 8.0F));
        handle.goalSelector.addGoal(8, new RandomLookAroundGoal(handle));
    }

    final class FoxStayGoal extends Goal {
        private final net.minecraft.world.entity.animal.fox.Fox fox;
        private final Pet pet;
        FoxStayGoal(net.minecraft.world.entity.animal.fox.Fox fox, Pet pet) {
            this.fox = fox;
            this.pet = pet;
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP, Goal.Flag.TARGET));
        }
        @Override public boolean canUse() { return pet.stay || Bukkit.getPlayer(pet.owner) == null; }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public void start() { holdPosition(); }
        @Override public void tick() { holdPosition(); }
        private void holdPosition() {
            fox.getNavigation().stop();
            fox.setTarget(null);
            var motion = fox.getDeltaMovement();
            fox.setDeltaMovement(0.0, motion.y, 0.0);
            fox.getMoveControl().setWantedPosition(fox.getX(), fox.getY(), fox.getZ(), 0.0);
        }
    }

    final class FoxFollowOwnerGoal extends Goal {
        private final net.minecraft.world.entity.animal.fox.Fox fox;
        private final Pet pet;
        private int recalc;
        FoxFollowOwnerGoal(net.minecraft.world.entity.animal.fox.Fox fox, Pet pet) {
            this.fox = fox;
            this.pet = pet;
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }
        private Player owner() { return Bukkit.getPlayer(pet.owner); }
        @Override public boolean canUse() {
            Player p = owner();
            return !pet.stay && fox.getTarget() == null && p != null && p.getWorld().getUID().equals(fox.getBukkitEntity().getWorld().getUID())
                    && fox.distanceToSqr(((CraftPlayer) p).getHandle()) > followStart * followStart;
        }
        @Override public boolean canContinueToUse() {
            Player p = owner();
            return !pet.stay && fox.getTarget() == null && p != null && p.getWorld().getUID().equals(fox.getBukkitEntity().getWorld().getUID())
                    && fox.distanceToSqr(((CraftPlayer) p).getHandle()) > followStop * followStop;
        }
        @Override public void start() { recalc = 0; }
        @Override public void stop() { if (fox.getTarget() == null) fox.getNavigation().stop(); }
        @Override public void tick() {
            Player p = owner();
            if (p == null || pet.stay) { fox.getNavigation().stop(); return; }
            if (fox.getTarget() != null) return;
            var target = ((CraftPlayer) p).getHandle();
            fox.getLookControl().setLookAt(target, 10.0f, fox.getMaxHeadXRot());
            if (--recalc <= 0) {
                recalc = followRepathTicks;
                fox.getNavigation().moveTo(target, followSpeed);
            }
        }
    }

    final class FoxLookAtOwnerGoal extends Goal {
        private final net.minecraft.world.entity.animal.fox.Fox fox;
        private final Pet pet;
        FoxLookAtOwnerGoal(net.minecraft.world.entity.animal.fox.Fox fox, Pet pet) {
            this.fox = fox;
            this.pet = pet;
            setFlags(EnumSet.of(Goal.Flag.LOOK));
        }
        @Override public boolean canUse() {
            Player p = Bukkit.getPlayer(pet.owner);
            if (p == null || !p.getWorld().getUID().equals(fox.getBukkitEntity().getWorld().getUID())
                    || fox.distanceToSqr(((CraftPlayer) p).getHandle()) >= 64.0) return false;
            if (!pet.stay && (fox.getTarget() != null || preyNearby())) return false;
            return true;
        }
        private boolean preyNearby() {
            org.bukkit.entity.Entity bukkitFox = fox.getBukkitEntity();
            for (org.bukkit.entity.Entity nearby : bukkitFox.getNearbyEntities(10, 5, 10)) {
                if (nearby instanceof org.bukkit.entity.Chicken && nearby.isValid()) return true;
            }
            return false;
        }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public void tick() {
            if (!canUse()) return;
            Player p = Bukkit.getPlayer(pet.owner);
            if (p != null) fox.getLookControl().setLookAt(((CraftPlayer) p).getHandle(), 10.0f, fox.getMaxHeadXRot());
        }
    }

    void sit(Fox fox, Pet pet, boolean value) {
        pet.stay = value;
        pet.seat = value ? fox.getLocation().clone() : null;
        pet.previous = null; pet.lastGoal = null; pet.stuck = 0; pet.repath = 0;
        fox.getPathfinder().stopPathfinding();
        if (value) {
            var motion = ((CraftFox) fox).getHandle().getDeltaMovement();
            ((CraftFox) fox).getHandle().setDeltaMovement(0.0, motion.y, 0.0);
        }
        fox.setSitting(value);
        fox.setAI(true);
        fox.setGravity(true);
        save();
    }
    void summon(Player p) { summon(p, Fox.Type.SNOW); }
    void summon(Player p, Fox.Type variant) {
        Pet pet = pets.computeIfAbsent(p.getUniqueId(), Pet::new);
        Fox fox = find(pet, true);
        if (pet.fox == null) { pet.variant = variant; pet.aging = defaultAging; }
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
        tell(p, "&aYour fox is here!");
    }
    void despawn(Player p, Pet pet, Fox fox) {
        if (pet == null || pet.fox == null) { tell(p, "&cNo registered fox."); return; }
        if (fox == null) { tell(p, "&cFox is unavailable. Registration kept to avoid losing a fox."); return; }
        UUID id = pet.fox;
        ItemStack held = fox.getEquipment().getItemInMainHand();
        if (held != null && !held.getType().isAir()) fox.getWorld().dropItemNaturally(fox.getLocation(), held.clone());
        fox.remove();
        wolfGoalsInstalled.remove(id);
        eatingUntil.remove(id);
        owners.remove(id);
        pet.fox = null;
        pet.stay = false;
        pet.seat = null;
        pet.last = null;
        pet.previous = null;
        save();
        tell(p, "&aFox despawned. You can summon a different variant.");
    }
    void dropHeldItem(Player p, Fox fox) {
        if (fox == null) { tell(p, "&cFox unavailable."); return; }
        ItemStack held = fox.getEquipment().getItemInMainHand();
        if (held == null || held.getType().isAir()) { tell(p, "&eYour fox isn't holding anything."); return; }
        fox.getEquipment().setItemInMainHand(null);
        fox.getWorld().dropItemNaturally(fox.getLocation(), held.clone());
        tell(p, "&aYour fox dropped its item.");
    }
    void tick() {
        ticks += 5;
        dropped.entrySet().removeIf(e -> ticks >= e.getValue().expires());
        eatingUntil.entrySet().removeIf(e -> { if (ticks < e.getValue()) return false; Entity entity = Bukkit.getEntity(e.getKey()); if (entity instanceof Fox fox) setEating(fox, false); return true; });
        for (Pet pet : pets.values()) {
            Fox fox = find(pet, false);
            if (fox == null) continue;
            protect(fox, pet);
            Player owner = Bukkit.getPlayer(pet.owner);
            pet.last = fox.getLocation().clone();
            if (owner == null || pet.stay) {
                fox.getPathfinder().stopPathfinding();
                var motion = ((CraftFox) fox).getHandle().getDeltaMovement();
                ((CraftFox) fox).getHandle().setDeltaMovement(0.0, motion.y, 0.0);
                fox.setSitting(true);
            } else {
                fox.setSitting(false);
                if (owner.getWorld() != fox.getWorld()
                        || fox.getLocation().distanceSquared(owner.getLocation()) > teleportDistance * teleportDistance) {
                    fox.teleport(owner.getLocation());
                    pet.stuck = 0;
                    pet.previous = null;
                } else if (ticks % (5L * Math.max(1, (stuckCheckTicks + 4) / 5)) == 0) {
                    double distance = fox.getLocation().distanceSquared(owner.getLocation());
                    if (distance > followStart * followStart && pet.previous != null
                            && pet.previous.getWorld() == fox.getWorld()
                            && pet.previous.distanceSquared(fox.getLocation()) < 0.16) pet.stuck++;
                    else pet.stuck = 0;
                    pet.previous = fox.getLocation().clone();
                    if (pet.stuck >= stuckChecksBeforeTeleport) {
                        fox.teleport(owner.getLocation());
                        pet.stuck = 0;
                    }
                }
            }
            if (snowflakeParticlesEnabled && pet.particles && particleAmount > 0 && ticks % 20 == 0) fox.getWorld().spawnParticle(Particle.SNOWFLAKE, fox.getLocation().add(0, 0.5, 0), particleAmount, .15, .15, .15, .005);
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
            if (pets.get(pending.owner()).stay && isChicken(incoming)) {
                eatChicken(fox, pets.get(pending.owner()));
                UUID foxId = fox.getUniqueId();
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    Entity current = Bukkit.getEntity(foxId);
                    if (current instanceof Fox live && isChicken(live.getEquipment().getItemInMainHand())) live.getEquipment().setItemInMainHand(null);
                }, 30L);
            }
            item.remove();
            dropped.remove(id);
            Player recipient = Bukkit.getPlayer(pending.owner());
            if (recipient != null) notifyItem(recipient, pets.get(pending.owner()), "&aItem swapped!");
            if (!old.getType().isAir()) {
                Item returned = fox.getWorld().dropItemNaturally(fox.getLocation(), old);
                returned.setPickupDelay(20);
            }
        }
    }
    boolean isManaged(Fox fox) {
        return owners.containsKey(fox.getUniqueId()) || fox.getPersistentDataContainer().has(petKey, PersistentDataType.STRING);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Pet pet = pets.get(e.getPlayer().getUniqueId());
        if (pet != null && pet.fox != null) dropped.put(e.getItemDrop().getUniqueId(), new Pending(pet.owner, ticks + 100));
    }
    @EventHandler public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Fox fox) || !isManaged(fox)) return;
        if (dropped.containsKey(e.getItem().getUniqueId())) { e.setCancelled(true); return; }
        if (e.getItem().getItemStack().getType() != Material.CHICKEN
                && e.getItem().getItemStack().getType() != Material.COOKED_CHICKEN) e.setCancelled(true);
    }
    @EventHandler public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Fox fox && owners.containsKey(fox.getUniqueId())) e.setCancelled(true);
    }
    @EventHandler public void onTarget(EntityTargetEvent e) {
        if (e.getEntity() instanceof Fox fox && owners.containsKey(fox.getUniqueId())) {
            if (e.getTarget() instanceof Player || e.getTarget() instanceof Fox
                    || (pets.get(owners.get(fox.getUniqueId())) != null
                    && pets.get(owners.get(fox.getUniqueId())).stay)) e.setCancelled(true);
        }
    }
    @EventHandler(ignoreCancelled = true)
    public void onWolfStyleCombat(EntityDamageByEntityEvent event) {
        if (!wolfStyleCombatEnabled) return;
        Entity attacker = event.getDamager();
        if (attacker instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) attacker = shooter;
        if (attacker instanceof Player owner && event.getEntity() instanceof LivingEntity target) {
            directFoxAttack(owner, target);
        }
        if (event.getEntity() instanceof Player owner && attacker instanceof LivingEntity target) {
            directFoxAttack(owner, target);
        }
    }
    void directFoxAttack(Player owner, LivingEntity target) {
        if (target instanceof Player || target instanceof Fox || !target.isValid() || target.isDead()) return;
        Pet pet = pets.get(owner.getUniqueId());
        if (pet == null || pet.stay) return;
        Fox fox = find(pet, false);
        if (fox == null || fox.getWorld() != target.getWorld()
                || fox.getLocation().distanceSquared(target.getLocation()) > 16 * 16) return;
        fox.setTarget(target);
    }
    @EventHandler public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!(e.getRightClicked() instanceof Fox fox)) return;
        UUID id = owners.get(fox.getUniqueId());
        if (id == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!id.equals(p.getUniqueId())) { tell(p, "&cThis fox belongs to another player."); return; }
        Pet pet = pets.get(id);
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (pet != null && pet.stay && isChicken(hand)) {
            if (hand.getAmount() == 1) p.getInventory().setItemInMainHand(null);
            else hand.setAmount(hand.getAmount() - 1);
            eatChicken(fox, pet);
            notifyItem(p, pet, "&aSnowy enjoyed the chicken!");
            return;
        }
        if (!hand.getType().isAir()) return;
        sit(fox, pet, !pet.stay);
        notifyMovement(p, pet, pet.stay ? "&eSnowy is sitting!" : "&aSnowy is following!");
    }
    boolean isChicken(ItemStack item) {
        if (item == null) return false;
        return switch (item.getType()) {
            case CHICKEN, COOKED_CHICKEN -> true;
            default -> false;
        };
    }
    void setEating(Fox fox, boolean eating) {
        try { fox.getClass().getMethod("setEating", boolean.class).invoke(fox, eating); }
        catch (ReflectiveOperationException ignored) { }
    }
    void eatChicken(Fox fox, Pet pet) {
        if (!pet.stay) return;
        setEating(fox, true);
        eatingUntil.put(fox.getUniqueId(), ticks + 30);
        fox.getWorld().playSound(fox.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.65f, 1.3f);
        fox.getWorld().spawnParticle(Particle.ITEM, fox.getLocation().add(0, .4, 0), 6, .12, .12, .12, .02, new ItemStack(Material.COOKED_CHICKEN));
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
        if (command.getName().equalsIgnoreCase("snowfox") && args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("snowfox.admin")) {
                sender.sendMessage(ChatColor.RED + "You need snowfox.admin permission.");
                return true;
            }
            loadSettings();
            sender.sendMessage(ChatColor.AQUA + "SnowFox config.yml reloaded. Existing pets and preferences were preserved.");
            return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (!allowed(p)) { tell(p, "&cYou need snowfox.use permission."); return true; }
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        String action = cmd.equals("snowfox") ? (args.length == 0 ? "summon" : args[0].toLowerCase(Locale.ROOT)) : cmd.substring(7);
        Pet pet = pets.get(p.getUniqueId());
        Fox fox = find(pet, true);
        switch (action) {
            case "summon" -> {
                String requested = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "snow";
                if (!requested.equals("snow") && !requested.equals("red")) {
                    tell(p, "&cUsage: /snowfox summon [snow|red]"); break;
                }
                if (pet != null && pet.fox != null && pet.variant != (requested.equals("red") ? Fox.Type.RED : Fox.Type.SNOW)) {
                    tell(p, "&cDespawn your current fox first with /snowfox despawn."); break;
                }
                summon(p, requested.equals("red") ? Fox.Type.RED : Fox.Type.SNOW);
            }
            case "despawn" -> despawn(p, pet, fox);
            case "drop" -> dropHeldItem(p, fox);
            case "grow" -> {
                if (pet == null || pet.fox == null) { tell(p, "&cSummon your fox first."); break; }
                String choice = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : (pet.aging ? "off" : "on");
                if (!choice.equals("on") && !choice.equals("off")) { tell(p, "&cUsage: /snowfox grow [on|off]"); break; }
                pet.aging = choice.equals("on");
                if (fox != null) fox.setAgeLock(!pet.aging);
                save();
                tell(p, pet.aging ? "&aYour fox can now grow naturally over time." : "&eYour fox's age is now frozen.");
            }
            case "tp" -> {
                if (fox == null) { tell(p, "&cFox unavailable."); break; }
                fox.teleport(p.getLocation()); sit(fox, pet, false); tell(p, "&aSnowy teleported!");
            }
            case "stay" -> {
                if (fox == null) { tell(p, "&cSummon your fox first."); break; }
                sit(fox, pet, !pet.stay); notifyMovement(p, pet, pet.stay ? "&eSnowy is sitting!" : "&aSnowy is following!");
            }
            case "messages" -> {
                Pet settings = pets.computeIfAbsent(p.getUniqueId(), Pet::new);
                int offset = cmd.equals("snowfox") ? 1 : 0;
                if (args.length <= offset) {
                    tell(p, "&eChat messages: &fmovement=" + (settings.movementMessages ? "on" : "off")
                            + "&f, items=" + (settings.itemMessages ? "on" : "off"));
                    tell(p, "&7Usage: /snowfox messages <movement|items|all> <on|off|toggle>");
                    break;
                }
                String category = args[offset].toLowerCase(Locale.ROOT);
                if (!List.of("movement", "items", "all").contains(category)) {
                    tell(p, "&cChoose movement, items, or all."); break;
                }
                String choice = args.length > offset + 1 ? args[offset + 1].toLowerCase(Locale.ROOT) : "toggle";
                if (!List.of("on", "off", "toggle").contains(choice)) {
                    tell(p, "&cChoose on, off, or toggle."); break;
                }
                if (category.equals("movement") || category.equals("all"))
                    settings.movementMessages = choice.equals("toggle") ? !settings.movementMessages : choice.equals("on");
                if (category.equals("items") || category.equals("all"))
                    settings.itemMessages = choice.equals("toggle") ? !settings.itemMessages : choice.equals("on");
                save();
                tell(p, "&aMessages updated: &fmovement=" + (settings.movementMessages ? "on" : "off")
                        + "&f, items=" + (settings.itemMessages ? "on" : "off"));
            }
            case "rename" -> {
                String name = cmd.equals("snowfox") ? (args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "") : String.join(" ", args);
                if (name.isBlank() || name.length() > 32) { tell(p, "&cName must be 1-32 characters."); break; }
                if (pet == null || pet.fox == null) { tell(p, "&cSummon your fox first."); break; }
                pet.name = ChatColor.translateAlternateColorCodes('&', name);
                if (fox != null) fox.setCustomName(pet.name);
                save(); tell(p, "&aRenamed to &r" + pet.name);
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
                    if (entity instanceof Fox candidate && !owners.containsKey(candidate.getUniqueId())
                            && (candidate.getPersistentDataContainer().get(petKey, PersistentDataType.STRING) == null
                                || p.getUniqueId().toString().equals(candidate.getPersistentDataContainer().get(petKey, PersistentDataType.STRING)))) {
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
            default -> tell(p, "&f/snowfox summon [snow|red], despawn, grow [on|off], drop, particles, tp, stay, messages, rename <name>, fix, trust, adopt, reset, reload");
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("snowfox") && args.length == 1)
            return (sender.hasPermission("snowfox.admin") ? List.of("summon","despawn","grow","drop","tp","stay","messages","rename","particles","fix","trust","adopt","reset","reload") : List.of("summon","despawn","grow","drop","tp","stay","messages","rename","particles","fix","trust","adopt","reset")).stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (command.getName().equalsIgnoreCase("snowfox") && args.length == 2 && args[0].equalsIgnoreCase("summon"))
            return List.of("snow", "red").stream().filter(v -> v.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (command.getName().equalsIgnoreCase("snowfox") && args.length == 2 && args[0].equalsIgnoreCase("grow"))
            return List.of("on", "off").stream().filter(v -> v.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (command.getName().equalsIgnoreCase("snowfox") && args.length == 2 && args[0].equalsIgnoreCase("messages"))
            return List.of("movement", "items", "all").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (command.getName().equalsIgnoreCase("snowfox") && args.length == 3 && args[0].equalsIgnoreCase("messages"))
            return List.of("on", "off", "toggle").stream().filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
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
                pet.aging = y.getBoolean("pets." + key + ".aging", false);
                try { pet.variant = Fox.Type.valueOf(y.getString("pets." + key + ".variant", "SNOW").toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException ignored) { pet.variant = Fox.Type.SNOW; }
                pet.stay = y.getBoolean("pets." + key + ".stay");
                pet.particles = y.getBoolean("pets." + key + ".particles");
                pet.movementMessages = y.getBoolean("pets." + key + ".messages.movement", true);
                pet.itemMessages = y.getBoolean("pets." + key + ".messages.items", true);
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
            y.set(path + ".aging", pet.aging);
            y.set(path + ".variant", pet.variant.name());
            y.set(path + ".stay", pet.stay);
            y.set(path + ".particles", pet.particles);
            y.set(path + ".messages.movement", pet.movementMessages);
            y.set(path + ".messages.items", pet.itemMessages);
            y.set(path + ".last", pet.last);
            y.set(path + ".seat", pet.seat);
        }
        try { y.save(file); } catch (IOException e) { getLogger().severe("Could not save pets.yml: " + e.getMessage()); }
    }
}