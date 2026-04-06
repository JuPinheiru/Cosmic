package server.bots;

import client.Character;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.TimerManager;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class BotRestockManager {

    private static final Logger log = LoggerFactory.getLogger(BotRestockManager.class);

    public static int HP_POT_TARGET = 200;
    public static int MP_POT_TARGET = 200;
    private static final int HP_POT_COST = 320;  // White Potion (2000002)
    private static final int MP_POT_COST = 620;  // Mana Elixir (2000006)

    private static final int[][] POTION_TABLE = {
        {  1,  20, 2000002, 2000006 },
        { 21,  40, 2000003, 2000006 },
        { 41,  70, 2000004, 2000006 },
        { 71, 999, 2000005, 2001002 },
    };

    private record ShopCandidate(int itemId, int price) {}

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void startRestock(BotEntry entry, Character bot) {
        if (entry.restocking) return;

        entry.restocking = true;
        entry.grinding   = false;
        entry.following  = false;
        entry.grindMapId = bot.getMapId();

        BotRouteConfig.RestockRoute route = BotRouteConfig.getRestockRoute(entry.grindMapId);
        if (route == null) {
            log.warn("BotRestockManager: no route for grindMap {}, buying pots in place", entry.grindMapId);
            buyPotions(bot);
            entry.restocking = false;
            entry.grinding   = true;
            return;
        }

        BotManager.getInstance().botSay(bot, "brb restocking");
        log.info("BotRestockManager: {} starting restock from {}", bot.getName(), entry.grindMapId);

        // 1. Walk to town
        BotMapTraveler.travel(entry, bot, route.toTownPath(), () ->
            // 2. Walk to pot shop
            BotMapTraveler.travel(entry, bot, route.toPotShopPath(), () ->
                TimerManager.getInstance().schedule(() -> {
                    // 3. Buy potions
                    buyPotions(bot);

                    if (route.weaponShopNpcId() > 0 && !route.toWeaponShopPath().isEmpty()) {
                        // 4. Walk to weapon shop
                        BotMapTraveler.travel(entry, bot, route.toWeaponShopPath(), () ->
                            TimerManager.getInstance().schedule(() -> {
                                // 5. Buy gear
                                buyGear(entry, bot, route.weaponShopNpcId());
                                // 6. Walk back
                                BotMapTraveler.travel(entry, bot, route.toGrindPath(),
                                        () -> finishRestock(entry, bot));
                            }, 1000)
                        );
                    } else {
                        // No weapon shop — go straight back
                        BotMapTraveler.travel(entry, bot, route.toGrindPath(),
                                () -> finishRestock(entry, bot));
                    }
                }, 1500)
            )
        );
    }

    // -------------------------------------------------------------------------
    // Buy potions
    // -------------------------------------------------------------------------

    private static void buyPotions(Character bot) {
        int[] potIds  = resolvePotions(bot.getLevel());
        int hpPotId   = potIds[0];
        int mpPotId   = potIds[1];

        int[] current = BotManager.getInstance().countPotions(bot);
        int hpNeeded  = Math.max(0, HP_POT_TARGET - current[0]);
        int mpNeeded  = Math.max(0, MP_POT_TARGET - current[1]);
        int totalCost = hpNeeded * HP_POT_COST + mpNeeded * MP_POT_COST;

        if (bot.getMeso() < totalCost) {
            int budget = bot.getMeso();
            hpNeeded  = Math.min(hpNeeded, budget / (HP_POT_COST + 1));
            mpNeeded  = Math.min(mpNeeded, (budget - hpNeeded * HP_POT_COST) / (MP_POT_COST + 1));
            totalCost = hpNeeded * HP_POT_COST + mpNeeded * MP_POT_COST;
        }

        if (hpNeeded > 0) InventoryManipulator.addById(bot.getClient(), hpPotId, (short) hpNeeded);
        if (mpNeeded > 0) InventoryManipulator.addById(bot.getClient(), mpPotId, (short) mpNeeded);
        if (totalCost > 0) bot.gainMeso(-totalCost, false);

        BotManager.getInstance().setupAutopotForBot(bot);
        log.info("BotRestockManager: {} bought {}x HP {}x MP, -{} meso",
                bot.getName(), hpNeeded, mpNeeded, totalCost);
    }

    // -------------------------------------------------------------------------
    // Buy gear from NPC shop
    // -------------------------------------------------------------------------

    private static void buyGear(BotEntry entry, Character bot, int npcId) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        List<ShopCandidate> candidates = loadShopEquips(npcId);
        if (candidates.isEmpty()) return;

        Inventory equipped = bot.getInventory(InventoryType.EQUIPPED);
        int bought = 0;

        for (ShopCandidate candidate : candidates) {
            // Only equips
            if (candidate.itemId() < 1000000 || candidate.itemId() >= 2000000) continue;

            Equip shopEquip = (Equip) ii.getEquipById(candidate.itemId());
            if (shopEquip == null) continue;

            String textSlot = ii.getEquipmentSlot(candidate.itemId());
            if (textSlot == null) continue;
            constants.inventory.EquipSlot eslot =
                    constants.inventory.EquipSlot.getFromTextSlot(textSlot);
            short primarySlot = (short) eslot.getPrimarySlot();
            if (primarySlot == 0) continue;
            if (!ii.canWearEquipment(bot, shopEquip, primarySlot)) continue;
            if (!bot.getInventory(InventoryType.EQUIP).isFull() == false &&
                bot.getInventory(InventoryType.EQUIP).getNumFreeSlot() < 1) continue;

            // Compare req level — buy if shop item is a higher tier
            Equip current = (Equip) equipped.getItem(primarySlot);
            int shopReqLevel    = ii.getEquipLevelReq(candidate.itemId());
            int currentReqLevel = current != null ? ii.getEquipLevelReq(current.getItemId()) : 0;

            if (shopReqLevel <= currentReqLevel) continue;
            if (bot.getMeso() < candidate.price()) continue;
            if (!InventoryManipulator.checkSpace(bot.getClient(), candidate.itemId(), (short) 1, "")) continue;

            InventoryManipulator.addById(bot.getClient(), candidate.itemId(), (short) 1);
            bot.gainMeso(-candidate.price(), false);
            bought++;

            log.info("BotRestockManager: {} bought {} (reqLv {} > {}), -{} meso",
                    bot.getName(), ii.getName(candidate.itemId()),
                    shopReqLevel, currentReqLevel, candidate.price());
        }

        if (bought > 0) {
            BotEquipManager.autoEquip(bot, null, null);
            BotManager.getInstance().botSay(bot, "bought " + bought + " upgrade" + (bought > 1 ? "s" : ""));
        } else {
            log.info("BotRestockManager: {} no gear upgrades available", bot.getName());
        }
    }

    private static List<ShopCandidate> loadShopEquips(int npcId) {
        List<ShopCandidate> result = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                "SELECT si.itemid, si.price FROM shopitems si " +
                "JOIN shops s ON s.shopid = si.shopid " +
                "WHERE s.npcid = ? AND si.itemid >= 1000000 AND si.itemid < 2000000")) {
            ps.setInt(1, npcId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new ShopCandidate(rs.getInt("itemid"), rs.getInt("price")));
                }
            }
        } catch (Exception e) {
            log.warn("BotRestockManager: failed to load shop for npc {}", npcId, e);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Finish
    // -------------------------------------------------------------------------

    private static void finishRestock(BotEntry entry, Character bot) {
        entry.restocking = false;
        entry.grinding   = true;
        entry.grindMapId = 0;
        BotManager.getInstance().setupAutopotForBot(bot);
        BotManager.getInstance().botSay(bot, "back, lets grind");
        log.info("BotRestockManager: {} restock complete at map {}", bot.getName(), bot.getMapId());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static int[] resolvePotions(int level) {
        for (int[] row : POTION_TABLE) {
            if (level >= row[0] && level <= row[1]) return new int[]{row[2], row[3]};
        }
        return new int[]{2000004, 2000006};
    }
}
