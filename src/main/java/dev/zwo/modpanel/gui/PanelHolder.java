package dev.zwo.modpanel.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

/** Identifica os inventários do painel e guarda as ações de cada slot. */
final class PanelHolder implements InventoryHolder {

    enum View { MAIN, BANS, TEMP_BANS, IP_BANS, MUTES, PROFILE, CONFIRM }

    enum Kind {
        OPEN_MAIN, OPEN_BANS, OPEN_TEMP_BANS, OPEN_IP_BANS, OPEN_MUTES, OPEN_PROFILE,
        PREVIOUS, NEXT, REFRESH,
        UNBAN_PROFILE, UNBAN_IP, UNMUTE, UNBAN_ALL, UNMUTE_ALL,
        CONFIRM, CANCEL
    }

    record Action(Kind kind, String value) {
        static Action of(Kind kind) {
            return new Action(kind, null);
        }
    }

    record Click(Action left, Action right) {
    }

    private final View view;
    private final int page;
    private final String target;
    private final PanelHolder back;
    private final Action pending;
    private final Map<Integer, Click> clicks = new HashMap<>();
    private Inventory inventory;

    PanelHolder(View view, int page, String target) {
        this(view, page, target, null, null);
    }

    PanelHolder(View view, int page, String target, PanelHolder back, Action pending) {
        this.view = view;
        this.page = page;
        this.target = target;
        this.back = back;
        this.pending = pending;
    }

    View view() {
        return view;
    }

    int page() {
        return page;
    }

    String target() {
        return target;
    }

    PanelHolder back() {
        return back;
    }

    Action pending() {
        return pending;
    }

    void bind(int slot, Action left, Action right) {
        clicks.put(slot, new Click(left, right));
    }

    void bind(int slot, Action left) {
        bind(slot, left, null);
    }

    Click click(int slot) {
        return clicks.get(slot);
    }

    void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
