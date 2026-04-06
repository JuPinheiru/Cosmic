package server.bots;

import client.Character;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.maps.MapleMap;
import server.maps.Portal;

import java.util.List;

/**
 * Handles bot navigation between maps by walking through portals.
 * Works with BotRouteConfig to follow predefined map sequences.
 */
public class BotMapTraveler {

    private static final Logger log = LoggerFactory.getLogger(BotMapTraveler.class);

    // How long to wait after entering a map before looking for next portal (ms)
    private static final int MAP_SETTLE_DELAY_MS = 3000;
    // How long to wait for portal traversal before giving up (ms)
    private static final int PORTAL_TIMEOUT_MS = 20000;

    /**
     * Starts traveling through a sequence of maps.
     * When complete, calls onArrival.
     */
    public static void travel(BotEntry entry, Character bot, List<Integer> route, Runnable onArrival) {
        if (route == null || route.isEmpty()) {
            onArrival.run();
            return;
        }

        // Skip maps we're already past
        int startIdx = 0;
        for (int i = 0; i < route.size(); i++) {
            if (route.get(i) == bot.getMapId()) {
                startIdx = i + 1;
                break;
            }
        }

        if (startIdx >= route.size()) {
            // Already at destination
            onArrival.run();
            return;
        }

        log.info("BotMapTraveler: {} traveling route {} starting at index {}",
                bot.getName(), route, startIdx);

        travelStep(entry, bot, route, startIdx, onArrival, 0);
    }

    private static void travelStep(BotEntry entry, Character bot, List<Integer> route,
                                   int stepIdx, Runnable onArrival, int attempts) {
        if (stepIdx >= route.size()) {
            log.info("BotMapTraveler: {} arrived at destination {}", bot.getName(), bot.getMapId());
            onArrival.run();
            return;
        }

        int targetMapId = route.get(stepIdx);

        if (bot.getMapId() == targetMapId) {
            // Already on this map, proceed to next step
            travelStep(entry, bot, route, stepIdx + 1, onArrival, 0);
            return;
        }

        if (attempts > 5) {
            log.warn("BotMapTraveler: {} failed to reach map {} after {} attempts, teleporting",
                    bot.getName(), targetMapId, attempts);
            forceTeleport(entry, bot, targetMapId);
            TimerManager.getInstance().schedule(() ->
                travelStep(entry, bot, route, stepIdx + 1, onArrival, 0), MAP_SETTLE_DELAY_MS);
            return;
        }

        // Find portal leading to target map
        Portal portal = findPortalToMap(bot, targetMapId);
        if (portal == null) {
            log.warn("BotMapTraveler: {} no portal found from {} to {}",
                    bot.getName(), bot.getMapId(), targetMapId);
            // Try teleporting directly
            forceTeleport(entry, bot, targetMapId);
            TimerManager.getInstance().schedule(() ->
                travelStep(entry, bot, route, stepIdx + 1, onArrival, 0), MAP_SETTLE_DELAY_MS);
            return;
        }

        // Navigate bot to portal position
        int portalX = portal.getPosition().x;
        int portalY = portal.getPosition().y;

        log.info("BotMapTraveler: {} walking to portal at ({},{}) → map {}",
                bot.getName(), portalX, portalY, targetMapId);

        // Set movement target to portal
        entry.moveTarget = new java.awt.Point(portalX, portalY);
        entry.moveTargetPrecise = true;
        schedulePortalCheck(entry, bot, portal, targetMapId, route, stepIdx, onArrival, attempts, 0);
    }

