package com.snowfox;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;

final class SnowFoxCommands implements CommandExecutor, TabCompleter {
    private final SnowFoxPlugin plugin;
    SnowFoxCommands(SnowFoxPlugin plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("snowfox") && args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("snowfox.admin")) {
                sender.sendMessage(ChatColor.RED + "You need snowfox.admin permission.");
                return true;
            }
            plugin.loadSettings();
            sender.sendMessage(ChatColor.AQUA + "SnowFox config.yml reloaded. Existing pets and preferences were preserved.");
            return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (!plugin.allowed(p)) { plugin.tell(p, "&cYou need snowfox.use permission."); return true; }
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        String action = cmd.equals("snowfox") ? (args.length == 0 ? "summon" : args[0].toLowerCase(Locale.ROOT)) : cmd.substring(7);
        Pet pet = plugin.pets.get(p.getUniqueId());
        Fox fox = plugin.find(pet, true);
        switch (action) {
            case "summon" -> {
                String requested = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "snow";
                if (!requested.equals("snow") && !requested.equals("red")) {
                    plugin.tell(p, "&cUsage: /snowfox summon [snow|red]"); break;
                }
                if (pet != null && pet.fox != null && pet.variant != (requested.equals("red") ? Fox.Type.RED : Fox.Type.SNOW)) {
                    plugin.tell(p, "&cDespawn your current fox first with /snowfox despawn."); break;
                }
                plugin.summon(p, requested.equals("red") ? Fox.Type.RED : Fox.Type.SNOW);
            }
            case "despawn" -> plugin.despawn(p, pet, fox);
            case "drop" -> plugin.dropHeldItem(p, fox);
            case "combat" -> {
                if (pet == null || pet.fox == null) { plugin.tell(p, "&cSummon your fox first."); break; }
                String choice = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : (pet.combat ? "off" : "on");
                if (!choice.equals("on") && !choice.equals("off")) { plugin.tell(p, "&cUsage: /snowfox combat [on|off]"); break; }
                pet.combat = choice.equals("on");
                if (!pet.combat && fox != null) {
                    plugin.combatTargets.remove(fox.getUniqueId());
                    plugin.combatExpires.remove(fox.getUniqueId());
                    fox.setTarget(null);
                }
                plugin.save();
                plugin.tell(p, pet.combat ? "&aFox defense enabled!" : "&eFox defense disabled.");
            }
            case "grow" -> {
                if (pet == null || pet.fox == null) { plugin.tell(p, "&cSummon your fox first."); break; }
                String choice = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : (pet.aging ? "off" : "on");
                if (!choice.equals("on") && !choice.equals("off")) { plugin.tell(p, "&cUsage: /snowfox grow [on|off]"); break; }
                pet.aging = choice.equals("on");
                if (fox != null) fox.setAgeLock(!pet.aging);
                plugin.save();
                plugin.tell(p, pet.aging ? "&aYour fox can now grow naturally over time." : "&eYour fox's age is now frozen.");
            }
            case "tp" -> {
                if (fox == null) { plugin.tell(p, "&cFox unavailable."); break; }
                fox.teleport(p.getLocation()); plugin.sit(fox, pet, false); plugin.tell(p, "&aSnowy teleported!");
            }
            case "stay" -> {
                if (fox == null) { plugin.tell(p, "&cSummon your fox first."); break; }
                plugin.sit(fox, pet, !pet.stay); plugin.notifyMovement(p, pet, pet.stay ? "&eSnowy is sitting!" : "&aSnowy is following!");
            }
            case "messages" -> {
                Pet settings = plugin.pets.computeIfAbsent(p.getUniqueId(), Pet::new);
                int offset = cmd.equals("snowfox") ? 1 : 0;
                if (args.length <= offset) {
                    plugin.tell(p, "&eChat messages: &fmovement=" + (settings.movementMessages ? "on" : "off")
                            + "&f, items=" + (settings.itemMessages ? "on" : "off"));
                    plugin.tell(p, "&7Usage: /snowfox messages <movement|items|all> <on|off|toggle>");
                    break;
                }
                String category = args[offset].toLowerCase(Locale.ROOT);
                if (!List.of("movement", "items", "all").contains(category)) {
                    plugin.tell(p, "&cChoose movement, items, or all."); break;
                }
                String choice = args.length > offset + 1 ? args[offset + 1].toLowerCase(Locale.ROOT) : "toggle";
                if (!List.of("on", "off", "toggle").contains(choice)) {
                    plugin.tell(p, "&cChoose on, off, or toggle."); break;
                }
                if (category.equals("movement") || category.equals("all"))
                    settings.movementMessages = choice.equals("toggle") ? !settings.movementMessages : choice.equals("on");
                if (category.equals("items") || category.equals("all"))
                    settings.itemMessages = choice.equals("toggle") ? !settings.itemMessages : choice.equals("on");
                plugin.save();
                plugin.tell(p, "&aMessages updated: &fmovement=" + (settings.movementMessages ? "on" : "off")
                        + "&f, items=" + (settings.itemMessages ? "on" : "off"));
            }
            case "rename" -> {
                String name = cmd.equals("snowfox") ? (args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "") : String.join(" ", args);
                if (name.isBlank() || name.length() > 32) { plugin.tell(p, "&cName must be 1-32 characters."); break; }
                if (pet == null || pet.fox == null) { plugin.tell(p, "&cSummon your fox first."); break; }
                pet.name = ChatColor.translateAlternateColorCodes('&', name);
                if (fox != null) fox.setCustomName(pet.name);
                plugin.save(); plugin.tell(p, "&aRenamed to &r" + pet.name);
            }
            case "particles" -> {
                if (pet == null) { plugin.tell(p, "&cSummon first."); break; }
                pet.particles = !pet.particles; plugin.save();
                plugin.tell(p, pet.particles ? "&bSnowflakes enabled!" : "&7Snowflakes disabled.");
            }
            case "fix" -> {
                if (fox == null) { plugin.tell(p, "&cFox unavailable."); break; }
                plugin.protect(fox, pet); plugin.tell(p, "&aSnowy repaired!");
            }
            case "trust" -> {
                if (fox == null) { plugin.tell(p, "&cFox unavailable."); break; }
                plugin.tell(p, "&eYour UUID: &f" + p.getUniqueId());
                plugin.tell(p, "&eFox trusts: &f" + fox.getFirstTrustedPlayer());
            }
            case "adopt" -> {
                if (pet != null && pet.fox != null) { plugin.tell(p, "&cYou already own a fox."); break; }
                Fox nearest = null; double best = 25;
                for (Entity entity : p.getNearbyEntities(5, 5, 5)) {
                    if (entity instanceof Fox candidate && !plugin.owners.containsKey(candidate.getUniqueId())
                            && (candidate.getPersistentDataContainer().get(plugin.petKey, PersistentDataType.STRING) == null
                                || p.getUniqueId().toString().equals(candidate.getPersistentDataContainer().get(plugin.petKey, PersistentDataType.STRING)))) {
                        double d = candidate.getLocation().distanceSquared(p.getLocation());
                        if (d < best) { best = d; nearest = candidate; }
                    }
                }
                if (nearest == null) { plugin.tell(p, "&cNo unowned fox nearby."); break; }
                Pet adopted = plugin.pets.computeIfAbsent(p.getUniqueId(), Pet::new);
                adopted.fox = nearest.getUniqueId();
                adopted.last = nearest.getLocation().clone();
                plugin.owners.put(adopted.fox, adopted.owner);
                plugin.protect(nearest, adopted); plugin.sit(nearest, adopted, false);
                plugin.tell(p, "&aFox adopted!");
            }
            case "reset" -> {
                if (pet == null || pet.fox == null) { plugin.tell(p, "&cNo registered fox."); break; }
                if (fox != null) { plugin.tell(p, "&cYour fox still exists. Use /snowfox tp."); break; }
                plugin.owners.remove(pet.fox); pet.fox = null; pet.stay = false; pet.seat = null;
                plugin.save(); plugin.tell(p, "&aRegistration reset; name and particles preserved.");
            }
            default -> plugin.tell(p, "&f/snowfox summon [snow|red], despawn, grow [on|off], combat [on|off], drop, particles, tp, stay, messages, rename <name>, fix, trust, adopt, reset, reload");
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("snowfox") && args.length == 1)
            return (sender.hasPermission("snowfox.admin") ? List.of("summon","despawn","grow","combat","drop","tp","stay","messages","rename","particles","fix","trust","adopt","reset","reload") : List.of("summon","despawn","grow","combat","drop","tp","stay","messages","rename","particles","fix","trust","adopt","reset")).stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (command.getName().equalsIgnoreCase("snowfox") && args.length == 2 && args[0].equalsIgnoreCase("summon"))
            return List.of("snow", "red").stream().filter(v -> v.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (command.getName().equalsIgnoreCase("snowfox") && args.length == 2 && args[0].equalsIgnoreCase("combat"))
            return List.of("on", "off").stream().filter(v -> v.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (command.getName().equalsIgnoreCase("snowfox") && args.length == 2 && args[0].equalsIgnoreCase("grow"))
            return List.of("on", "off").stream().filter(v -> v.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (command.getName().equalsIgnoreCase("snowfox") && args.length == 2 && args[0].equalsIgnoreCase("messages"))
            return List.of("movement", "items", "all").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (command.getName().equalsIgnoreCase("snowfox") && args.length == 3 && args[0].equalsIgnoreCase("messages"))
            return List.of("on", "off", "toggle").stream().filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        return Collections.emptyList();
    }

}
