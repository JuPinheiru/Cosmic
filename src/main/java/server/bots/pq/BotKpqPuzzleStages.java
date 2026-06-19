package server.bots.pq;

import client.BotClient;
import client.Character;
import net.server.world.Party;
import scripting.event.EventInstanceManager;
import server.bots.BotEntry;
import server.bots.BotScript;
import server.bots.BotScriptContext;
import server.bots.BotScriptStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * First-pass KPQ puzzle positioning. The human leader still talks to Cloto;
 * bots only stand on the known-good locations for stages 2-4.
 */
final class BotKpqPuzzleStages {
    private static final Logger log = LoggerFactory.getLogger(BotKpqPuzzleStages.class);

    private static final int STAGE_2_MAP = 103000801;
    private static final int STAGE_3_MAP = 103000802;
    private static final int STAGE_4_MAP = 103000803;

    private static final String SCRIPT_ID = "kpq-puzzle-stages";

    private static final String KEY_MAP = "kpqPuzzleMap";
    private static final String KEY_COMBO = "kpqPuzzleCombo";
    private static final String KEY_SLOT = "kpqPuzzleSlot";
    private static final String KEY_X = "kpqPuzzleX";
    private static final String KEY_Y = "kpqPuzzleY";
    private static final String KEY_ARRIVED = "kpqPuzzleArrived";

    private static final Stage STAGE_2 = new Stage(
            STAGE_2_MAP,
            "2stageclear",
            "stg2Property",
            // Centers of Cloto's rope rectangles. Keep isolated for manual tuning
            // if moveTo reaches the rope but the client needs a different Y.
            new Point[] {
                    new Point(-753, -23),
                    new Point(-719, -257),
                    new Point(-584, -251),
                    new Point(-481, -70)
            },
            new Rectangle[] {
                    new Rectangle(-755, -132, 4, 218),
                    new Rectangle(-721, -340, 4, 166),
                    new Rectangle(-586, -326, 4, 150),
                    new Rectangle(-483, -181, 4, 222)
            },
            new int[][] {
                    {0, 1, 1, 1},
                    {1, 0, 1, 1},
                    {1, 1, 0, 1},
                    {1, 1, 1, 0}
            });

    private static final Stage STAGE_3 = new Stage(
            STAGE_3_MAP,
            "3stageclear",
            "stg3Property",
            new Point[] {
                    new Point(678, -155),
                    new Point(861, -95),
                    new Point(1028, -155),
                    new Point(946, -216),
                    new Point(772, -216)
            },
            new Rectangle[] {
                    new Rectangle(608, -180, 140, 50),
                    new Rectangle(791, -117, 140, 45),
                    new Rectangle(958, -180, 140, 50),
                    new Rectangle(876, -238, 140, 45),
                    new Rectangle(702, -238, 140, 45)
            },
            new int[][] {
                    {0, 0, 1, 1, 1},
                    {0, 1, 0, 1, 1},
                    {0, 1, 1, 0, 1},
                    {0, 1, 1, 1, 0},
                    {1, 0, 0, 1, 1},
                    {1, 0, 1, 0, 1},
                    {1, 0, 1, 1, 0},
                    {1, 1, 0, 0, 1},
                    {1, 1, 0, 1, 0},
                    {1, 1, 1, 0, 0}
            });

    private static final Stage STAGE_4 = new Stage(
            STAGE_4_MAP,
            "4stageclear",
            "stg4Property",
            new Point[] {
                    new Point(927, -234),
                    new Point(894, -182),
                    new Point(963, -182),
                    new Point(862, -130),
                    new Point(927, -130),
                    new Point(998, -130)
            },
            new Rectangle[] {
                    new Rectangle(910, -236, 35, 5),
                    new Rectangle(877, -184, 35, 5),
                    new Rectangle(946, -184, 35, 5),
                    new Rectangle(845, -132, 35, 5),
                    new Rectangle(910, -132, 35, 5),
                    new Rectangle(981, -132, 35, 5)
            },
            new int[][] {
                    {0, 0, 0, 1, 1, 1},
                    {0, 0, 1, 0, 1, 1},
                    {0, 0, 1, 1, 0, 1},
                    {0, 0, 1, 1, 1, 0},
                    {0, 1, 0, 0, 1, 1},
                    {0, 1, 0, 1, 0, 1},
                    {0, 1, 0, 1, 1, 0},
                    {0, 1, 1, 0, 0, 1},
                    {0, 1, 1, 0, 1, 0},
                    {0, 1, 1, 1, 0, 0},
                    {1, 0, 0, 0, 1, 1},
                    {1, 0, 0, 1, 0, 1},
                    {1, 0, 0, 1, 1, 0},
                    {1, 0, 1, 0, 0, 1},
                    {1, 0, 1, 0, 1, 0},
                    {1, 0, 1, 1, 0, 0},
                    {1, 1, 0, 0, 0, 1},
                    {1, 1, 0, 0, 1, 0},
                    {1, 1, 0, 1, 0, 0},
                    {1, 1, 1, 0, 0, 0}
            });

