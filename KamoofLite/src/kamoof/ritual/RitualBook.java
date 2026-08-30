package kamoof.ritual;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

/**
 * Le livre "Pacte Demonique" qui tombe a la fin du rituel (porte de RitualBook S2).
 * 2 pages MiniMessage cliquables ; PDC uuid = token a usage unique.
 */
public final class RitualBook {

    private static final NamespacedKey KEY_UUID = new NamespacedKey("kamooflite", "uuid");

    private static final String NAME = "<dark_red>Pacte Demonique";
    private static final String LORE = "<gray>par <red>Lucifer";

    private static final String PAGE1 =
            "<dark_red>Pacte Ensanglante<br><br><red>Ce pacte te permet d'augmenter ta vie de "
            + "<dark_red>5 <red>coeurs t'est propose, mais ta prochaine mort te coutera 3 tetes..."
            + "<br><dark_red><b>Vas-tu l'accepter ?<br><br>"
            + "<hover:show_text:\"<dark_purple>Clique pour accepter ce pacte\">"
            + "<click:run_command:/ritual pacte 1><red><b>[Accepter le Pacte]";

    private static final String PAGE2 =
            "<dark_gray>Pacte Oublie<br><br><gray>Ce pacte te permet de ne <dark_gray><b>pas laisser de tete</b> "
            + "<gray>derriere toi a ta mort, cependant durant le restant de cette vie tu seras plus faible"
            + "<br><dark_gray><b>Vas-tu l'accepter ?<br><br>"
            + "<hover:show_text:\"<dark_purple>Clique pour accepter ce pacte\">"
            + "<click:run_command:/ritual pacte 2>[Accepter le Pacte]";

    private RitualBook() { }

    public static ItemStack getBook(UUID uuid) {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();
        meta.setGeneration(BookMeta.Generation.TATTERED);
        meta.title(RitualManager.mini(NAME));
        meta.author(Component.text("Lucifer"));
        meta.itemName(RitualManager.mini(NAME));
        meta.lore(List.of(RitualManager.mini(LORE)));
        meta.setFireResistant(true);
        meta.pages(List.of(RitualManager.mini(PAGE1), RitualManager.mini(PAGE2)));
        meta.getPersistentDataContainer().set(KEY_UUID, PersistentDataType.STRING, uuid.toString());
        item.setItemMeta(meta);
        return item;
    }

    public static UUID getUUID(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        String uuid = meta.getPersistentDataContainer().get(KEY_UUID, PersistentDataType.STRING);
        if (uuid == null) return null;
        try {
            return UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
