package server.bots;

import client.Character;
import server.maps.MapleMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks bots that run autonomously (no human owner).
 * Used by BotManager to tick ownerless bots and by BotAutoSpawner
 * to migrate bots when they level up.
 */
public class AutonomousBotRegistry {

    private static final AutonomousBotRegistry instance = new AutonomousBotRegistry();
    public static AutonomousBotRegistry getInstance() { return instance; }
    private AutonomousBotRegistry() {}

    // charId -> Character
    private final Map<Integer, Character> autonomousBots = new ConcurrentHashMap<>();

    public void register(Character bot) {
        autonomousBots.put(bot.getId(), bot);
    }

    public void unregister(int charId) {
        autonomousBots.remove(charId);
    }

    public boolean isAutonomous(int charId) {
        return autonomousBots.containsKey(charId);
    }

    public java.util.Collection<Character> getAll() {
        return autonomousBots.values();
    }

    /**
     * Called when a bot levels up — checks if it should migrate to a new grind map.
     */
    public void onBotLevelUp(Character bot) {
        if (!isAutonomous(bot.getId())) return;
        int newMap = BotAutoSpawner.resolveGrindMap(bot.getLevel());
        if (newMap == bot.getMapId()) return; // already on right map

        MapleMap map;
        try {
            map = bot.getClient().getChannelServer().getMapFactory().getMap(newMap);
        } catch (Exception e) {
            return; // mapa não existe no WZ
        }
        if (map == null) return;

        bot.forceChangeMap(map, map.getRandomPlayerSpawnpoint());

        BotManager botManager = BotManager.getInstance();
        BotEntry entry = botManager.getFirstBotEntry(-bot.getId());
        if (entry != null) {
            entry.grinding = true;
            entry.following = false;
            BotManager.getInstance().botSay(bot, "lv" + bot.getLevel() + "! moving to new spot");
        }
    }
}