    private static final BotScript SCRIPT = new BotScript() {
        private final List<BotScriptStep> steps = List.of(BotScriptStep.of(
                BotKpqPuzzleStages::tick,
                BotKpqPuzzleStages::tick,
                ctx -> false));

        @Override
        public String id() {
            return SCRIPT_ID;
        }

        @Override
        public boolean applies(BotEntry entry, Character bot, Character owner) {
            if (!(bot.getClient() instanceof BotClient)) {
                return false;
            }
            Stage stage = stageFor(bot.getMapId());
            if (stage == null) {
                return false;
            }
            EventInstanceManager eim = bot.getEventInstance();
            if (eim == null) {
                return false;
            }
            Character leader = getHumanPartyLeaderInSameEvent(bot, eim);
            if (leader == null) {
                return false;
            }
            if (isStageCleared(eim, stage.clearProperty)) {
                return isActive(entry);
            }
            if (shouldRelease(entry, bot, leader)) {
                return isActive(entry);
            }
            if (leader.getMapId() != bot.getMapId() || eim.getProperty(stage.comboProperty) == null) {
                return false;
            }

            int slot = botSlot(bot);
            return slot >= 0 && slot < 3;
        }

        @Override
        public List<BotScriptStep> steps() {
            return steps;
        }
    };

    private BotKpqPuzzleStages() {}

    static BotScript script() {
        return SCRIPT;
    }

    private static void tick(BotScriptContext ctx) {
        Stage stage = stageFor(ctx.bot.getMapId());
        if (stage == null) {
            return;
        }

        EventInstanceManager eim = ctx.bot.getEventInstance();
        if (eim == null) {
            return;
        }

        Character leader = getHumanPartyLeaderInSameEvent(ctx.bot, eim);
        if (isStageCleared(eim, stage.clearProperty)) {
            releaseToFollow(ctx, leader, stage, true);
            return;
        }
        if (leader != null && shouldRelease(ctx.entry, ctx.bot, leader)) {
            releaseToFollow(ctx, leader, stage, false);
            return;
        }

        Integer comboIndex = parseComboIndex(eim.getProperty(stage.comboProperty), stage);
        if (comboIndex == null) {
            return;
        }

        int slot = botSlot(ctx.bot);
        List<Point> targets = stage.targetsForCombo(comboIndex);
        List<Rectangle> targetAreas = stage.targetAreasForCombo(comboIndex);
        if (slot < 0 || slot >= targets.size() || slot >= targetAreas.size()) {
            return;
        }

        Point target = targets.get(slot);
        Rectangle targetArea = targetAreas.get(slot);
        boolean assignmentChanged = assignmentChanged(ctx, stage, comboIndex, slot, target);
        boolean insideTarget = contains(targetArea, ctx.bot.getPosition());

        if (assignmentChanged || (!insideTarget && ctx.tasksDone())) {
            rememberAssignment(ctx, stage, comboIndex, slot, target);
            log.info("KPQ stage {} bot {} read {}={} combo {} slot {} -> {}",
                    stage.number(), ctx.bot.getName(), stage.comboProperty, comboIndex,
                    stage.comboLabel(comboIndex), slot + 1, target);
            ctx.queueMoveTo(target, true);
            ctx.queueStop();
            return;
        }

        if (insideTarget && ctx.getInt(KEY_ARRIVED) == 0) {
            ctx.setInt(KEY_ARRIVED, 1);
            log.info("KPQ stage {} bot {} waiting at {} for leader validation",
                    stage.number(), ctx.bot.getName(), target);
        }
    }

