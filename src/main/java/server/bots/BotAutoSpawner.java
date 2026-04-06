package server.bots;

import client.Character;
import client.DefaultDates;
import client.Job;
import client.creator.BotCreator;
import client.BotClient;
import net.server.Server;
import net.server.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import tools.BCrypt;
import tools.DatabaseConnection;

import java.awt.Point;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Automatically spawns and manages a pool of autonomous bots on server startup.
 * Bots are created if fewer than TARGET_BOT_COUNT exist, then spawned into
 * level-appropriate grind maps.
 */
public class BotAutoSpawner {

    private static final Logger log = LoggerFactory.getLogger(BotAutoSpawner.class);

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------
    public static final int TARGET_BOT_COUNT = 20;
    public static final int WORLD = 0;
    public static final int CHANNEL = 1;

    /** All possible random bot names. */
    private static final List<String> NAME_POOL = List.of(
        "Arturia", "Percival", "Lancelot", "Galahad", "Tristan",
        "Morgana", "Viviane", "Isolde", "Elaine", "Nimue",
        "Aldric", "Brennan", "Cedric", "Dorian", "Edric",
        "Faelan", "Gareth", "Hadwin", "Ingvar", "Jorvik",
        "Kaelith", "Lorien", "Maevis", "Nereth", "Orvyn",
        "Pyriel", "Quelith", "Roveth", "Sylvan", "Thalos",
        "Ulveth", "Vyreth", "Wystan", "Xolan", "Yreth",
        "Zephyr", "Arwen", "Braxton", "Corvin", "Dalan"
    );

    /** Jobs available for random assignment. */
    private static final int[] JOB_POOL = {
        100, // Swordsman (Warrior path)
        200, // Magician
        300, // Bowman (Archer path)
        400, // Rogue (Thief path)
        500, // Pirate
    };

    // -------------------------------------------------------------------------
    // Grind map roadmap: level range → mapId
    // -------------------------------------------------------------------------
    private static final int[][] GRIND_MAPS = {
        // {minLevel, maxLevel, mapId}
        {  1,  10, 100010100  }, // Henesys Hunting Ground I (temporário para teste)
        { 11,  20, 104040000 }, // Pig Beach
        { 21,  30, 101030104 }, // Dungeon Road (Orange Mushroom)
        { 31,  40, 102030100 }, // Ant Tunnel
        { 41,  50, 105040305 }, // Sleepywood Dungeon
        { 51,  60, 211040300 }, // Ludibrium
        { 61,  70, 220050300 }, // Omega Sector
        { 71,  80, 230020000 }, // Aqua Road
        { 81,  90, 240040400 }, // El Nath
        { 91, 999, 240050400 }, // Ice Valley
    };

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------
    private static final BotAutoSpawner instance = new BotAutoSpawner();
    public static BotAutoSpawner getInstance() { return instance; }
    private BotAutoSpawner() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Called from Server.init() after worlds/channels are up.
     * Schedules auto-spawn with a short delay to ensure everything is ready.
     */
    public void scheduleAutoSpawn() {
        TimerManager.getInstance().schedule(() -> {
            try {
                autoSpawnBots();
            } catch (Exception e) {
                log.error("BotAutoSpawner: failed to auto-spawn bots", e);
            }
        }, 5000); // 5s delay after server start
    }

    // -------------------------------------------------------------------------
    // Core logic
    // -------------------------------------------------------------------------

    private void autoSpawnBots() throws SQLException {
        log.info("BotAutoSpawner: checking bot pool...");

        List<Integer> existingBotCharIds = loadExistingBotCharIds();
        int existing = existingBotCharIds.size();
        log.info("BotAutoSpawner: found {} existing bots, target is {}", existing, TARGET_BOT_COUNT);

        // Create missing bots
        if (existing < TARGET_BOT_COUNT) {
            int toCreate = TARGET_BOT_COUNT - existing;
            List<Integer> newIds = createBots(toCreate);
            existingBotCharIds.addAll(newIds);
            log.info("BotAutoSpawner: created {} new bots", newIds.size());
        }

        // Spawn all bots
        int spawned = 0;
        for (int charId : existingBotCharIds) {
            try {
                spawnBot(charId);
                spawned++;
            } catch (Exception e) {
                log.warn("BotAutoSpawner: failed to spawn charId={}", charId, e);
            }
        }
        log.info("BotAutoSpawner: spawned {}/{} bots successfully", spawned, existingBotCharIds.size());
    }

