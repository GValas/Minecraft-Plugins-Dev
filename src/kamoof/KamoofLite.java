package kamoof;

import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.attribute.Attribute;

import kamoof.ritual.RitualBook;
import kamoof.ritual.RitualListener;
import kamoof.ritual.RitualManager;
import kamoof.ritual.RitualSetup;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

public class KamoofLite extends JavaPlugin implements Listener {

    // Profil d'origine de chaque joueur deguise (pour /undisguise et la mort)
    private final Map<UUID, PlayerProfile> original = new HashMap<>();

    // Profil authlib NMS d'origine (uuid + vrai nom + textures) capture AVANT le deguisement.
    // Repose verbatim a la restauration -> garantit le vrai skin (corrige le skin Steve/crack de v2.4).
    private final Map<UUID, Object> originalProfileNms = new HashMap<>();

    // Pseudo du deguisement en cours (pour le message de deconnexion).
    private final Map<UUID, String> disguiseName = new HashMap<>();

    // Heure de debut du deguisement en cours (export dashboard).
    private final Map<UUID, Long> disguiseSince = new HashMap<>();

    // Active la tentative de respawn NMS pour rafraichir SA PROPRE vue (F5).
    // Si la version casse, on peut le couper sans toucher au reste.
    private static final boolean SELF_REFRESH = true;
    // Logge une seule fois les signatures NMS trouvees (diagnostic pour finaliser le respawn).
    private boolean diagDone = false;