    private static Integer parseComboIndex(String value, Stage stage) {
        try {
            int comboIndex = Integer.parseInt(value);
            return comboIndex >= 0 && comboIndex < stage.combos.length ? comboIndex : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Character getHumanPartyLeaderInSameEvent(Character bot, EventInstanceManager eim) {
        Party party = bot.getParty();
        if (party == null || party.getLeader() == null) {
            return null;
        }

        Character leader = party.getLeader().getPlayer();
        return leader != null
                && !(leader.getClient() instanceof BotClient)
                && leader.getEventInstance() == eim
                && bot.isPartyMember(leader)
                ? leader
                : null;
    }

    private static boolean isStageCleared(EventInstanceManager eim, String clearProperty) {
        String value = eim.getProperty(clearProperty);
        return value != null && !value.trim().isEmpty();
    }

    private static boolean shouldRelease(BotEntry entry, Character bot, Character leader) {
        return isActive(entry) && leader.getMapId() != bot.getMapId();
    }

    private static boolean isActive(BotEntry entry) {
        return entry != null && SCRIPT_ID.equals(entry.script.scriptId);
    }

    private static void releaseToFollow(BotScriptContext ctx, Character leader, Stage stage, boolean stageCleared) {
        if (stageCleared) {
            log.info("KPQ stage {} already cleared; releasing bot {} from puzzle script",
                    stage.number(), ctx.bot.getName());
        } else {
            log.info("leader advanced from KPQ stage {} to map {}, releasing bot {} puzzle state",
                    stage.number(), leader.getMapId(), ctx.bot.getName());
        }
        log.info("bot {} resumed follow after KPQ puzzle clear", ctx.bot.getName());
        ctx.manager.issueFollowOwner(ctx.entry);
        ctx.entry.script.reset(null);
    }

    private static int botSlot(Character bot) {
        Party party = bot.getParty();
        if (party == null) {
            return -1;
        }

        List<Character> bots = new ArrayList<>();
        for (Character member : bot.getPartyMembersOnline()) {
            if (member != null
                    && member.getClient() instanceof BotClient
                    && member.getMapId() == bot.getMapId()
                    && member.getEventInstance() == bot.getEventInstance()) {
                bots.add(member);
            }
        }
        bots.sort(Comparator.comparingInt(Character::getId));

        for (int i = 0; i < bots.size(); i++) {
            if (bots.get(i).getId() == bot.getId()) {
                return i;
            }
        }
        return -1;
    }

    private static boolean assignmentChanged(BotScriptContext ctx, Stage stage, int comboIndex, int slot, Point target) {
        return ctx.getInt(KEY_MAP) != stage.mapId
                || ctx.getInt(KEY_COMBO) != comboIndex
                || ctx.getInt(KEY_SLOT) != slot
                || ctx.getInt(KEY_X) != target.x
                || ctx.getInt(KEY_Y) != target.y;
    }

    private static void rememberAssignment(BotScriptContext ctx, Stage stage, int comboIndex, int slot, Point target) {
        ctx.setInt(KEY_MAP, stage.mapId);
        ctx.setInt(KEY_COMBO, comboIndex);
        ctx.setInt(KEY_SLOT, slot);
        ctx.setInt(KEY_X, target.x);
        ctx.setInt(KEY_Y, target.y);
        ctx.setInt(KEY_ARRIVED, 0);
    }

    private static Stage stageFor(int mapId) {
        return switch (mapId) {
            case STAGE_2_MAP -> STAGE_2;
            case STAGE_3_MAP -> STAGE_3;
            case STAGE_4_MAP -> STAGE_4;
            default -> null;
        };
    }

    private static boolean contains(Rectangle area, Point point) {
        return area != null && point != null && area.contains(point);
    }

    private record Stage(int mapId, String clearProperty, String comboProperty, Point[] positions,
                         Rectangle[] areas, int[][] combos) {
        int number() {
            return mapId - 103000800 + 1;
        }

        List<Point> targetsForCombo(int comboIndex) {
            List<Point> targets = new ArrayList<>(3);
            int[] combo = combos[comboIndex];
            for (int i = 0; i < combo.length; i++) {
                if (combo[i] == 1) {
                    targets.add(positions[i]);
                }
            }
            return targets;
        }

        List<Rectangle> targetAreasForCombo(int comboIndex) {
            List<Rectangle> targets = new ArrayList<>(3);
            int[] combo = combos[comboIndex];
            for (int i = 0; i < combo.length; i++) {
                if (combo[i] == 1) {
                    targets.add(areas[i]);
                }
            }
            return targets;
        }

        String comboLabel(int comboIndex) {
            StringBuilder label = new StringBuilder(3);
            int[] combo = combos[comboIndex];
            for (int i = 0; i < combo.length; i++) {
                if (combo[i] == 1) {
                    label.append(i + 1);
                }
            }
            return label.toString();
        }
    }
}