    private List<Integer> loadExistingBotCharIds() throws SQLException {
        List<Integer> ids = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                "SELECT c.id FROM characters c " +
                "JOIN accounts a ON a.id = c.accountid " +
                "WHERE a.is_bot = 1 AND c.id IS NOT NULL " +
                "ORDER BY c.id LIMIT ?")) {
            ps.setInt(1, TARGET_BOT_COUNT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt("id"));
            }
        }
        return ids;
    }

    private List<Integer> createBots(int count) throws SQLException {
        List<String> usedNames = getUsedBotNames();
        List<Integer> createdIds = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String name = pickUnusedName(usedNames);
            if (name == null) {
                log.warn("BotAutoSpawner: ran out of available names after creating {} bots", i);
                break;
            }
            usedNames.add(name.toLowerCase());

            try {
                int charId = createBotAccount(name);
                if (charId > 0) {
                    createdIds.add(charId);
                    log.info("BotAutoSpawner: created bot '{}' charId={}", name, charId);
                }
            } catch (Exception e) {
                log.warn("BotAutoSpawner: failed to create bot '{}'", name, e);
            }
        }
        return createdIds;
    }

    private int createBotAccount(String name) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection()) {
            // Create account
            String hashedPw = BCrypt.hashpw("botbot", BCrypt.gensalt(12));
            int accountId;
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO accounts (name, password, birthday, tempban, is_bot) VALUES (?, ?, ?, ?, 1)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, hashedPw);
                ps.setDate(3, Date.valueOf(DefaultDates.getBirthday()));
                ps.setTimestamp(4, Timestamp.valueOf(DefaultDates.getTempban()));
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (!rs.next()) return -1;
                    accountId = rs.getInt(1);
                }
            }

            // Create character
            BotClient botClient = new BotClient(WORLD, CHANNEL);
            botClient.setAccID(accountId);
            botClient.setAccountName(name);

            int charId = BotCreator.createCharacter(botClient, name);
            if (charId <= 0) {
                log.warn("BotAutoSpawner: BotCreator failed for '{}'", name);
                return -1;
            }

            // Mark character as bot
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE characters SET is_bot = 1 WHERE id = ?")) {
                ps.setInt(1, charId);
                ps.executeUpdate();
            }

            return charId;
        }
    }

    private void spawnBot(int charId) throws SQLException {
        Channel ch = Server.getInstance().getChannel(WORLD, CHANNEL);
        if (ch == null) {
            log.warn("BotAutoSpawner: channel {}-{} not available", WORLD, CHANNEL);
            return;
        }

        // Load basic info to find the right grind map
        int level = getBotLevel(charId);
        int mapId = resolveGrindMap(level);

        var mapFactory = ch.getMapFactory();
        var map = mapFactory.getMap(mapId);
        if (map == null) {
            log.warn("BotAutoSpawner: map {} not found for charId={}", mapId, charId);
            return;
        }

        Point spawnPos = map.getRandomPlayerSpawnpoint().getPosition();
        BotManager botManager = BotManager.getInstance();
        log.info("BotAutoSpawner: loading bot charId={} into map={}", charId, map != null ? map.getId() : "NULL");
        Character botChar = botManager.loadOfflineBot(charId, WORLD, CHANNEL, map, spawnPos);

        // Register as autonomous bot (no owner)
        AutonomousBotRegistry.getInstance().register(botChar);
        log.info("BotAutoSpawner: registered charId={} as autonomous", botChar.getId());

        // Start grind
        BotEntry entry = botManager.registerSpawnedBot(-botChar.getId(), null, botChar);
        if (entry != null) {
            entry.fhIndex = BotMovementManager.buildFhIndex(botChar.getMap());
            entry.lastMapId = botChar.getMapId();
        }  // negative id = ownerless
        log.info("BotAutoSpawner: entry={}, autonomous={}", entry,
                AutonomousBotRegistry.getInstance().isAutonomous(botChar.getId()));
        if (entry != null) {
            entry.grinding = true;
            entry.following = false;
            BotManager.getInstance().setupAutopotForBot(botChar);
            log.info("BotAutoSpawner: fhIndex size={}, monsters={}, map={}",
                    entry.fhIndex != null ? entry.fhIndex.size() : -1,
                    botChar.getMap().getAllMonsters().size(),
                    botChar.getMapId());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public static int resolveGrindMap(int level) {
        return BotRouteConfig.getZoneForLevel(level).randomGrindMap();
    }

    private int getBotLevel(int charId) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT level FROM characters WHERE id = ?")) {
            ps.setInt(1, charId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("level") : 1;
            }
        }
    }

    private List<String> getUsedBotNames() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                "SELECT LOWER(name) FROM accounts WHERE is_bot = 1");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) names.add(rs.getString(1));
        }
        return names;
    }

    private String pickUnusedName(List<String> usedNames) {
        List<String> shuffled = new ArrayList<>(NAME_POOL);
        java.util.Collections.shuffle(shuffled, ThreadLocalRandom.current());
        for (String name : shuffled) {
            if (!usedNames.contains(name.toLowerCase())) return name;
        }
        return null;
    }
}
