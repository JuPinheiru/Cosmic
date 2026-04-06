package server.bots;

import java.util.List;
import java.util.Map;

/**
 * Defines grind zones, travel routes and restock stops for autonomous bots.
 *
 * HOW TO ADD A NEW ZONE:
 * 1. Add a GrindZone entry in GRIND_ZONES
 * 2. Add a RestockRoute entry in RESTOCK_ROUTES keyed by grindMapId
 *
 * RestockRoute defines:
 * - toTownPath: maps to walk from grind → pot shop
 * - potShopNpcId: NPC id of the potion shop
 * - toPotShopPath: maps from town → pot shop
 * - toWeaponShopPath: maps from pot shop → weapon shop
 * - weaponShopNpcId: NPC id of the weapon shop (0 = skip)
 * - toGrindPath: maps to walk back from last stop → grind map
 */
public class BotRouteConfig {

    public record GrindZone(int minLevel, int maxLevel, int[] grindMapIds) {
        public int randomGrindMap() {
            return grindMapIds[(int)(Math.random() * grindMapIds.length)];
        }
    }

    public record RestockRoute(
        List<Integer> toTownPath,        // grind → town
        int potShopNpcId,                // NPC id da loja de poções
        List<Integer> toPotShopPath,     // town → pot shop
        List<Integer> toWeaponShopPath,  // pot shop → weapon shop
        int weaponShopNpcId,             // NPC id da loja de equips (0 = skip)
        List<Integer> toGrindPath        // last stop → grind
    ) {}

    // -------------------------------------------------------------------------
    // Grind zones
    // -------------------------------------------------------------------------
    public static final List<GrindZone> GRIND_ZONES = List.of(
        new GrindZone(  1,  10, new int[]{ 104040000 }), // Pig Beach
        new GrindZone( 11,  20, new int[]{ 104040001 }), // Henesys HG II
        new GrindZone( 21,  30, new int[]{ 100010301 }), // Henesys HG III
        new GrindZone( 31,  40, new int[]{ 102020100 }), // Rocky Mountain I
        new GrindZone( 41,  50, new int[]{ 105040305 }), // Sleepywood Dungeon
        new GrindZone( 51,  60, new int[]{ 211040300 }), // Ludi
        new GrindZone( 61,  70, new int[]{ 220050300 }), // Omega Sector
        new GrindZone( 71,  80, new int[]{ 230020000 }), // Aqua Road
        new GrindZone( 81,  90, new int[]{ 240040400 }), // El Nath
        new GrindZone( 91, 999, new int[]{ 240050400 })  // Ice Valley
    );

    // -------------------------------------------------------------------------
    // Restock routes — keyed by grindMapId
    // Add more zones as you map the portal sequences in-game.
    // -------------------------------------------------------------------------
    public static final Map<Integer, RestockRoute> RESTOCK_ROUTES = Map.of(

        // Pig Beach (lv 1-10)
        // Grind → Henesys → Market → Dept Store (pots) → Market → Weapon Store (gear) → Market → Henesys → Grind
            104040000, new RestockRoute(
                    List.of(104040000, 100000000, 100000100, 100000102), // grind → Henesys → Market → Dept Store
                    1052116,                                              // pot shop NPC: Luna
                    List.of(),                                            // já chegou no pot shop
                    List.of(100000102, 100000100, 100000101),             // Dept Store → Market → Weapon Store
                    1051000,                                              // weapon shop NPC
                    List.of(100000101, 100000100, 100000000, 104040000)   // back to grind
            )

        // Add more routes here as you map them:
        // 100010200, new RestockRoute(...),
        // 102020100, new RestockRoute(...),
    );

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public static GrindZone getZoneForLevel(int level) {
        for (GrindZone zone : GRIND_ZONES) {
            if (level >= zone.minLevel() && level <= zone.maxLevel()) return zone;
        }
        return GRIND_ZONES.get(0);
    }

    public static RestockRoute getRestockRoute(int grindMapId) {
        return RESTOCK_ROUTES.get(grindMapId);
    }
}
