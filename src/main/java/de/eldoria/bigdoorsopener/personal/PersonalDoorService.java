/*
 *     SPDX-License-Identifier: AGPL-3.0-only
 *
 *     Copyright (C) 2021 EldoriaRPG Team and Contributor
 */

package de.eldoria.bigdoorsopener.personal;

import de.eldoria.bigdoorsopener.core.BigDoorsOpener;
import nl.pim16aap2.bigDoors.Door;
import nl.pim16aap2.bigDoors.events.DoorEventTogglePrepare;
import org.bukkit.*;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.Level;

/** Opt-in virtual gates. Physical snapshots are durably saved BEFORE opening the passage. */
public final class PersonalDoorService implements Listener, AutoCloseable {
    private final BigDoorsOpener plugin;
    private final File file;
    private final NamespacedKey marker;
    private final Map<Long, Gate> gates = new LinkedHashMap<>();
    private final Map<UUID, Location> projectiles = new HashMap<>();
    private BukkitTask task;
    private int tick;
    private boolean closed;
    private static final int MAX_BLOCKS = 512;
    private static final int MAX_SESSIONS = 128;
    private static final int MAX_DISPLAYS = 2048;

    public PersonalDoorService(BigDoorsOpener plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "personal-doors.yml");
        marker = new NamespacedKey(plugin, "personal_door");
        load();
        for (World world : Bukkit.getWorlds()) cleanup(world.getEntities());
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        plugin.getLogger().info("Modo individual disponível; " + gates.size() + " porta(s) configurada(s).");
    }

    public boolean manages(long id) { return gates.containsKey(id); }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            ConfigurationSection root = yaml.getConfigurationSection("doors");
            if (root == null) return;
            for (String key : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(key);
                long id = Long.parseLong(key);
                UUID world = UUID.fromString(section.getString("world"));
                List<DoorVoxel> blocks = section.getStringList("blocks").stream().map(DoorVoxel::decode).toList();
                if (blocks.isEmpty() || blocks.size() > MAX_BLOCKS) throw new IllegalArgumentException("Snapshot fora do limite: " + id);
                Set<UUID> players = new HashSet<>();
                for (String player : section.getStringList("players")) players.add(UUID.fromString(player));
                Gate gate = new Gate(id, world, blocks, new DoorAccessRule(section.getString("permission", ""), players));
                gate.direction = DoorDirection.parse(section.getString("direction", "up"));
                gates.put(id, gate);
            }
        } catch (Exception error) {
            // Do not discard snapshots that were parsed before the failure.
            plugin.getLogger().log(Level.SEVERE, "Falha ao ler portas individuais. Restaure personal-doors.yml.bak antes de ativar novas portas.", error);
            for (Gate gate : gates.values()) gate.restore();
            throw new IllegalStateException("Persistência de portas individuais inválida", error);
        }
    }

    private void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", 1);
        for (Gate gate : gates.values()) {
            String path = "doors." + gate.id;
            yaml.set(path + ".world", gate.worldId.toString());
            yaml.set(path + ".permission", gate.rule.permission());
            yaml.set(path + ".direction", gate.direction.name().toLowerCase(Locale.ROOT));
            yaml.set(path + ".players", gate.rule.players().stream().map(UUID::toString).sorted().toList());
            yaml.set(path + ".blocks", gate.blocks.stream().map(DoorVoxel::encode).toList());
        }
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        yaml.save(temp);
        if (file.exists()) Files.copy(file.toPath(), new File(file.getPath() + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
        try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }

    /** No default doors are converted. Only an explicit administrator command can capture one. */
    public boolean command(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bdo.command.personal")) { sender.sendMessage("§cVocê não tem permissão para configurar portas individuais."); return true; }
        if (args.length < 2) { usage(sender); return true; }
        try {
            long id = Long.parseLong(args[0]);
            String action = args[1].toLowerCase(Locale.ROOT);
            if (action.equals("info")) {
                Gate gate = gates.get(id);
                sender.sendMessage(gate == null ? "§ePorta no modo global." : "§aPorta individual " + id + ": permissão=" + gate.rule.permission()
                        + ", direção=" + (gate.direction == DoorDirection.UP ? "cima" : "baixo")
                        + ", jogadores=" + gate.rule.players().size() + ", blocos=" + gate.blocks.size() + ", espectadores=" + gate.sessions.size());
                return true;
            }
            if (action.equals("direction") || action.equals("direcao")) {
                if (args.length != 3) { usage(sender); return true; }
                Gate gate = gates.get(id);
                if (gate == null) throw new IllegalArgumentException("Ative o modo individual desta porta primeiro.");
                DoorDirection next = DoorDirection.parse(args[2]);
                DoorDirection previous = gate.direction;
                gate.direction = next;
                try { save(); } catch (IOException error) { gate.direction = previous; throw error; }
                // Retain each viewer's animation progress, changing only its direction.
                for (Session session : gate.sessions.values()) gate.positionDisplays(session);
                sender.sendMessage("§aDireção salva: " + (next == DoorDirection.UP ? "para cima." : "para baixo."));
                return true;
            }
            if (action.equals("off")) {
                Gate gate = gates.get(id);
                if (gate == null) { sender.sendMessage("§eEsta porta já está no modo global."); return true; }
                World world = gate.world();
                if (!gate.loaded()) throw new IllegalArgumentException("Carregue os chunks da porta antes de desativar.");
                if (world.getPlayers().stream().anyMatch(p -> gate.inside(p.getLocation(), p.getWidth() / 2, p.getHeight())))
                    throw new IllegalArgumentException("Afaste os jogadores da passagem antes de restaurar a porta física.");
                gate.restore();
                gates.remove(id);
                try { save(); } catch (IOException error) { gates.put(id, gate); throw error; }
                sender.sendMessage("§aPorta física restaurada; modo global disponível novamente.");
                return true;
            }
            if ((!action.equals("permission") && !action.equals("player")) || args.length != 3) { usage(sender); return true; }
            Door door = BigDoorsOpener.getBigDoors().getCommander().getDoor(null, id);
            if (door == null) throw new IllegalArgumentException("Porta não encontrada no BigDoors.");
            Gate existing = gates.get(id);
            DoorAccessRule rule;
            if (action.equals("permission")) {
                if (!args[2].matches("[A-Za-z0-9_.:-]{1,160}")) throw new IllegalArgumentException("Use um nó de permissão explícito, sem asterisco.");
                rule = new DoorAccessRule(args[2], Set.of());
            } else rule = new DoorAccessRule("", Set.of(resolvePlayer(args[2])));
            if (existing != null) {
                DoorAccessRule previous = existing.rule;
                existing.rule = rule;
                try { save(); } catch (IOException error) { existing.rule = previous; throw error; }
                sender.sendMessage("§aAcesso individual atualizado. Outros jogadores continuam bloqueados.");
                return true;
            }
            if (door.isOpen() || BigDoorsOpener.getBigDoors().getCommander().isDoorBusy(id))
                throw new IllegalArgumentException("Feche a porta e espere a animação terminar antes de ativar o modo individual.");
            if (door.getWorld() == null) throw new IllegalArgumentException("Mundo da porta indisponível.");
            org.bukkit.Location min = door.getMinimum(), max = door.getMaximum();
            long volume = (long)(max.getBlockX() - min.getBlockX() + 1) * (max.getBlockY() - min.getBlockY() + 1) * (max.getBlockZ() - min.getBlockZ() + 1);
            if (volume <= 0 || volume > MAX_BLOCKS) throw new IllegalArgumentException("O modo individual aceita até " + MAX_BLOCKS + " blocos de volume por porta.");
            List<DoorVoxel> blocks = new ArrayList<>();
            for (int x = min.getBlockX(); x <= max.getBlockX(); x++) for (int y = min.getBlockY(); y <= max.getBlockY(); y++) for (int z = min.getBlockZ(); z <= max.getBlockZ(); z++) {
                if (!door.getWorld().isChunkLoaded(x >> 4, z >> 4)) throw new IllegalArgumentException("Carregue todos os chunks da porta primeiro.");
                org.bukkit.block.Block block = door.getWorld().getBlockAt(x,y,z);
                if (block.getState() instanceof TileState || block.isLiquid()) throw new IllegalArgumentException("Portas com inventários, entidades de bloco ou líquidos não são compatíveis.");
                if (!block.getType().isAir()) blocks.add(new DoorVoxel(x,y,z,!block.isPassable(),block.getBlockData().getAsString()));
            }
            if (blocks.isEmpty()) throw new IllegalArgumentException("A porta não tem blocos para capturar.");
            Gate gate = new Gate(id, door.getWorld().getUID(), blocks, rule);
            if (gates.values().stream().anyMatch(other -> other.worldId.equals(gate.worldId)
                    && min.getBlockX() < other.maxX && max.getBlockX() + 1 > other.minX
                    && min.getBlockY() < other.maxY && max.getBlockY() + 1 > other.minY
                    && min.getBlockZ() < other.maxZ && max.getBlockZ() + 1 > other.minZ))
                throw new IllegalArgumentException("A porta sobrepõe outra porta individual.");
            gates.put(id, gate);
            try { save(); } catch (IOException error) { gates.remove(id); throw error; }
            gate.clearPhysical();
            sender.sendMessage("§aModo individual ativado. Abertura visual por elevação e colisão por jogador; snapshot salvo.");
        } catch (Exception error) {
            sender.sendMessage("§cNão foi possível configurar: " + error.getMessage());
            plugin.getLogger().log(Level.WARNING, "Falha ao configurar porta individual", error);
        }
        return true;
    }

    private UUID resolvePlayer(String name) {
        try { return UUID.fromString(name); } catch (IllegalArgumentException ignored) { }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        for (OfflinePlayer known : Bukkit.getOfflinePlayers()) if (name.equalsIgnoreCase(known.getName())) return known.getUniqueId();
        throw new IllegalArgumentException("Jogador não conhecido. Informe o UUID ou peça que entre no servidor.");
    }
    private void usage(CommandSender sender) {
        sender.sendMessage("§e/bdo personal <id> permission <permissão>");
        sender.sendMessage("§e/bdo personal <id> player <jogador|UUID>");
        sender.sendMessage("§e/bdo personal <id> direction <cima|baixo>");
        sender.sendMessage("§e/bdo personal <id> info|off");
    }

    private void tick() {
        if (closed) return;
        tick++;
        int count = gates.values().stream().mapToInt(g -> g.sessions.size()).sum();
        for (Gate gate : gates.values()) {
            if (!gate.loaded()) { gate.dropSessions(); continue; }
            if (tick % 20 == 1) gate.refreshDoor();
            if (!gate.available) continue;
            gate.clearPhysical();
            World world = gate.world();
            Set<UUID> seen = new HashSet<>();
            for (Player player : world.getPlayers()) {
                if (!gate.sentTo(player)) continue;
                seen.add(player.getUniqueId());
                Session session = gate.sessions.get(player.getUniqueId());
                if (session == null) {
                    if (count >= MAX_SESSIONS) { gate.mask(player, false); continue; }
                    session = new Session(player);
                    gate.sessions.put(player.getUniqueId(), session); count++;
                }
                boolean open = gate.mayPass(player);
                if (open != session.open) gate.transition(session, open);
                else if (session.displays.stream().anyMatch(display -> !display.isValid())) {
                    session.destroy(); gate.transition(session,open);
                }
                if (session.open) {
                    if (session.step < 20) gate.animate(session);
                } else if (!session.displays.isEmpty()) {
                    if (session.step > 0) gate.animate(session); else { session.destroy(); gate.mask(player,false); }
                }
                if (tick % 20 == 0 || session.fresh) { gate.mask(player, session.open || !session.displays.isEmpty()); session.fresh = false; }
            }
            Iterator<Map.Entry<UUID, Session>> iterator = gate.sessions.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, Session> entry = iterator.next();
                if (!seen.contains(entry.getKey())) { gate.mask(entry.getValue().player, false); entry.getValue().destroy(); iterator.remove(); count--; }
            }
        }
        Iterator<Map.Entry<UUID, Location>> iterator = projectiles.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID,Location> tracked = iterator.next();
            Entity entity = Bukkit.getEntity(tracked.getKey());
            if (!(entity instanceof Projectile projectile) || !entity.isValid() || entity.isDead()) { iterator.remove(); continue; }
            Player shooter = projectile.getShooter() instanceof Player p ? p : null;
            Location to = entity.getLocation();
            if (blocked(shooter, tracked.getValue(), to, .1, .2)) { entity.remove(); iterator.remove(); }
            else tracked.setValue(to);
        }
    }

    private boolean blocked(Player player, Location from, Location to, double halfWidth, double height) {
        if (from == null || to == null || !Objects.equals(from.getWorld(), to.getWorld())) return false;
        for (Gate gate : gates.values()) {
            if (gate.world() != to.getWorld() || (player != null && gate.mayPass(player))) continue;
            if (!gate.mightIntersect(from,to,halfWidth,height)) continue;
            for (DoorVoxel block : gate.blocks) if (block.crosses(from.getX(),from.getY(),from.getZ(),to.getX(),to.getY(),to.getZ(),halfWidth,height)) return true;
        }
        return false;
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent || event.getTo() == null || event.getFrom().toVector().equals(event.getTo().toVector())) return;
        Player player = event.getPlayer();
        if (blocked(player,event.getFrom(),event.getTo(),player.getWidth()/2,player.getHeight())) {
            // A permission revoked while inside must not trap the player: permit only a short exit.
            boolean shortExit = blocked(player,event.getFrom(),event.getFrom(),player.getWidth()/2,player.getHeight())
                    && event.getFrom().distanceSquared(event.getTo()) < 2.25
                    && !blocked(player,event.getTo(),event.getTo(),player.getWidth()/2,player.getHeight());
            if (!shortExit) { event.setTo(event.getFrom()); player.setVelocity(new org.bukkit.util.Vector()); }
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void teleport(PlayerTeleportEvent event) {
        if (event.getTo()!=null && blocked(event.getPlayer(),event.getTo(),event.getTo(),event.getPlayer().getWidth()/2,event.getPlayer().getHeight())) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void mob(io.papermc.paper.event.entity.EntityMoveEvent event) {
        if(gates.isEmpty() || event.getEntity() instanceof Player)return;
        Player rider=event.getEntity().getPassengers().stream().filter(Player.class::isInstance).map(Player.class::cast).findFirst().orElse(null);
        if(blocked(rider,event.getFrom(),event.getTo(),event.getEntity().getWidth()/2,event.getEntity().getHeight()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void vehicle(VehicleMoveEvent event) {
        for (Entity rider : event.getVehicle().getPassengers()) if (rider instanceof Player player
                && blocked(player,event.getFrom(),event.getTo(),Math.max(.3,event.getVehicle().getWidth()/2),Math.max(1.8,event.getVehicle().getHeight()))) {
            event.getVehicle().setVelocity(new org.bukkit.util.Vector());
            event.getVehicle().eject();
            event.getVehicle().teleport(event.getFrom());
            player.teleport(event.getFrom());
            return;
        }
    }
    @EventHandler(ignoreCancelled=true) public void launch(ProjectileLaunchEvent event) { if (!gates.isEmpty()) projectiles.put(event.getEntity().getUniqueId(),event.getEntity().getLocation()); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(EntityDamageByEntityEvent event) {
        Player attacker = event.getDamager() instanceof Player p ? p : event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p ? p : null;
        if (attacker != null && blocked(attacker,attacker.getEyeLocation(),event.getEntity().getLocation().add(0,.5,0),0,0)) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void toggle(DoorEventTogglePrepare event) { if (manages(event.getDoor().getDoorUID())) event.setCancelled(true); }
    @EventHandler public void quit(PlayerQuitEvent event) { forget(event.getPlayer()); }
    @EventHandler public void changed(PlayerChangedWorldEvent event) { forget(event.getPlayer()); }
    private void forget(Player player) { for (Gate gate : gates.values()) { Session session=gate.sessions.remove(player.getUniqueId()); if(session!=null)session.destroy(); } }
    @EventHandler public void chunkLoad(io.papermc.paper.event.packet.PlayerChunkLoadEvent event) {
        if (gates.isEmpty()) return;
        Bukkit.getScheduler().runTaskLater(plugin,()->{
            for (Gate gate:gates.values()) if(gate.world()==event.getPlayer().getWorld()
                    && gate.blocks.stream().anyMatch(b -> b.x() >> 4 == event.getChunk().getX() && b.z() >> 4 == event.getChunk().getZ())) {
                Session session=gate.sessions.get(event.getPlayer().getUniqueId());
                gate.mask(event.getPlayer(),session!=null && (session.open || !session.displays.isEmpty()));
            }
        },1);
    }
    @EventHandler public void chunkUnload(ChunkUnloadEvent event) {
        for(Gate gate:gates.values()) if(gate.world()==event.getWorld() && gate.blocks.stream().anyMatch(b->b.x()>>4==event.getChunk().getX() && b.z()>>4==event.getChunk().getZ())) gate.restore();
    }
    @EventHandler public void worldUnload(WorldUnloadEvent event) { for(Gate gate:gates.values())if(gate.world()==event.getWorld()) { gate.dropSessions();gate.restore(); } }
    @EventHandler public void entitiesLoad(EntitiesLoadEvent event) { cleanup(event.getEntities()); }
    private void cleanup(Collection<? extends Entity> entities) { for(Entity entity:entities)if(entity.getPersistentDataContainer().has(marker,PersistentDataType.LONG))entity.remove(); }
    private boolean cell(org.bukkit.block.Block block) { return gates.values().stream().anyMatch(g->g.world()==block.getWorld() && g.blocks.stream().anyMatch(b->b.x()==block.getX() && b.y()==block.getY() && b.z()==block.getZ())); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breakBlock(BlockBreakEvent event) { if(cell(event.getBlock()))event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void placeBlock(BlockPlaceEvent event) { if(cell(event.getBlock()))event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fluid(BlockFromToEvent event) { if(cell(event.getBlock())||cell(event.getToBlock()))event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void piston(BlockPistonExtendEvent event) { if(event.getBlocks().stream().anyMatch(b->cell(b)||cell(b.getRelative(event.getDirection()))))event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void piston(BlockPistonRetractEvent event) { if(event.getBlocks().stream().anyMatch(b->cell(b)||cell(b.getRelative(event.getDirection()))))event.setCancelled(true); }

    @Override public void close() {
        closed=true;
        if(task!=null)task.cancel();
        for(Gate gate:gates.values())try { gate.restore(); }
        catch (RuntimeException error) { plugin.getLogger().log(Level.SEVERE,"Falha ao restaurar porta " + gate.id,error); }
        projectiles.clear();
    }

    private final class Gate {
        final long id;
        final UUID worldId;
        final List<DoorVoxel> blocks;
        final Map<UUID,Session> sessions=new HashMap<>();
        final Map<DoorVoxel,BlockData> data=new HashMap<>();
        final int minX,minY,minZ,maxX,maxY,maxZ;
        DoorAccessRule rule;
        DoorDirection direction=DoorDirection.UP;
        boolean conflict;
        boolean available=true, locked;
        Gate(long id,UUID world,List<DoorVoxel> blocks,DoorAccessRule rule) {
            this.id=id;worldId=world;this.blocks=List.copyOf(blocks);this.rule=rule;
            minX=blocks.stream().mapToInt(DoorVoxel::x).min().orElseThrow();maxX=blocks.stream().mapToInt(DoorVoxel::x).max().orElseThrow()+1;
            minY=blocks.stream().mapToInt(DoorVoxel::y).min().orElseThrow();maxY=blocks.stream().mapToInt(DoorVoxel::y).max().orElseThrow()+1;
            minZ=blocks.stream().mapToInt(DoorVoxel::z).min().orElseThrow();maxZ=blocks.stream().mapToInt(DoorVoxel::z).max().orElseThrow()+1;
            for(DoorVoxel block:blocks)data.put(block,Bukkit.createBlockData(block.data()));
            refreshDoor();
        }
        World world(){return Bukkit.getWorld(worldId);}
        boolean loaded(){World world=world();return world!=null && blocks.stream().allMatch(b->world.isChunkLoaded(b.x()>>4,b.z()>>4));}
        boolean sentTo(Player player){
            return blocks.stream().anyMatch(b->player.isChunkSent(((long)(b.x()>>4)&0xffffffffL)|((long)(b.z()>>4)<<32)));
        }
        boolean mayPass(Player player){
            return !conflict && available && !locked && player.getWorld()==world()
                    && rule.allows(player.getUniqueId(),player::hasPermission);
        }
        void refreshDoor(){
            Door door=BigDoorsOpener.getBigDoors().getCommander().getDoor(null,id);
            if(door==null){
                if(available){restore();plugin.getLogger().warning("Porta individual " + id + " removida do BigDoors; snapshot restaurado. Use personal off para retirar o registro.");}
                available=false;return;
            }
            available=true;locked=door.isLocked();
        }
        boolean inside(Location at,double width,double height){return blocks.stream().anyMatch(b->b.crosses(at.getX(),at.getY(),at.getZ(),at.getX(),at.getY(),at.getZ(),width,height));}
        boolean mightIntersect(Location from,Location to,double width,double height){
            return Math.max(from.getX(),to.getX())+width>minX && Math.min(from.getX(),to.getX())-width<maxX
                    && Math.max(from.getY(),to.getY())+height>minY && Math.min(from.getY(),to.getY())<maxY
                    && Math.max(from.getZ(),to.getZ())+width>minZ && Math.min(from.getZ(),to.getZ())-width<maxZ;
        }
        void clearPhysical(){
            if(!loaded())return;
            for(DoorVoxel b:blocks){BlockData current=world().getBlockAt(b.x(),b.y(),b.z()).getBlockData();
                if(!current.getMaterial().isAir()&&!current.getAsString().equals(b.data())){
                    if(!conflict)plugin.getLogger().severe("Porta individual "+id+" bloqueada: alteração externa no snapshot. Blocos não serão sobrescritos.");
                    conflict=true;return;
                }
            }
            conflict=false;
            for(DoorVoxel b:blocks)if(!world().getBlockAt(b.x(),b.y(),b.z()).getType().isAir())world().getBlockAt(b.x(),b.y(),b.z()).setType(Material.AIR,false);
        }
        void mask(Player player,boolean open){
            if(!player.isOnline()||player.getWorld()!=world())return;
            Map<io.papermc.paper.math.BlockPosition,BlockData> changes=new HashMap<>();
            for(DoorVoxel b:blocks)if(player.isChunkSent(((long)(b.x()>>4)&0xffffffffL)|((long)(b.z()>>4)<<32)))
                changes.put(io.papermc.paper.math.Position.block(b.x(),b.y(),b.z()),open?Material.AIR.createBlockData():data.get(b));
            if(!changes.isEmpty())player.sendMultiBlockChange(changes);
        }
        void restoreView(Player player){if(!player.isOnline()||player.getWorld()!=world())return;for(DoorVoxel b:blocks)if(world().isChunkLoaded(b.x()>>4,b.z()>>4)){Location at=new Location(world(),b.x(),b.y(),b.z());player.sendBlockChange(at,at.getBlock().getBlockData());}}
        void transition(Session session,boolean open){
            session.open=open;
            int displays=gates.values().stream().flatMap(g->g.sessions.values().stream()).mapToInt(s->s.displays.size()).sum();
            if(session.displays.isEmpty() && displays+blocks.size()>MAX_DISPLAYS){
                session.step=open?20:0;session.fresh=true;mask(session.player,open);return;
            }
            if(session.displays.isEmpty())for(DoorVoxel b:blocks){
                BlockDisplay display=world().spawn(new Location(world(),b.x(),b.y(),b.z()),BlockDisplay.class,e->{
                    e.setPersistent(false);e.setVisibleByDefault(false);e.setBlock(data.get(b));
                    e.getPersistentDataContainer().set(marker,PersistentDataType.LONG,id);
                    e.setInterpolationDuration(1);e.setTeleportDuration(1);e.setViewRange(1);
                    e.setTransformation(new Transformation(new Vector3f(0,direction.offset(maxY-minY,session.step),0),new Quaternionf(),new Vector3f(1),new Quaternionf()));
                });
                session.player.showEntity(plugin,display);session.displays.add(display);
            }
            mask(session.player,true);
        }
        void animate(Session session){
            session.step=Math.max(0,Math.min(20,session.step+(session.open?1:-1)));
            positionDisplays(session);
        }
        void positionDisplays(Session session){
            for(BlockDisplay display:session.displays){display.setInterpolationDelay(0);display.setTransformation(new Transformation(
                    new Vector3f(0,direction.offset(maxY-minY,session.step),0),new Quaternionf(),new Vector3f(1),new Quaternionf()));}
        }
        void dropSessions(){for(Session session:sessions.values())session.destroy();sessions.clear();}
        void restore(){
            dropSessions();World world=world();if(world==null)return;
            for(DoorVoxel b:blocks)if(world.isChunkLoaded(b.x()>>4,b.z()>>4)){
                org.bukkit.block.Block block=world.getBlockAt(b.x(),b.y(),b.z());
                if(block.getType().isAir())block.setBlockData(data.get(b),false);
            }
            for(Player player:world.getPlayers())restoreView(player);
        }
    }
    private static final class Session {
        final Player player;
        final List<BlockDisplay> displays=new ArrayList<>();
        boolean open,fresh=true;
        int step;
        Session(Player player){this.player=player;}
        void destroy(){displays.forEach(Entity::remove);displays.clear();}
    }
}