    // ---------------------------------------------------------------------
    // Masse unique sur le serveur (v2.9)
    // ---------------------------------------------------------------------
    // Memoire persistante de ce qu'on ne peut PAS observer en direct :
    //  - offlineMaceHolders : UUID de joueurs HORS-LIGNE dont le dernier inventaire vu
    //    (inv + ender chest, shulkers/bundles inclus) contenait une masse.
    //  - maceContainers : emplacements "monde;x;y;z" de conteneurs vus contenir une masse.
    // Tout le reste (joueurs en ligne, masses au sol) est recompte a la demande.
    private final Set<UUID> offlineMaceHolders = new HashSet<>();
    private final Set<String> maceContainers = new HashSet<>();
    private File maceFile;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        chargerMasse();
        // Rituel des tetes (feature portee de KamoofSMP S2)
        getServer().getPluginManager().registerEvents(new RitualSetup(), this);
        getServer().getPluginManager().registerEvents(new RitualListener(), this);
        RitualManager.load(this);
        ecrireDisguises(); // pas de persistance -> etat vide au demarrage
        getLogger().info("KamoofLite v3.1 actif.");
    }

    @Override
    public void onDisable() {
        sauverMasse();
        RitualManager.save();
    }

    // Charge la memoire persistante de la masse unique depuis mace.yml.
    private void chargerMasse() {
        maceFile = new File(getDataFolder(), "mace.yml");
        if (!maceFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(maceFile);
        for (String s : cfg.getStringList("offline-holders")) {
            try { offlineMaceHolders.add(UUID.fromString(s)); } catch (IllegalArgumentException ignore) { }
        }
        maceContainers.addAll(cfg.getStringList("containers"));
    }

    // Sauve la memoire persistante (appelee a chaque modification + onDisable).
    private void sauverMasse() {
        try {
            if (maceFile == null) maceFile = new File(getDataFolder(), "mace.yml");
            getDataFolder().mkdirs();
            YamlConfiguration cfg = new YamlConfiguration();
            List<String> holders = new ArrayList<>();
            for (UUID id : offlineMaceHolders) holders.add(id.toString());
            cfg.set("offline-holders", holders);
            cfg.set("containers", new ArrayList<>(maceContainers));
            cfg.save(maceFile);
        } catch (Throwable t) {
            getLogger().warning("[masse] sauvegarde mace.yml echouee: " + t.getMessage());
        }
    }

    // Exporte l'etat des deguisements EN COURS vers plugins/KamoofLite/disguises.json,
    // lu par le dashboard web via le partage SMB. Les pseudos Minecraft sont [A-Za-z0-9_],
    // donc aucun echappement JSON n'est necessaire.
    private void ecrireDisguises() {
        try {
            getDataFolder().mkdirs();
            StringBuilder sb = new StringBuilder();
            sb.append("{\"updated\":").append(System.currentTimeMillis()).append(",\"disguises\":[");
            boolean first = true;
            for (Map.Entry<UUID, String> e : disguiseName.entrySet()) {
                PlayerProfile own = original.get(e.getKey());
                Player p = Bukkit.getPlayer(e.getKey());
                String real = (own != null && own.getName() != null) ? own.getName()
                        : (p != null ? p.getName() : e.getKey().toString());
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"player\":\"").append(real)
                  .append("\",\"uuid\":\"").append(e.getKey())
                  .append("\",\"as\":\"").append(e.getValue())
                  .append("\",\"since\":").append(disguiseSince.getOrDefault(e.getKey(), 0L))
                  .append('}');
            }
            sb.append("]}");
            java.nio.file.Files.writeString(new File(getDataFolder(), "disguises.json").toPath(), sb.toString());
        } catch (Throwable t) {
            getLogger().warning("[deguisement] export disguises.json echoue: " + t.getMessage());
        }
    }

    // Joueur deguise qui se deconnecte : le message de quit doit nommer le DEGUISEMENT,
    // pas le vrai pseudo. On nettoie aussi les Map (pas de persistance du deguisement).
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        // Masse unique : le joueur devient inobservable -> on memorise s'il emporte une masse.
        boolean changed;
        if (joueurPorteMasse(player)) {
            changed = offlineMaceHolders.add(id);
        } else {
            changed = offlineMaceHolders.remove(id);
        }
        if (changed) sauverMasse();

        String name = disguiseName.remove(id);
        PlayerProfile own = original.remove(id);
        originalProfileNms.remove(id);
        disguiseSince.remove(id);
        if (name == null) return; // pas deguise -> message vanilla inchange
        // Le message de quit vanilla affichera le pseudo du DEGUISEMENT : on logge le
        // vrai pseudo pour que le dashboard puisse relier les deux.
        String real = (own != null && own.getName() != null) ? own.getName() : player.getName();
        getLogger().info("[deguisement] " + real + " quitte (etait deguise en " + name + ")");
        ecrireDisguises();
        event.quitMessage(Component.translatable("multiplayer.player.left",
                Component.text(name)).color(NamedTextColor.YELLOW));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        PlayerProfile own = restoreAppearance(player);

        // Pacte du rituel : influe sur le nombre de tetes droppees.
        String pacte = RitualManager.getPacte(player);
        int headCount = 1;
        if ("2".equalsIgnoreCase(pacte)) {
            // Pacte Oublie : aucune tete ne tombe.
            headCount = 0;
            RitualManager.setPacte(player, null);
            RitualManager.send(player, RitualManager.MSG_DEATH_FORGOTTEN);
        } else if ("1".equalsIgnoreCase(pacte)) {
            // Pacte Ensanglante : 3 tetes + retrait du bonus de vie.
            headCount = RitualManager.BLOODY_HEADS;
            try {
                player.getAttribute(Attribute.MAX_HEALTH).removeModifier(RitualManager.hpModifier());
            } catch (Throwable ignore) { }
            RitualManager.setPacte(player, null);
            RitualManager.send(player, RitualManager.MSG_DEATH_BLOODY);
        }
        if (headCount <= 0) return;

        // Drop d'une (ou plusieurs) tete(s) portant le vrai skin (textures) du joueur mort
        try {
            PlayerProfile headProfile = (own != null) ? own : player.getPlayerProfile();
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setPlayerProfile(headProfile);
            head.setItemMeta(meta);
            head.setAmount(headCount);
            event.getDrops().add(head);
        } catch (Throwable t) {
            getLogger().warning("Drop tete echoue: " + t.getMessage());
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.PLAYER_HEAD) return;
        if (!(item.getItemMeta() instanceof SkullMeta meta)) return;

        PlayerProfile headProfile = meta.getPlayerProfile();
        if (headProfile == null || headProfile.getName() == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        // Sauvegarde de l'apparence d'origine (une seule fois) : profil Bukkit + profil authlib NMS.
        UUID id = player.getUniqueId();
        if (!original.containsKey(id)) {
            original.put(id, player.getPlayerProfile());
            try {
                Object handle = player.getClass().getMethod("getHandle").invoke(player);
                Object gp = getGameProfile(handle);
                // On garde la reference vers le VRAI profil : on ne le mute jamais (le deguisement
                // cree toujours un nouveau GameProfile), donc ses textures restent intactes.
                if (gp != null) originalProfileNms.put(id, gp);
            } catch (Throwable t) {
                getLogger().warning("[capture] profil NMS d'origine non sauvegarde: " + t.getMessage());
            }
        }

        String name = headProfile.getName();

        if (headProfile.hasTextures()) {
            applyDisguise(player, headProfile, name);
            consumeOneHead(player);
            return;
        }
        // Ancienne tete sans textures : on les recupere depuis Mojang en async
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            boolean ok;
            try {
                ok = headProfile.complete(true);
            } catch (Throwable t) {
                ok = false;
            }
            final boolean completed = ok && headProfile.hasTextures();
            Bukkit.getScheduler().runTask(this, () -> {
                if (!completed) {
                    player.sendMessage("§cDeguisement impossible : skin introuvable.");
                    return;
                }
                applyDisguise(player, headProfile, name);
                consumeOneHead(player);
            });
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("ritual")) {
            return onRitualCommand(sender, args);
        }
        // /undisguise
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Commande reservee aux joueurs.");
            return true;
        }
        if (!original.containsKey(player.getUniqueId())) {
            player.sendMessage("§7Tu n'es pas deguise.");
            return true;
        }
        restoreAppearance(player);
        player.sendMessage("§aTu as repris ton apparence.");
        return true;
    }

    // /ritual setup (admin) | /ritual pacte <1|2> (accepte le pacte du livre en main)
    private boolean onRitualCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Commande reservee aux joueurs.");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("pacte")) {
            if (!args[1].equals("1") && !args[1].equals("2")) return true;
            ItemStack book = player.getInventory().getItemInMainHand();
            UUID uuid = RitualBook.getUUID(book);
            if (uuid == null) {
                book = player.getInventory().getItemInOffHand();
                uuid = RitualBook.getUUID(book);
            }
            if (uuid == null) return true;
            if (RitualManager.getPacte(player) != null) {
                RitualManager.send(player, RitualManager.MSG_ALREADY_CHOSE);
                return true;
            }
            if (!RitualManager.isValidToken(uuid)) return true;
            book.setAmount(0);
            RitualManager.setPacte(player, args[1]);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("setup")) {
            if (!RitualManager.hasAdminLevel(player)) {
                player.sendMessage("§cReserve aux administrateurs (niveau " + RitualManager.REQUIRED_OP_LEVEL + "+).");
                return true;
            }
            if (player.getInventory().addItem(RitualSetup.getItems()).isEmpty()) {
                player.sendMessage("§aItems de setup du rituel recus. Clic-droit un bloc avec le baton.");
            } else {
                player.sendMessage("§cInventaire plein.");
            }
            return true;
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("place")) {
            if (!RitualManager.hasAdminLevel(player)) {
                player.sendMessage("§cReserve aux administrateurs (niveau " + RitualManager.REQUIRED_OP_LEVEL + "+).");
                return true;
            }
            try {
                double x = Double.parseDouble(args[1]);
                double y = Double.parseDouble(args[2]);
                double z = Double.parseDouble(args[3]);
                org.bukkit.Location center = new org.bukkit.Location(player.getWorld(), x, y, z);
                if (RitualSetup.placeFullAltar(center, player)) {
                    player.sendMessage("§aAutel du rituel construit, centre en §e"
                            + (int) x + " " + (int) y + " " + (int) z + "§a (monde " + player.getWorld().getName() + ").");
                } else {
                    player.sendMessage("§cStructure ritual.nbt introuvable.");
                }
            } catch (NumberFormatException e) {
                player.sendMessage("§cCoordonnees invalides. Usage: /ritual place <x> <y> <z>");
            }
            return true;
        }
        player.sendMessage("§7/ritual setup §8— items d'installation (admin)");
        player.sendMessage("§7/ritual place <x> <y> <z> §8— construit l'autel centre sur ces coords (admin)");
        return true;
    }

    // Restaure l'apparence d'origine (utilise par /undisguise ET la mort).
    // Retourne le profil Bukkit d'origine (ou null si le joueur n'etait pas deguise),
    // pour reutilisation (ex: drop de la tete au vrai skin a la mort).
    private PlayerProfile restoreAppearance(Player player) {
        UUID id = player.getUniqueId();
        PlayerProfile own = original.remove(id);
        Object savedNms = originalProfileNms.remove(id);
        String was = disguiseName.remove(id);
        disguiseSince.remove(id);
        if (own == null && savedNms == null) return null;
        if (was != null) {
            String real = (own != null && own.getName() != null) ? own.getName() : player.getName();
            getLogger().info("[deguisement] " + real + " reprend son apparence (etait " + was + ")");
        }
        ecrireDisguises();
        try {
            if (own != null) player.setPlayerProfile(own); // coherence cote API Bukkit
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            if (savedNms != null) {
                // On repose le VRAI GameProfile authlib (uuid + nom + textures) tel quel.
                // -> skin correct pour les autres joueurs, sans reconstruction fragile.
                Field f = findFieldOfType(handle.getClass(), "com.mojang.authlib.GameProfile");
                if (f != null) { f.setAccessible(true); f.set(handle, savedNms); }
                if (SELF_REFRESH) { try { resendToSelf(handle); } catch (Throwable ignore) { } }
            } else {
                forceDisplayName(player, null); // repli : ancien chemin (reconstruit depuis le handle)
            }
            player.setPlayerListName(null); // reset du pseudo TAB
            player.displayName(Component.text(player.getName())); // reset du pseudo CHAT
            refresh(player); // les autres relisent le profil (skin + nom au-dessus de la tete)
        } catch (Throwable t) {
            getLogger().warning("Restore: " + t.getMessage());
        }
        return own;
    }

    private void applyDisguise(Player player, PlayerProfile headProfile, String name) {
        try {
            // On garde TON UUID (ton skin s'affiche sur toi, pas de doublon TAB),
            // on copie le nom + les textures, et on force le pseudo dans le TAB.
            PlayerProfile disguise = Bukkit.createProfile(player.getUniqueId(), name);
            disguise.setProperties(headProfile.getProperties());
            player.setPlayerProfile(disguise);
            player.setPlayerListName(name);
            player.displayName(Component.text(name)); // pseudo affiche dans le CHAT
            // NMS : change le nom AU-DESSUS de la tete (additif ; n'altere pas le skin ci-dessus).
            forceDisplayName(player, name);
            refresh(player);
            disguiseName.put(player.getUniqueId(), name); // pour le message de deconnexion
            disguiseSince.put(player.getUniqueId(), System.currentTimeMillis());
            PlayerProfile own = original.get(player.getUniqueId());
            String real = (own != null && own.getName() != null) ? own.getName() : player.getName();
            getLogger().info("[deguisement] " + real + " se deguise en " + name);
            ecrireDisguises();
            player.sendMessage("§aTu es maintenant deguise en " + name);
        } catch (Throwable t) {
            player.sendMessage("§cDeguisement echoue: " + t.getMessage());
        }
    }

    private void consumeOneHead(Player player) {
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand.getType() == Material.PLAYER_HEAD) {
            inHand.setAmount(inHand.getAmount() - 1);
        }
    }

    // Force les AUTRES clients a recharger le skin (cache puis reaffiche 2 ticks plus tard).
    // Comme le GameProfile NMS porte deja le nouveau nom, le re-spawn affiche le bon nom au-dessus.
    private void refresh(Player target) {
        List<Player> viewers = new ArrayList<>();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(target)) continue;
            viewer.hidePlayer(this, target);
            viewers.add(viewer);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Player viewer : viewers) {
                if (viewer.isOnline() && target.isOnline()) {
                    viewer.showPlayer(this, target);
                }
            }
        }, 2L);
    }

    // ---------------------------------------------------------------------
    // NMS : nom flottant AU-DESSUS de la tete
    // ---------------------------------------------------------------------
    // Le nom au-dessus de la tete vient du GameProfile.name de l'entite (pas du
    // setPlayerListName, qui n'agit que sur la TAB). Avec ton propre UUID, Bukkit
    // refuse de changer ce nom -> on le change directement sur le GameProfile NMS.
    // name == null  -> on remet ton vrai pseudo (depuis ton profil d'origine).
    // Tout est en try/catch : si la version ne colle pas, le skin marche quand meme.

    private void forceDisplayName(Player player, String name) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player); // CraftPlayer -> ServerPlayer
            Object profile = getGameProfile(handle);
            if (profile == null) {
                getLogger().warning("[nom] GameProfile NMS introuvable, nom au-dessus inchange.");
                return;
            }
            String realName = player.getName();
            String wanted = (name != null) ? name : realName;
            swapProfileName(handle, profile, player.getUniqueId(), wanted);
            // Le rafraichissement pour les AUTRES est gere par refresh() (hide/show).
            // Pour TA vue (F5), on tente un respawn NMS.
            if (SELF_REFRESH) resendToSelf(handle);
        } catch (Throwable t) {
            getLogger().warning("[nom] forceDisplayName a echoue (skin OK quand meme): " + t);
        }
    }

    private Object getGameProfile(Object handle) {
        // 1) methode getGameProfile()
        try {
            Method m = findNoArgMethodReturning(handle.getClass(), "com.mojang.authlib.GameProfile");
            if (m != null) return m.invoke(handle);
        } catch (Throwable ignored) { }
        // 2) champ de type GameProfile
        try {
            Field f = findFieldOfType(handle.getClass(), "com.mojang.authlib.GameProfile");
            if (f != null) { f.setAccessible(true); return f.get(handle); }
        } catch (Throwable ignored) { }
        return null;
    }

    // Construit un nouveau GameProfile(uuid, nom) avec les MEMES textures, puis le pose
    // sur le champ GameProfile du handle (sur-ecrit la reference -> compatible record immutable).
    //
    // Fix v2.8 : on ne RECOPIE plus les textures (l'ancienne recopie par putAll echouait a tous
    // les coups -> le profil pose etait sans textures -> les AUTRES joueurs + la TAB voyaient un
    // skin Steve/crack, alors que TOI tu gardais le bon skin via le cache client). A la place on
    // REUTILISE telle quelle la PropertyMap actuelle (celle que setPlayerProfile vient de poser,
    // donc deja remplie avec les textures du deguisement) et on la passe au constructeur record
    // a 3 args GameProfile(UUID, String, PropertyMap). Plus aucune recopie => les textures ne
    // peuvent plus se perdre.
    private void swapProfileName(Object handle, Object oldProfile, UUID uuid, String name) throws Exception {
        Class<?> gp = Class.forName("com.mojang.authlib.GameProfile");
        Class<?> pmClass = Class.forName("com.mojang.authlib.properties.PropertyMap");
        Object props = invokeProperties(gp, oldProfile); // PropertyMap actuelle (textures du deguisement)
        Object np = (props != null)
                ? gp.getConstructor(UUID.class, String.class, pmClass).newInstance(uuid, name, props)
                : gp.getConstructor(UUID.class, String.class).newInstance(uuid, name);
        Field f = findFieldOfType(handle.getClass(), "com.mojang.authlib.GameProfile");
        if (f == null) throw new NoSuchFieldException("champ GameProfile introuvable sur " + handle.getClass());
        f.setAccessible(true);
        f.set(handle, np);
    }

    // Tente de renvoyer un paquet respawn au joueur pour rafraichir SA vue (skin + nom en F5).
    // La signature de ClientboundRespawnPacket depend de la version : on logge un diagnostic
    // une fois, on tente les constructions plausibles, et on echoue proprement sinon.
    private void resendToSelf(Object handle) {
        try {
            Class<?> respawnClass;
            try {
                respawnClass = Class.forName("net.minecraft.network.protocol.game.ClientboundRespawnPacket");
            } catch (ClassNotFoundException e) {
                getLogger().warning("[nom] ClientboundRespawnPacket introuvable (mapping ?). Self-view non rafraichie.");
                return;
            }
            if (!diagDone) {
                diagDone = true;
                getLogger().info("[diag] ClientboundRespawnPacket constructeurs:");
                for (Constructor<?> c : respawnClass.getConstructors()) {
                    getLogger().info("[diag]   " + c);
                }
                getLogger().info("[diag] ServerPlayer methodes 'createCommonSpawnInfo'/respawn-like:");
                for (Method m : handle.getClass().getMethods()) {
                    String n = m.getName().toLowerCase();
                    if (n.contains("spawninfo") || n.contains("respawn") || n.contains("dimension")) {
                        getLogger().info("[diag]   " + m);
                    }
                }
                getLogger().info("[diag] >>> Colle ces lignes a Claude pour finaliser le respawn self-view.");
            }
            // TODO(live): construire ClientboundRespawnPacket avec la vraie signature 26.1.2
            // (a partir du diagnostic ci-dessus) puis: getConnection(handle).send(packet)
            // + repositionner le joueur. Laisse en TODO tant qu'on n'a pas confirme la signature,
            // pour ne pas envoyer un paquet malforme qui glitcherait le client.
        } catch (Throwable t) {
            getLogger().warning("[nom] resendToSelf a echoue: " + t);
        }
    }

    // Recupere la PropertyMap d'un GameProfile en gerant les deux API authlib :
    // getProperties() (ancienne) et properties() (record, MC 26.x). Sans ca, la copie
    // des textures echoue silencieusement et le skin disparait pour les autres joueurs.
    private Object invokeProperties(Class<?> gp, Object profile) throws Exception {
        for (String n : new String[]{"getProperties", "properties"}) {
            try {
                return gp.getMethod(n).invoke(profile);
            } catch (NoSuchMethodException ignored) {
                // on tente le nom suivant
            }
        }
        throw new NoSuchMethodException("GameProfile : ni getProperties() ni properties() trouve");
    }

    // --- petits utilitaires de reflexion ---

    private Method findNoArgMethodReturning(Class<?> cls, String returnTypeName) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType().getName().equals(returnTypeName)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private Field findFieldOfType(Class<?> cls, String typeName) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getName().equals(typeName)) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Masse unique : detection "une masse existe-t-elle ?" (recompte a la demande)
    // ---------------------------------------------------------------------

    // Vrai si une masse se trouve dans ce stack, ou imbriquee dans une shulker box / un bundle.
    private boolean stackContientMasse(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return false;
        if (it.getType() == Material.MACE) return true;
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState()) {
            BlockState bs = bsm.getBlockState();
            if (bs instanceof InventoryHolder ih) {
                for (ItemStack inner : ih.getInventory().getContents()) {
                    if (stackContientMasse(inner)) return true;
                }
            }
        }
        if (meta instanceof BundleMeta bm && bm.hasItems()) {
            for (ItemStack inner : bm.getItems()) {
                if (stackContientMasse(inner)) return true;
            }
        }
        return false;
    }

    // Vrai si l'inventaire (ou un conteneur imbrique) contient une masse.
    private boolean inventaireContientMasse(Inventory inv) {
        if (inv == null) return false;
        for (ItemStack it : inv.getContents()) {
            if (stackContientMasse(it)) return true;
        }
        return false;
    }

    // Vrai si le joueur porte une masse (inventaire principal OU ender chest).
    private boolean joueurPorteMasse(Player p) {
        return inventaireContientMasse(p.getInventory()) || inventaireContientMasse(p.getEnderChest());
    }

    // Le coeur de la regle : une masse existe-t-elle quelque part sur le serveur ?
    private boolean uneMasseExiste() {
        // 1) Joueurs en ligne (inv + ender chest, shulkers/bundles inclus).
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (joueurPorteMasse(p)) return true;
        }
        // 2) Masses au sol (item entities) ou dans un cadre, dans les mondes charges.
        for (World w : Bukkit.getWorlds()) {
            for (Item e : w.getEntitiesByClass(Item.class)) {
                if (stackContientMasse(e.getItemStack())) return true;
            }
            for (ItemFrame f : w.getEntitiesByClass(ItemFrame.class)) {
                if (stackContientMasse(f.getItem())) return true;
            }
        }
        // 3) Porteurs HORS-LIGNE memorises (inobservables -> on fait confiance a la memoire).
        for (UUID id : offlineMaceHolders) {
            if (Bukkit.getPlayer(id) == null) return true; // toujours hors-ligne
        }
        // 4) Conteneurs memorises : si le chunk est decharge on garde, sinon on verifie (et on purge).
        if (verifierConteneurs()) return true;
        return false;
    }

    // Parcourt maceContainers : conteneur en chunk decharge -> compte comme existant ;
    // en chunk charge -> verifie reellement et purge l'entree si la masse n'y est plus.
    private boolean verifierConteneurs() {
        boolean trouve = false;
        boolean modifie = false;
        for (String key : new ArrayList<>(maceContainers)) {
            Location loc = parseLocation(key);
            if (loc == null || loc.getWorld() == null) { maceContainers.remove(key); modifie = true; continue; }
            if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                trouve = true; // chunk decharge -> gele -> la masse est toujours la
                continue;
            }
            BlockState bs = loc.getBlock().getState();
            if (bs instanceof InventoryHolder ih && inventaireContientMasse(ih.getInventory())) {
                trouve = true;
            } else {
                maceContainers.remove(key); // perime -> auto-reparation
                modifie = true;
            }
        }
        if (modifie) sauverMasse();
        return trouve;
    }

    // "monde;x;y;z" pour une position de bloc.
    private String clefDeLocation(Location loc) {
        return loc.getWorld().getName() + ";" + loc.getBlockX() + ";" + loc.getBlockY() + ";" + loc.getBlockZ();
    }

    private Location parseLocation(String key) {
        String[] p = key.split(";");
        if (p.length != 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            return new Location(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Emplacement de bloc d'un conteneur a partir de son InventoryHolder (null si ce n'est
    // pas un conteneur de bloc : inventaire joueur, table de craft, etc.).
    private Location locationDuConteneur(InventoryHolder holder) {
        if (holder instanceof DoubleChest dc) {
            InventoryHolder left = dc.getLeftSide();
            if (left instanceof BlockState bs) return bs.getLocation();
            return null;
        }
        if (holder instanceof BlockState bs) return bs.getLocation();
        return null;
    }

    // ---------------------------------------------------------------------
    // Masse unique : handlers d'evenements
    // ---------------------------------------------------------------------

    private boolean recetteEstMasse(Recipe recipe) {
        return recipe != null && recipe.getResult().getType() == Material.MACE;
    }

    // Apercu du craft : si une masse existe deja, on vide le slot resultat (feedback visuel direct).
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (!recetteEstMasse(event.getRecipe())) return;
        if (uneMasseExiste()) {
            event.getInventory().setResult(null);
        }
    }

    // Securite (couvre le shift-clic) : on annule la fabrication si une masse existe deja.
    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (!recetteEstMasse(event.getRecipe())) return;
        if (uneMasseExiste()) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player p) {
                p.sendMessage("§cUne masse existe deja sur le serveur. Impossible d'en fabriquer une autre.");
            }
        }
    }

    // Connexion : le joueur redevient observable en direct -> on le retire des porteurs hors-ligne.
    // (Satisfait aussi le "scan a la connexion" pour integrer une masse deja en jeu.)
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (offlineMaceHolders.remove(event.getPlayer().getUniqueId())) {
            sauverMasse();
        }
    }

    // Fermeture d'un conteneur de bloc : on met a jour la memoire des coffres a masse.
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();
        Location loc = locationDuConteneur(inv.getHolder());
        if (loc == null) return; // pas un conteneur de bloc
        String key = clefDeLocation(loc);
        boolean changed;
        if (inventaireContientMasse(inv)) {
            changed = maceContainers.add(key);
        } else {
            changed = maceContainers.remove(key);
        }
        if (changed) sauverMasse();
    }

    // Chargement de chunk : purge les entrees de conteneurs devenues invalides
    // (coffre supprime/vide pendant qu'on ne regardait pas) -> auto-reparation sans commande.
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (maceContainers.isEmpty()) return;
        Chunk chunk = event.getChunk();
        String world = chunk.getWorld().getName();
        boolean modifie = false;
        for (String key : new ArrayList<>(maceContainers)) {
            Location loc = parseLocation(key);
            if (loc == null) { maceContainers.remove(key); modifie = true; continue; }
            if (!loc.getWorld().getName().equals(world)) continue;
            if ((loc.getBlockX() >> 4) != chunk.getX() || (loc.getBlockZ() >> 4) != chunk.getZ()) continue;
            BlockState bs = loc.getBlock().getState();
            if (!(bs instanceof InventoryHolder ih) || !inventaireContientMasse(ih.getInventory())) {
                maceContainers.remove(key);
                modifie = true;
            }
        }
        if (modifie) sauverMasse();
    }
}