    private static void schedulePortalCheck(BotEntry entry, Character bot, Portal portal,
                                            int targetMapId, List<Integer> route, int stepIdx, Runnable onArrival,
                                            int attempts, int elapsed) {

        if (elapsed == 0) {
            TimerManager.getInstance().schedule(() ->
                    schedulePortalCheck(entry, bot, portal, targetMapId, route, stepIdx,
                            onArrival, attempts, 500), MAP_SETTLE_DELAY_MS);
            return;
        }
        if (elapsed >= PORTAL_TIMEOUT_MS) {
            // Timeout — force teleport
            log.warn("BotMapTraveler: {} portal timeout, force teleporting to {}", bot.getName(), targetMapId);
            forceTeleport(entry, bot, targetMapId);
            TimerManager.getInstance().schedule(() ->
                    travelStep(entry, bot, route, stepIdx + 1, onArrival, 0), MAP_SETTLE_DELAY_MS);
            return;
        }

        TimerManager.getInstance().schedule(() -> {
            if (bot.getMapId() != route.get(stepIdx > 0 ? stepIdx - 1 : 0)) {
                entry.moveTarget = null; // limpa target do portal anterior
                TimerManager.getInstance().schedule(() ->
                        travelStep(entry, bot, route, stepIdx + 1, onArrival, 0), MAP_SETTLE_DELAY_MS + 3000);
                return;
            }

            // Check if close enough to portal
            java.awt.Point botPos = bot.getPosition();
            java.awt.Point portalPos = portal.getPosition();
            int dist = Math.abs(botPos.x - portalPos.x) + Math.abs(botPos.y - portalPos.y);
            if (dist <= 60) {
                usePortal(entry, bot, portal, targetMapId);
                TimerManager.getInstance().schedule(() -> {
                    if (bot.getMapId() == targetMapId) {
                        travelStep(entry, bot, route, stepIdx + 1, onArrival, 0);
                    } else {
                        travelStep(entry, bot, route, stepIdx, onArrival, attempts + 1);
                    }
                }, MAP_SETTLE_DELAY_MS);
            } else {
                // Not there yet — keep polling
                schedulePortalCheck(entry, bot, portal, targetMapId, route, stepIdx,
                        onArrival, attempts, elapsed + 500);
            }
        }, 500);
    }

    private static Portal findPortalToMap(Character bot, int targetMapId) {
        MapleMap currentMap = bot.getMap();
        if (currentMap == null) return null;

        log.info("BotMapTraveler: {} looking for portal to {} in map {}, available portals: {}",
                bot.getName(), targetMapId, currentMap.getId(),
                currentMap.getPortals().stream()
                        .map(p -> p.getTargetMapId() + "(" + p.getName() + ")")
                        .collect(java.util.stream.Collectors.joining(", ")));

        for (Portal portal : currentMap.getPortals()) {
            if (portal.getTargetMapId() == targetMapId) {
                return portal;
            }
        }
        return null;
    }

    private static void usePortal(BotEntry entry, Character bot, Portal portal, int targetMapId) {
        try {
            portal.enterPortal(bot.getClient());
            BotMovementManager.resetEntryState(entry);
            entry.fhIndex = BotMovementManager.buildFhIndex(bot.getMap());
            entry.lastMapId = bot.getMapId();
            entry.moveTarget = null;
            log.info("BotMapTraveler: {} entered map {}", bot.getName(), bot.getMapId());
        } catch (Exception e) {
            log.warn("BotMapTraveler: portal use failed for {}", bot.getName(), e);
        }
    }

    private static void forceTeleport(BotEntry entry, Character bot, int targetMapId) {
        try {
            MapleMap targetMap = Server.getInstance()
                .getChannel(bot.getClient().getWorld(), bot.getClient().getChannel())
                .getMapFactory().getMap(targetMapId);
            if (targetMap == null) return;

            bot.forceChangeMap(targetMap, targetMap.getRandomPlayerSpawnpoint());
            BotMovementManager.resetEntryState(entry);
            entry.fhIndex = BotMovementManager.buildFhIndex(targetMap);
            entry.lastMapId = targetMapId;
            entry.moveTarget = null;
            entry.inAir = false;
        } catch (Exception e) {
            log.warn("BotMapTraveler: force teleport failed for {}", bot.getName(), e);
        }
    }
}
