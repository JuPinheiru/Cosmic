package server.bots;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import constants.game.GameConstants;
import constants.skills.Aran;
import constants.skills.Assassin;
import constants.skills.Bandit;
import constants.skills.Bishop;
import constants.skills.Bowmaster;
import constants.skills.Brawler;
import constants.skills.Buccaneer;
import constants.skills.ChiefBandit;
import constants.skills.Cleric;
import constants.skills.Corsair;
import constants.skills.Crossbowman;
import constants.skills.Crusader;
import constants.skills.DarkKnight;
import constants.skills.DragonKnight;
import constants.skills.FPArchMage;
import constants.skills.FPMage;
import constants.skills.FPWizard;
import constants.skills.Fighter;
import constants.skills.Gunslinger;
import constants.skills.Hermit;
import constants.skills.Hero;
import constants.skills.Hunter;
import constants.skills.ILArchMage;
import constants.skills.ILMage;
import constants.skills.ILWizard;
import constants.skills.Magician;
import constants.skills.Marauder;
import constants.skills.Marksman;
import constants.skills.NightLord;
import constants.skills.Outlaw;
import constants.skills.Page;
import constants.skills.Paladin;
import constants.skills.Priest;
import constants.skills.Ranger;
import constants.skills.Shadower;
import constants.skills.Sniper;
import constants.skills.Spearman;
import constants.skills.Warrior;
import constants.skills.WhiteKnight;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class BotBuildManager {

    /**
     * AP build for a warrior bot.
     * dexTarget = 0 means pure STR (no DEX investment beyond base).
     * dexTarget > 0 means fill DEX to that value first, then all STR.
     */
    static class ApBuild {
        final int dexTarget;
        ApBuild(int dexTarget) { this.dexTarget = dexTarget; }
    }

    /**
     * One step in a skill build order.
     * Spend SP on skillId until it reaches targetLevel, then advance to the next step.
     * Processing the full list top-to-bottom (skipping already-done steps) is backward-compatible:
     * a bot that missed many levels catches up by draining all available SP in sequence.
     */
    record BuildStep(int skillId, int targetLevel) {}

    private static BuildStep s(int id, int to) { return new BuildStep(id, to); }

    // ─── AP ───────────────────────────────────────────────────────────────────

    /** Stores the AP build, confirms it to the owner, and immediately spends any pending AP. */
    static void setApBuild(BotEntry entry, ApBuild build, String confirmMsg) {
        entry.apBuild      = build;
        entry.apPromptSent = false;
        BotManager.getInstance().botSay(entry.bot, confirmMsg);
        autoAssignAp(entry, entry.bot);
    }

    /**
     * Returns a prompt asking the owner to choose an AP build, or null if:
     * - no AP is pending, - build already chosen, - prompt already sent, or
     * - job is not a supported warrior branch.
     */
    static String buildApPrompt(BotEntry entry, Character bot) {
        Job job = bot.getJob();
        if (job != Job.WARRIOR && job != Job.FIGHTER && job != Job.PAGE && job != Job.SPEARMAN) return null;
        if (entry.apBuild != null || entry.apPromptSent || bot.getRemainingAp() < 1) return null;
        entry.apPromptSent = true;
        return "what AP build? type 'pure str' or e.g. '25 dex' to set a dex target";
    }

    /** Spends all remaining AP according to the stored build (STR primary, DEX up to target). */
    static void autoAssignAp(BotEntry entry, Character bot) {
        if (entry.apBuild == null || bot.getRemainingAp() < 1) return;
        int ap = bot.getRemainingAp();
        int strGain = 0, dexGain = 0;
        if (entry.apBuild.dexTarget > 0) {
            int dexNeeded = Math.max(0, entry.apBuild.dexTarget - bot.getDex());
            dexGain = Math.min(dexNeeded, ap);
            ap -= dexGain;
        }
        strGain = ap;
        if (strGain > 0 || dexGain > 0) {
            bot.assignStrDexIntLuk(strGain, dexGain, 0, 0);
        }
    }

    // ─── SP ───────────────────────────────────────────────────────────────────

    /**
     * Returns a prompt asking for the SP build variant, or null if not needed.
     * Currently only Hero has two documented builds (1h sword vs 2h).
     * Sets spVariantPromptSent so Hero SP is held until the owner responds.
     */
    static String buildSpVariantPrompt(BotEntry entry, Character bot) {
        if (bot.getJob() != Job.HERO) return null;
        if (entry.spVariant != null || entry.spVariantPromptSent || bot.getRemainingSps()[3] < 1) return null;
        entry.spVariantPromptSent = true;
        return "hero build: '1h' (1h sword, Brandish first) or '2h' (interleave AC + Brandish for faster charges)?";
    }

    /**
     * Spends all available SP following the per-level build order for the bot's current job.
     * Processes steps top-to-bottom; skips steps already at or past their target level.
     * This is naturally backward-compatible: if the bot accumulated SP over many levels,
     * all of it is drained in the correct sequence.
     *
     * For Hero specifically, SP is held until the owner chooses "1h" or "2h".
     */
    static void autoAssignSp(BotEntry entry, Character bot) {
        // Hold Hero SP until owner chooses a variant, regardless of when the prompt was sent.
        if (bot.getJob() == Job.HERO && entry.spVariant == null) return;

        List<BuildStep> steps = getBuildOrder(bot.getJob(), entry.spVariant);
        if (steps == null) return;

        autoAssignSp(bot, steps);
    }

    static String respecSp(BotEntry entry, Character bot) {
        if (bot.getJob() == Job.HERO && entry.spVariant == null) {
            return "need your hero build first. say '1h' or '2h'";
        }

        List<Job> buildPath = getSupportedBuildPath(bot.getJob());
        if (buildPath == null) {
            return "dont have an sp respec build for my job yet";
        }

        int[] refundedSp = new int[5];
        List<Skill> skillsToReset = new ArrayList<>();
        for (Map.Entry<Skill, Character.SkillEntry> learned : bot.getSkills().entrySet()) {
            Skill skill = learned.getKey();
            Character.SkillEntry skillEntry = learned.getValue();
            if (skill == null || skillEntry == null || skillEntry.skillevel <= 0) {
                continue;
            }

            int skillId = skill.getId();
            if (skill.isBeginnerSkill() || GameConstants.isHiddenSkills(skillId)) {
                continue;
            }
            if (!GameConstants.isInJobTree(skillId, bot.getJob().getId())) {
                continue;
            }

            refundedSp[GameConstants.getSkillBook(skillId / 10000)] += skillEntry.skillevel;
            skillsToReset.add(skill);
        }

        for (Skill skill : skillsToReset) {
            bot.changeSkillLevel(skill, (byte) 0, bot.getMasterLevel(skill), bot.getSkillExpiration(skill));
        }
        for (int book = 0; book < refundedSp.length; book++) {
            if (refundedSp[book] > 0) {
                bot.gainSp(refundedSp[book], book, false);
            }
        }

        for (Job job : buildPath) {
            List<BuildStep> steps = getBuildOrder(job, entry.spVariant);
            if (steps != null) {
                autoAssignSp(bot, steps);
            }
        }

        return "ok, rebuilt my sp using the bot build";
    }

    private static void autoAssignSp(Character bot, List<BuildStep> steps) {
        for (BuildStep step : steps) {
            Skill skill = SkillFactory.getSkill(step.skillId());
            if (skill == null) continue;
            int book = GameConstants.getSkillBook(step.skillId() / 10000);
            if (bot.getRemainingSps()[book] < 1) continue;
            while (bot.getRemainingSps()[book] > 0) {
                int lv = bot.getSkillLevel(skill);
                if (lv >= step.targetLevel()) break;
                bot.gainSp(-1, book, false);
                bot.changeSkillLevel(skill, (byte) (lv + 1),
                        bot.getMasterLevel(skill), bot.getSkillExpiration(skill));
            }
        }
    }

    private static List<Job> getSupportedBuildPath(Job job) {
        return switch (job) {
            // Warrior
            case WARRIOR      -> List.of(Job.WARRIOR);
            case FIGHTER      -> List.of(Job.WARRIOR, Job.FIGHTER);
            case CRUSADER     -> List.of(Job.WARRIOR, Job.FIGHTER, Job.CRUSADER);
            case HERO         -> List.of(Job.WARRIOR, Job.FIGHTER, Job.CRUSADER, Job.HERO);
            case PAGE         -> List.of(Job.WARRIOR, Job.PAGE);
            case WHITEKNIGHT  -> List.of(Job.WARRIOR, Job.PAGE, Job.WHITEKNIGHT);
            case PALADIN      -> List.of(Job.WARRIOR, Job.PAGE, Job.WHITEKNIGHT, Job.PALADIN);
            case SPEARMAN     -> List.of(Job.WARRIOR, Job.SPEARMAN);
            case DRAGONKNIGHT -> List.of(Job.WARRIOR, Job.SPEARMAN, Job.DRAGONKNIGHT);
            case DARKKNIGHT   -> List.of(Job.WARRIOR, Job.SPEARMAN, Job.DRAGONKNIGHT, Job.DARKKNIGHT);
            // Magician
            case MAGICIAN     -> List.of(Job.MAGICIAN);
            case FP_WIZARD    -> List.of(Job.MAGICIAN, Job.FP_WIZARD);
            case FP_MAGE      -> List.of(Job.MAGICIAN, Job.FP_WIZARD, Job.FP_MAGE);
            case FP_ARCHMAGE  -> List.of(Job.MAGICIAN, Job.FP_WIZARD, Job.FP_MAGE, Job.FP_ARCHMAGE);
            case IL_WIZARD    -> List.of(Job.MAGICIAN, Job.IL_WIZARD);
            case IL_MAGE      -> List.of(Job.MAGICIAN, Job.IL_WIZARD, Job.IL_MAGE);
            case IL_ARCHMAGE  -> List.of(Job.MAGICIAN, Job.IL_WIZARD, Job.IL_MAGE, Job.IL_ARCHMAGE);
            case CLERIC       -> List.of(Job.MAGICIAN, Job.CLERIC);
            case PRIEST       -> List.of(Job.MAGICIAN, Job.CLERIC, Job.PRIEST);
            case BISHOP       -> List.of(Job.MAGICIAN, Job.CLERIC, Job.PRIEST, Job.BISHOP);
            // Bowman
            case BOWMAN       -> List.of(Job.BOWMAN);
            case HUNTER       -> List.of(Job.BOWMAN, Job.HUNTER);
            case RANGER       -> List.of(Job.BOWMAN, Job.HUNTER, Job.RANGER);
            case BOWMASTER    -> List.of(Job.BOWMAN, Job.HUNTER, Job.RANGER, Job.BOWMASTER);
            case CROSSBOWMAN  -> List.of(Job.BOWMAN, Job.CROSSBOWMAN);
            case SNIPER       -> List.of(Job.BOWMAN, Job.CROSSBOWMAN, Job.SNIPER);
            case MARKSMAN     -> List.of(Job.BOWMAN, Job.CROSSBOWMAN, Job.SNIPER, Job.MARKSMAN);
            // Thief
            case THIEF        -> List.of(Job.THIEF);
            case ASSASSIN     -> List.of(Job.THIEF, Job.ASSASSIN);
            case HERMIT       -> List.of(Job.THIEF, Job.ASSASSIN, Job.HERMIT);
            case NIGHTLORD    -> List.of(Job.THIEF, Job.ASSASSIN, Job.HERMIT, Job.NIGHTLORD);
            case BANDIT       -> List.of(Job.THIEF, Job.BANDIT);
            case CHIEFBANDIT  -> List.of(Job.THIEF, Job.BANDIT, Job.CHIEFBANDIT);
            case SHADOWER     -> List.of(Job.THIEF, Job.BANDIT, Job.CHIEFBANDIT, Job.SHADOWER);
            // Pirate
            case PIRATE       -> List.of(Job.PIRATE);
            case BRAWLER      -> List.of(Job.PIRATE, Job.BRAWLER);
            case MARAUDER     -> List.of(Job.PIRATE, Job.BRAWLER, Job.MARAUDER);
            case BUCCANEER    -> List.of(Job.PIRATE, Job.BRAWLER, Job.MARAUDER, Job.BUCCANEER);
            case GUNSLINGER   -> List.of(Job.PIRATE, Job.GUNSLINGER);
            case OUTLAW       -> List.of(Job.PIRATE, Job.GUNSLINGER, Job.OUTLAW);
            case CORSAIR      -> List.of(Job.PIRATE, Job.GUNSLINGER, Job.OUTLAW, Job.CORSAIR);
            // Aran
            case ARAN1        -> List.of(Job.ARAN1);
            case ARAN2        -> List.of(Job.ARAN1, Job.ARAN2);
            case ARAN3        -> List.of(Job.ARAN1, Job.ARAN2, Job.ARAN3);
            case ARAN4        -> List.of(Job.ARAN1, Job.ARAN2, Job.ARAN3, Job.ARAN4);
            default           -> null;
        };
    }

    // ─── Build orders ─────────────────────────────────────────────────────────

    private static List<BuildStep> getBuildOrder(Job job, String variant) {
        return switch (job) {
            // Warrior
            case WARRIOR      -> warriorBuild();
            case FIGHTER      -> fighterBuild();
            case CRUSADER     -> crusaderBuild();
            case HERO         -> "2h".equals(variant) ? hero2hBuild() : hero1hBuild();
            case PAGE         -> pageBuild();
            case WHITEKNIGHT  -> whiteKnightBuild();
            case PALADIN      -> paladinBuild();
            case SPEARMAN     -> spearmanBuild();
            case DRAGONKNIGHT -> dragonKnightBuild();
            case DARKKNIGHT   -> darkKnightBuild();
            // Magician
            case MAGICIAN     -> magicianBuild();
            case FP_WIZARD    -> fpWizardBuild();
            case FP_MAGE      -> fpMageBuild();
            case FP_ARCHMAGE  -> fpArchMageBuild();
            case IL_WIZARD    -> ilWizardBuild();
            case IL_MAGE      -> ilMageBuild();
            case IL_ARCHMAGE  -> ilArchMageBuild();
            case CLERIC       -> clericBuild();
            case PRIEST       -> priestBuild();
            case BISHOP       -> bishopBuild();
            // Bowman
            case BOWMAN       -> bowmanBuild();
            case HUNTER       -> hunterBuild();
            case RANGER       -> rangerBuild();
            case BOWMASTER    -> bowmasterBuild();
            case CROSSBOWMAN  -> crossbowmanBuild();
            case SNIPER       -> sniperBuild();
            case MARKSMAN     -> marksmanBuild();
            // Thief
            case ASSASSIN     -> assassinBuild();
            case HERMIT       -> hermitBuild();
            case NIGHTLORD    -> nightLordBuild();
            case BANDIT       -> banditBuild();
            case CHIEFBANDIT  -> chiefBanditBuild();
            case SHADOWER     -> shadowerBuild();
            // Pirate
            case BRAWLER      -> brawlerBuild();
            case MARAUDER     -> marauderBuild();
            case BUCCANEER    -> buccaneerBuild();
            case GUNSLINGER   -> gunslingerBuild();
            case OUTLAW       -> outlawBuild();
            case CORSAIR      -> corsairBuild();
            // Aran
            case ARAN1        -> aran1Build();
            case ARAN2        -> aran2Build();
            case ARAN3        -> aran3Build();
            case ARAN4        -> aran4Build();
            default           -> null;
        };
    }

    // =========================================================================
    // WARRIOR BRANCH
    // =========================================================================

    /**
     * Warrior (lv10–30).
     * HP Recovery → MaxHP% → 1pt Power Strike → Slash Blast (AoE, max) → Power Strike (max) → fill HP Recovery.
     */
    private static List<BuildStep> warriorBuild() {
        return List.of(
                s(Warrior.IMPROVED_HPREC, 5),
                s(Warrior.IMPROVED_MAXHP, 10),
                s(Warrior.POWER_STRIKE, 1),
                s(Warrior.SLASH_BLAST, 20),
                s(Warrior.POWER_STRIKE, 20),
                s(Warrior.IMPROVED_HPREC, 16)
        );
    }

    /**
     * Fighter (lv30–70) — sword path.
     * Mastery → early Booster → 1pt Rage → Power Guard (max) → Rage (max) → finish Booster/Mastery.
     * Final Attack skipped.
     */
    private static List<BuildStep> fighterBuild() {
        return List.of(
                s(Fighter.SWORD_MASTERY, 19),
                s(Fighter.SWORD_BOOSTER, 6),
                s(Fighter.RAGE, 3),
                s(Fighter.POWER_GUARD, 30),
                s(Fighter.RAGE, 30),
                s(Fighter.SWORD_BOOSTER, 20),
                s(Fighter.SWORD_MASTERY, 20)
        );
    }

    /**
     * Crusader (lv70–120) — sword path.
     * Combo → Coma → Panic → Armor Crash → fillers.
     */
    private static List<BuildStep> crusaderBuild() {
        return List.of(
                s(Crusader.COMBO, 30),
                s(Crusader.SWORD_COMA, 30),
                s(Crusader.SWORD_PANIC, 30),
                s(Crusader.ARMOR_CRASH, 20),
                s(Crusader.SHOUT, 20),
                s(Crusader.SHIELD_MASTERY, 20),
                s(Crusader.IMPROVING_MPREC, 20)
        );
    }

    /**
     * Hero 1h sword + shield (lv120–200).
     * Rush(1) → Brandish(max) → AC(max) → Stance(max) → MW → Will → Achilles → Guardian → Enrage → Rush(max) → MW(max).
     */
    private static List<BuildStep> hero1hBuild() {
        return List.of(
                s(Hero.RUSH, 1),
                s(Hero.BRANDISH, 30),
                s(Hero.ADVANCED_COMBO, 30),
                s(Hero.STANCE, 30),
                s(Hero.MAPLE_WARRIOR, 13),
                s(Hero.HEROS_WILL, 1),
                s(Hero.MAPLE_WARRIOR, 19),
                s(Hero.ACHILLES, 30),
                s(Hero.HEROS_WILL, 5),
                s(Hero.GUARDIAN, 30),
                s(Hero.ENRAGE, 30),
                s(Hero.RUSH, 30),
                s(Hero.MAPLE_WARRIOR, 30)
        );
    }

    /**
     * Hero 2h (lv120–200).
     * Interleaves AC early for faster charges.
     */
    private static List<BuildStep> hero2hBuild() {
        return List.of(
                s(Hero.RUSH, 1),
                s(Hero.BRANDISH, 1),
                s(Hero.ADVANCED_COMBO, 1),
                s(Hero.BRANDISH, 21),
                s(Hero.ADVANCED_COMBO, 30),
                s(Hero.BRANDISH, 30),
                s(Hero.STANCE, 30),
                s(Hero.MAPLE_WARRIOR, 13),
                s(Hero.HEROS_WILL, 1),
                s(Hero.MAPLE_WARRIOR, 19),
                s(Hero.ACHILLES, 30),
                s(Hero.HEROS_WILL, 5),
                s(Hero.GUARDIAN, 30),
                s(Hero.ENRAGE, 30),
                s(Hero.RUSH, 30),
                s(Hero.MAPLE_WARRIOR, 30)
        );
    }

    /**
     * Page (lv30–70) — sword path.
     * Mastery → Threaten → Power Guard → Booster.
     */
    private static List<BuildStep> pageBuild() {
        return List.of(
                s(Page.SWORD_MASTERY, 20),
                s(Page.THREATEN, 20),
                s(Page.POWER_GUARD, 30),
                s(Page.SWORD_BOOSTER, 20)
        );
    }

    /**
     * White Knight (lv70–120) — sword path.
     * Charge Blow → Lightning Charge → Magic Crash → Shield Mastery → MP Recovery.
     */
    private static List<BuildStep> whiteKnightBuild() {
        return List.of(
                s(WhiteKnight.CHARGE_BLOW, 30),
                s(WhiteKnight.SWORD_LIT_CHARGE, 30),
                s(WhiteKnight.MAGIC_CRASH, 20),
                s(WhiteKnight.SHIELD_MASTERY, 20),
                s(WhiteKnight.IMPROVING_MP_RECOVERY, 20)
        );
    }

    /**
     * Paladin (lv120–200).
     * Rush(1) → Heaven's Hammer(max) → Sanctuary(max) → Stance(max) → MW → Will → Achilles → Divine Shield → Rush(max) → MW(max).
     */
    private static List<BuildStep> paladinBuild() {
        return List.of(
                s(Paladin.RUSH, 1),
                s(Paladin.HEAVENS_HAMMER, 30),
                s(Paladin.BLAST, 30),
                s(Paladin.STANCE, 30),
                s(Paladin.MAPLE_WARRIOR, 13),
                s(Paladin.HEROS_WILL, 1),
                s(Paladin.MAPLE_WARRIOR, 19),
                s(Paladin.ACHILLES, 30),
                s(Paladin.HEROS_WILL, 5),
                s(Paladin.GUARDIAN, 30),
                s(Paladin.RUSH, 30),
                s(Paladin.MAPLE_WARRIOR, 30)
        );
    }

    /**
     * Spearman (lv30–70) — spear path.
     * Hyper Body first (best party skill) → Mastery → Iron Will → Booster.
     */
    private static List<BuildStep> spearmanBuild() {
        return List.of(
                s(Spearman.HYPER_BODY, 30),
                s(Spearman.SPEAR_MASTERY, 20),
                s(Spearman.IRON_WILL, 20),
                s(Spearman.SPEAR_BOOSTER, 20)
        );
    }

    /**
     * Dragon Knight (lv70–120) — spear path.
     * Dragon Roar (AoE) → Dragon Blood → Crusher → Dragon Fury → fillers.
     */
    private static List<BuildStep> dragonKnightBuild() {
        return List.of(
                s(DragonKnight.DRAGON_ROAR, 30),
                s(DragonKnight.DRAGON_BLOOD, 30),
                s(DragonKnight.SPEAR_CRUSHER, 30),
                s(DragonKnight.SPEAR_DRAGON_FURY, 30),
                s(DragonKnight.SACRIFICE, 20),
                s(DragonKnight.POWER_CRASH, 20),
                s(DragonKnight.ELEMENTAL_RESISTANCE, 20)
        );
    }

    /**
     * Dark Knight (lv120–200).
     * Rush(1) → Beholder(max) → Beholder's Buff(max) → Stance(max) → MW → Will → Achilles → Hex → Berserk → Rush(max) → MW(max).
     */
    private static List<BuildStep> darkKnightBuild() {
        return List.of(
                s(DarkKnight.RUSH, 1),
                s(DarkKnight.BEHOLDER, 30),
                s(DarkKnight.AURA_OF_BEHOLDER, 20),
                s(DarkKnight.STANCE, 30),
                s(DarkKnight.MAPLE_WARRIOR, 13),
                s(DarkKnight.HEROS_WILL, 1),
                s(DarkKnight.MAPLE_WARRIOR, 19),
                s(DarkKnight.ACHILLES, 30),
                s(DarkKnight.HEROS_WILL, 5),
                s(DarkKnight.HEX_OF_BEHOLDER, 20),
                s(DarkKnight.BERSERK, 30),
                s(DarkKnight.RUSH, 30),
                s(DarkKnight.MAPLE_WARRIOR, 30)
        );
    }

    // =========================================================================
    // MAGICIAN BRANCH
    // =========================================================================

    /**
     * Magician (lv8–30).
     * MP Recovery → Magic Guard (max) → Magic Armor → Max MP Increase → fillers.
     */
    private static List<BuildStep> magicianBuild() {
        return List.of(
                s(Magician.IMPROVED_MP_RECOVERY, 5),
                s(Magician.MAGIC_GUARD, 20),
                s(Magician.MAGIC_ARMOR, 20),
                s(Magician.IMPROVED_MAX_MP_INCREASE, 10),
                s(Magician.IMPROVED_MP_RECOVERY, 16)
        );
    }

    /**
     * F/P Wizard (lv30–70).
     * MP Eater → Meditation → Slow → Fire Arrow(max) → Poison Breath → Teleport.
     */
    private static List<BuildStep> fpWizardBuild() {
        return List.of(
                s(FPWizard.MP_EATER, 20),
                s(FPWizard.MEDITATION, 20),
                s(FPWizard.SLOW, 20),
                s(FPWizard.FIRE_ARROW, 30),
                s(FPWizard.POISON_BREATH, 30),
                s(FPWizard.TELEPORT, 20)
        );
    }

    /**
     * F/P Mage (lv70–120).
     * Partial Resistance → Spell Booster → Element Composition → Explosion(max) → Poison Mist(max) → Seal → Element Amplification.
     */
    private static List<BuildStep> fpMageBuild() {
        return List.of(
                s(FPMage.PARTIAL_RESISTANCE, 20),
                s(FPMage.SPELL_BOOSTER, 20),
                s(FPMage.ELEMENT_COMPOSITION, 30),
                s(FPMage.EXPLOSION, 30),
                s(FPMage.POISON_MIST, 30),
                s(FPMage.SEAL, 20),
                s(FPMage.ELEMENT_AMPLIFICATION, 20)
        );
    }

    /**
     * F/P Arch Mage (lv120–200).
     * Big Bang(max) → Paralyze(max) → Meteor(max) → Infinity → Mana Reflection → MW → Will → Fire Demon → Elquines → MW(max).
     */
    private static List<BuildStep> fpArchMageBuild() {
        return List.of(
                s(FPArchMage.BIG_BANG, 30),
                s(FPArchMage.PARALYZE, 30),
                s(FPArchMage.METEOR_SHOWER, 30),
                s(FPArchMage.INFINITY, 30),
                s(FPArchMage.MANA_REFLECTION, 30),
                s(FPArchMage.MAPLE_WARRIOR, 13),
                s(FPArchMage.HEROS_WILL, 1),
                s(FPArchMage.MAPLE_WARRIOR, 19),
                s(FPArchMage.FIRE_DEMON, 30),
                s(FPArchMage.ELQUINES, 30),
                s(FPArchMage.HEROS_WILL, 5),
                s(FPArchMage.MAPLE_WARRIOR, 30)
        );
    }

    /**
     * I/L Wizard (lv30–70).
     * MP Eater → Meditation → Slow → Cold Beam(max) → Thunderbolt(max) → Teleport.
     */
    private static List<BuildStep> ilWizardBuild() {
        return List.of(
                s(ILWizard.MP_EATER, 20),
                s(ILWizard.MEDITATION, 20),
                s(ILWizard.SLOW, 20),
                s(ILWizard.COLD_BEAM, 30),
                s(ILWizard.THUNDERBOLT, 30),
                s(ILWizard.TELEPORT, 20)
        );
    }

    /**
     * I/L Mage (lv70–120).
     * Partial Resistance → Spell Booster → Element Composition → Ice Strike(max) → Thunder Spear(max) → Seal → Element Amplification.
     */
    private static List<BuildStep> ilMageBuild() {
        return List.of(
                s(ILMage.PARTIAL_RESISTANCE, 20),
                s(ILMage.SPELL_BOOSTER, 20),
                s(ILMage.ELEMENT_COMPOSITION, 30),
                s(ILMage.ICE_STRIKE, 30),
                s(ILMage.THUNDER_SPEAR, 30),
                s(ILMage.SEAL, 20),
                s(ILMage.ELEMENT_AMPLIFICATION, 20)
        );
    }

    /**
     * I/L Arch Mage (lv120–200).
     * Big Bang(max) → Chain Lightning(max) → Blizzard(max) → Infinity → Mana Reflection → MW → Will → Ice Demon → Ifrit → MW(max).
     */
    private static List<BuildStep> ilArchMageBuild() {
        return List.of(
                s(ILArchMage.BIG_BANG, 30),
                s(ILArchMage.CHAIN_LIGHTNING, 30),
                s(ILArchMage.BLIZZARD, 30),
                s(ILArchMage.INFINITY, 30),
                s(ILArchMage.MANA_REFLECTION, 30),
                s(ILArchMage.MAPLE_WARRIOR, 13),
                s(ILArchMage.HEROS_WILL, 1),
                s(ILArchMage.MAPLE_WARRIOR, 19),
                s(ILArchMage.ICE_DEMON, 30),
                s(ILArchMage.IFRIT, 30),
                s(ILArchMage.HEROS_WILL, 5),
                s(ILArchMage.MAPLE_WARRIOR, 30)
        );
    }

    /**
     * Cleric (lv30–70).
     * Heal(max) → Bless(max) → Invincible(max) → Holy Arrow(max) → Teleport.
     */
    private static List<BuildStep> clericBuild() {
        return List.of(
                s(Cleric.HEAL, 30),
                s(Cleric.BLESS, 20),
                s(Cleric.INVINCIBLE, 20),
                s(Cleric.HOLY_ARROW, 30),
                s(Cleric.TELEPORT, 20)
        );
    }

    /**
     * Priest (lv70–120).
     * Holy Symbol(max) → Dispel → Mystic Door → Shining Ray(max) → Doom → Summon Dragon → Elemental Resistance.
     */
    private static List<BuildStep> priestBuild() {
        return List.of(
                s(Priest.HOLY_SYMBOL, 30),
                s(Priest.DISPEL, 20),
                s(Priest.MYSTIC_DOOR, 20),
                s(Priest.SHINING_RAY, 30),
                s(Priest.DOOM, 20),
                s(Priest.SUMMON_DRAGON, 30),
                s(Priest.ELEMENTAL_RESISTANCE, 20)
        );
    }

    /**
     * Bishop (lv120–200).
     * Big Bang(max) → Genesis(max) → Bahamut(max) → Infinity → Mana Reflection → Holy Shield → Resurrection → MW → Will → Angel Ray → MW(max).
     */
    private static List<BuildStep> bishopBuild() {
        return List.of(
                s(Bishop.BIG_BANG, 30),
                s(Bishop.GENESIS, 30),
                s(Bishop.BAHAMUT, 30),
                s(Bishop.INFINITY, 30),
                s(Bishop.MANA_REFLECTION, 30),
                s(Bishop.HOLY_SHIELD, 30),
                s(Bishop.RESURRECTION, 30),
                s(Bishop.MAPLE_WARRIOR, 13),
                s(Bishop.HEROS_WILL, 1),
                s(Bishop.MAPLE_WARRIOR, 19),
                s(Bishop.ANGEL_RAY, 30),
                s(Bishop.HEROS_WILL, 5),
                s(Bishop.MAPLE_WARRIOR, 30)
        );
    }

    // =========================================================================
    // BOWMAN BRANCH
    // =========================================================================

    /**
     * Bowman (lv10–30).
     * Critical Shot → Focus → Arrow Blow(max) → Double Shot(max).
     */
    private static List<BuildStep> bowmanBuild() {
        return List.of(
                s(constants.skills.Archer.CRITICAL_SHOT, 20),
                s(constants.skills.Archer.FOCUS, 20),
                s(constants.skills.Archer.ARROW_BLOW, 20),
                s(constants.skills.Archer.DOUBLE_SHOT, 20)
        );
    }

    /**
     * Hunter (lv30–70) — bow path.
     * Mastery → Soul Arrow → Booster → Arrow Bomb(max) → Power Knockback.
     * Final Attack skipped.
     */
    private static List<BuildStep> hunterBuild() {
        return List.of(
                s(Hunter.BOW_MASTERY, 20),
                s(Hunter.SOUL_ARROW, 20),
                s(Hunter.BOW_BOOSTER, 20),
                s(Hunter.ARROW_BOMB, 30),
                s(Hunter.POWER_KNOCKBACK, 20)
        );
    }

    /**
     * Ranger (lv70–120).
     * Strafe(max) → Silver Hawk(max) → Arrow Rain(max) → Puppet → Inferno → Mortal Blow → Thrust.
     */
    private static List<BuildStep> rangerBuild() {
        return List.of(
                s(Ranger.STRAFE, 30),
                s(Ranger.SILVER_HAWK, 30),
                s(Ranger.ARROW_RAIN, 30),
                s(Ranger.PUPPET, 20),
                s(Ranger.INFERNO, 30),
                s(Ranger.MORTAL_BLOW, 20),
                s(Ranger.THRUST, 20)
        );
    }

    /**
     * Bowmaster (lv120–200).
     * Hurricane(max) → Sharp Eyes(max) → Phoenix(max) → Bow Expert → Hamstring → Concentrate → Dragon's Breath → MW → Will → MW(max).
     */
    private static List<BuildStep> bowmasterBuild() {
        return List.of(
                s(Bowmaster.HURRICANE, 30),
                s(Bowmaster.SHARP_EYES, 30),
                s(Bowmaster.PHOENIX, 30),
                s(Bowmaster.BOW_EXPERT, 30),
                s(Bowmaster.HAMSTRING, 30),
                s(Bowmaster.CONCENTRATE, 30),
                s(Bowmaster.MAPLE_WARRIOR, 13),
                s(Bowmaster.HEROS_WILL, 1),
                s(Bowmaster.MAPLE_WARRIOR, 19),
                s(Bowmaster.DRAGONS_BREATH, 30),
                s(Bowmaster.HEROS_WILL, 5),
                s(Bowmaster.MAPLE_WARRIOR, 30)
        );
    }

    /**
     * Crossbowman (lv30–70).
     * Mastery → Soul Arrow → Booster → Iron Arrow(max) → Power Knockback.
     * Final Attack skipped.
     */
    private static List<BuildStep> crossbowmanBuild() {
        return List.of(
                s(Crossbowman.CROSSBOW_MASTERY, 20),
                s(Crossbowman.SOUL_ARROW, 20),
                s(Crossbowman.CROSSBOW_BOOSTER, 20),
                s(Crossbowman.IRON_ARROW, 30),
                s(Crossbowman.POWER_KNOCKBACK, 20)
        );
    }

    /**
     * Sniper (lv70–120).
     * Strafe(max) → Golden Eagle(max) → Arrow Eruption(max) → Puppet → Blizzard → Mortal Blow → Thrust.
     */
    private static List<BuildStep> sniperBuild() {
        return List.of(
                s(Sniper.STRAFE, 30),
                s(Sniper.GOLDEN_EAGLE, 30),
                s(Sniper.ARROW_ERUPTION, 30),
                s(Sniper.PUPPET, 20),
                s(Sniper.BLIZZARD, 30),
                s(Sniper.MORTAL_BLOW, 20),
                s(Sniper.THRUST, 20)
        );
    }

    /**
     * Marksman (lv120–200).
     * Snipe(max) → Sharp Eyes(max) → Frost Prey(max) → Marksman Boost → Piercing Arrow → Blind → Dragon's Breath → MW → Will → MW(max).
     */
    private static List<BuildStep> marksmanBuild() {
        return List.of(
                s(Marksman.SNIPE, 30),
                s(Marksman.SHARP_EYES, 30),
                s(Marksman.FROST_PREY, 30),
                s(Marksman.MARKSMAN_BOOST, 30),
                s(Marksman.PIERCING_ARROW, 30),
                s(Marksman.BLIND, 20),
                s(Marksman.MAPLE_WARRIOR, 13),
                s(Marksman.HEROS_WILL, 1),
                s(Marksman.MAPLE_WARRIOR, 19),
                s(Marksman.DRAGONS_BREATH, 30),
                s(Marksman.HEROS_WILL, 5),
                s(Marksman.MAPLE_WARRIOR, 30)
        );
    }

    // =========================================================================
    // THIEF BRANCH
    // =========================================================================

    /**
     * Assassin (lv30–70) — claw path.
     * Claw Mastery → Critical Throw → Haste → Drain → Booster → Endure.
     */
    private static List<BuildStep> assassinBuild() {
        return List.of(
                s(Assassin.CLAW_MASTERY, 20),
                s(Assassin.CRITICAL_THROW, 20),
                s(Assassin.HASTE, 20),
                s(Assassin.DRAIN, 30),
                s(Assassin.CLAW_BOOSTER, 20),
                s(Assassin.ENDURE, 20)
        );
    }

    /**
     * Hermit (lv70–120).
     * Shadow Partner(max) → Avenger(max) → Shadow Meso(max) → Flash Jump → Meso Up → Shadow Web → Alchemist.
     */
    private static List<BuildStep> hermitBuild() {
        return List.of(
                s(Hermit.SHADOW_PARTNER, 30),
                s(Hermit.AVENGER, 30),
                s(Hermit.SHADOW_MESO, 30),
                s(Hermit.FLASH_JUMP, 20),
                s(Hermit.MESO_UP, 20),
                s(Hermit.SHADOW_WEB, 20),
                s(Hermit.ALCHEMIST, 20)
        );
    }

    /**
     * Night Lord (lv120–200).
     * Triple Throw(max) → Shadow Stars(max) → Venomous Star(max) → Shadow Shifter → Taunt → Ninja Ambush → Ninja Storm → MW → Will → MW(max).
     */
    private static List<BuildStep> nightLordBuild() {
        return List.of(
                s(NightLord.TRIPLE_THROW, 30),
                s(NightLord.SHADOW_STARS, 30),
                s(NightLord.VENOMOUS_STAR, 30),
                s(NightLord.SHADOW_SHIFTER, 30),
                s(NightLord.TAUNT, 20),
                s(NightLord.NINJA_AMBUSH, 30),
                s(NightLord.MAPLE_WARRIOR, 13),
                s(NightLord.HEROS_WILL, 1),
                s(NightLord.MAPLE_WARRIOR, 19),
                s(NightLord.NINJA_STORM, 30),
                s(NightLord.HEROS_WILL, 5),
                s(NightLord.MAPLE_WARRIOR, 30)
        );
    }

    /**
     * Bandit (lv30–70) — dagger path.
     * Dagger Mastery → Haste → Savage Blow(max) → Steal → Booster → Endure.
     */
    private static List<BuildStep> banditBuild() {
        return List.of(
                s(Bandit.DAGGER_MASTERY, 20),
                s(Bandit.HASTE, 20),
                s(Bandit.SAVAGE_BLOW, 30),
                s(Bandit.STEAL, 20),
                s(Bandit.DAGGER_BOOSTER, 20),
                s(Bandit.ENDURE, 20)
        );
    }

    /**
     * Chief Bandit (lv70–120).
     * Assaulter(max) → Pickpocket → Chakra → Meso Guard → Meso Explosion → Band of Thieves → Shield Mastery.
     */
    private static List<BuildStep> chiefBanditBuild() {
        return List.of(
                s(ChiefBandit.ASSAULTER, 30),
                s(ChiefBandit.PICKPOCKET, 30),
                s(ChiefBandit.CHAKRA, 30),
                s(ChiefBandit.MESO_GUARD, 30),
                s(ChiefBandit.MESO_EXPLOSION, 30),
                s(ChiefBandit.BAND_OF_THIEVES, 20),
                s(ChiefBandit.SHIELD_MASTERY, 20)
        );
    }

    /**
     * Shadower (lv120–200).
     * Assassinate(max) → Boomerang Step(max) → Venom Stab(max) → Shadow Shifter → Smoke Screen → Taunt → Ninja Ambush → MW → Will → MW(max).
     */
    private static List<BuildStep> shadowerBuild() {
        return List.of(
                s(Shadower.ASSASSINATE, 30),
                s(Shadower.BOOMERANG_STEP, 30),
                s(Shadower.VENOMOUS_STAB, 30),
                s(Shadower.SHADOW_SHIFTER, 30),
                s(Shadower.SMOKE_SCREEN, 30),
                s(Shadower.TAUNT, 20),
                s(Shadower.NINJA_AMBUSH, 30),
                s(Shadower.MAPLE_WARRIOR, 13),
                s(Shadower.HEROS_WILL, 1),
                s(Shadower.MAPLE_WARRIOR, 19),
                s(Shadower.HEROS_WILL, 5),
                s(Shadower.MAPLE_WARRIOR, 30)
        );
    }

    // =========================================================================
    // PIRATE BRANCH
    // =========================================================================

    /**
     * Brawler (lv30–70) — knuckle path.
     * Improve Max HP → Knuckler Mastery → Corkscrew Blow → Booster → Double Uppercut → Oak Barrel → MP Recovery.
     */
    private static List<BuildStep> brawlerBuild() {
        return List.of(
                s(Brawler.IMPROVE_MAX_HP, 20),
                s(Brawler.KNUCKLER_MASTERY, 20),
                s(Brawler.CORKSCREW_BLOW, 30),
                s(Brawler.KNUCKLER_BOOSTER, 20),
                s(Brawler.DOUBLE_UPPERCUT, 20),
                s(Brawler.OAK_BARREL, 10),
                s(Brawler.MP_RECOVERY, 20)
        );
    }

    /**
     * Marauder (lv70–120).
     * Energy Charge → Energy Blast(max) → Shockwave(max) → Stun Mastery → Energy Drain → Transformation.
     */
    private static List<BuildStep> marauderBuild() {
        return List.of(
                s(Marauder.ENERGY_CHARGE, 20),
                s(Marauder.ENERGY_BLAST, 30),
                s(Marauder.SHOCKWAVE, 30),
                s(Marauder.STUN_MASTERY, 20),
                s(Marauder.ENERGY_DRAIN, 30),
                s(Marauder.TRANSFORMATION, 10)
        );
    }

    /**
     * Buccaneer (lv120–200).
     * Barrage(max) → Dragon Strike(max) → Super Transform → Speed Infusion → Time Leap → MW → Will → Snatch → Demolition → MW(max).
     */
    private static List<BuildStep> buccaneerBuild() {
        return List.of(
                s(Buccaneer.BARRAGE, 30),
                s(Buccaneer.DRAGON_STRIKE, 30),
                s(Buccaneer.SUPER_TRANSFORMATION, 30),
                s(Buccaneer.SPEED_INFUSION, 30),
                s(Buccaneer.TIME_LEAP, 30),
                s(Buccaneer.MAPLE_WARRIOR, 13),
                s(Buccaneer.PIRATES_RAGE, 30),
                s(Buccaneer.MAPLE_WARRIOR, 19),
                s(Buccaneer.SNATCH, 30),
                s(Buccaneer.DEMOLITION, 30),
                s(Buccaneer.MAPLE_WARRIOR, 30)
        );
    }

    /**
     * Gunslinger (lv30–70) — gun path.
     * Mastery → Invisible Shot → Grenade(max) → Booster → Blank Shot → Recoil Shot.
     */
    private static List<BuildStep> gunslingerBuild() {
        return List.of(
                s(Gunslinger.GUN_MASTERY, 20),
                s(Gunslinger.INVISIBLE_SHOT, 20),
                s(Gunslinger.GRENADE, 30),
                s(Gunslinger.GUN_BOOSTER, 20),
                s(Gunslinger.BLANK_SHOT, 20),
                s(Gunslinger.RECOIL_SHOT, 10)
        );
    }

    /**
     * Outlaw (lv70–120).
     * Homing Beacon → Flame Thrower(max) → Ice Splitter(max) → Burst Fire → Octopus → Gaviota.
     */
    private static List<BuildStep> outlawBuild() {
        return List.of(
                s(Outlaw.HOMING_BEACON, 20),
                s(Outlaw.FLAME_THROWER, 30),
                s(Outlaw.ICE_SPLITTER, 30),
                s(Outlaw.BURST_FIRE, 20),
                s(Outlaw.OCTOPUS, 30),
                s(Outlaw.GAVIOTA, 30)
        );
    }

    /**
     * Corsair (lv120–200).
     * Rapid Fire(max) → Battleship(max) → Battleship Cannon(max) → Battleship Torpedo(max) → Speed Infusion → Bullseye → Hypnotize → MW → Wrath → Aerial → MW(max).
     */
    private static List<BuildStep> corsairBuild() {
        return List.of(
                s(Corsair.RAPID_FIRE, 30),
                s(Corsair.BATTLE_SHIP, 30),
                s(Corsair.BATTLESHIP_CANNON, 30),
                s(Corsair.BATTLESHIP_TORPEDO, 30),
                s(Corsair.SPEED_INFUSION, 30),
                s(Corsair.BULLSEYE, 30),
                s(Corsair.HYPNOTIZE, 30),
                s(Corsair.MAPLE_WARRIOR, 13),
                s(Corsair.WRATH_OF_THE_OCTOPI, 30),
                s(Corsair.MAPLE_WARRIOR, 19),
                s(Corsair.AERIAL_STRIKE, 30),
                s(Corsair.MAPLE_WARRIOR, 30)
        );
    }

    // =========================================================================
    // ARAN BRANCH
    // =========================================================================

    /**
     * Aran 1st job (lv10–30).
     * Combat Step → Double Swing → Combo Ability → Polearm Booster.
     */
    private static List<BuildStep> aran1Build() {
        return List.of(
                s(Aran.COMBAT_STEP, 15),
                s(Aran.DOUBLE_SWING, 20),
                s(Aran.COMBO_ABILITY, 20),
                s(Aran.POLEARM_BOOSTER, 20)
        );
    }

    /**
     * Aran 2nd job (lv30–70).
     * Polearm Mastery → Triple Swing(max) → Body Pressure → Combo Drain → Combo Smash → Final Charge.
     */
    private static List<BuildStep> aran2Build() {
        return List.of(
                s(Aran.POLEARM_MASTERY, 20),
                s(Aran.TRIPLE_SWING, 20),
                s(Aran.BODY_PRESSURE, 20),
                s(Aran.COMBO_DRAIN, 20),
                s(Aran.COMBO_SMASH, 20),
                s(Aran.FINAL_CHARGE, 20)
        );
    }

    /**
     * Aran 3rd job (lv70–120).
     * Full Swing(max) → Combo Fenrir(max) → Rolling Spin(max) → Smart Knockback → Snow Charge → Combo Critical → Final Toss.
     */
    private static List<BuildStep> aran3Build() {
        return List.of(
                s(Aran.FULL_SWING, 30),
                s(Aran.COMBO_FENRIR, 30),
                s(Aran.ROLLING_SPIN, 20),
                s(Aran.SMART_KNOCKBACK, 20),
                s(Aran.SNOW_CHARGE, 20),
                s(Aran.COMBO_CRITICAL, 20),
                s(Aran.FINAL_TOSS, 20)
        );
    }

    /**
     * Aran 4th job (lv120–200).
     * Over Swing(max) → Combo Tempest(max) → Combo Barrier(max) → High Mastery → High Defense → Final Blow → MW → Will → Freeze Standing → MW(max).
     */
    private static List<BuildStep> aran4Build() {
        return List.of(
                s(Aran.OVER_SWING, 30),
                s(Aran.COMBO_TEMPEST, 30),
                s(Aran.COMBO_BARRIER, 30),
                s(Aran.HIGH_MASTERY, 30),
                s(Aran.HIGH_DEFENSE, 30),
                s(Aran.FINAL_BLOW, 30),
                s(Aran.MAPLE_WARRIOR, 13),
                s(Aran.HEROS_WILL, 1),
                s(Aran.MAPLE_WARRIOR, 19),
                s(Aran.FREEZE_STANDING, 20),
                s(Aran.HEROS_WILL, 5),
                s(Aran.MAPLE_WARRIOR, 30)
        );
    }

    // ─── Level-up ─────────────────────────────────────────────────────────────

    /**
     * Detects level-up; sends prompts BEFORE spending SP/AP so that Hero's
     * variant prompt can gate spending until the owner responds.
     */
    static void checkLevelUp(BotEntry entry, Character bot) {
        int lvl = bot.getLevel();
        if (entry.lastKnownLevel == lvl) return;
        int prev = entry.lastKnownLevel;
        entry.lastKnownLevel = lvl;
        if (prev == -1) {
            autoAssignSp(entry, bot);
            autoAssignAp(entry, bot);
            return;
        }

        // Send job/build prompts first — some (Hero SP variant) gate SP spending
        if (lvl == 8 || lvl == 10 || lvl == 30 || lvl == 70 || lvl == 120) {
            entry.grinding  = false;
            entry.following = true;
            BotChatManager.checkBotStatus(entry, bot);
        }

        autoAssignSp(entry, bot);
        autoAssignAp(entry, bot);
    }

    /** Returns the next job-advancement prompt (updating jobPromptSent), or null if none pending. */
    static String buildJobPrompt(BotEntry entry, Character bot) {
        int lvl = bot.getLevel();
        Job job = bot.getJob();
        int prompted = entry.jobPromptSent;

        if (job == Job.BEGINNER) {
            if (lvl >= 10 && prompted < 10) {
                entry.jobPromptSent = 10;
                return "hey i can change jobs now!! warrior, mage, bowman, thief, or pirate?";
            } else if (lvl >= 8 && prompted < 8) {
                entry.jobPromptSent = 8;
                return "i can become a mage already if u want, or wait til lv10 for other jobs";
            }
            return null;
        }

        if (lvl >= 30 && prompted < 30) {
            String msg = switch (job) {
                case WARRIOR  -> "lv30! 2nd job time~ fighter, page, or spearman?";
                case MAGICIAN -> "lv30! pick 2nd job: f/p wizard, i/l wizard, or cleric?";
                case BOWMAN   -> "lv30! hunter or crossbowman?";
                case THIEF    -> "lv30! assassin or bandit?";
                case PIRATE   -> "lv30! brawler or gunslinger?";
                default       -> null;
            };
            if (msg != null) { entry.jobPromptSent = 30; return msg; }
        }

        if (lvl >= 70 && prompted < 70) {
            String msg = switch (job) {
                case FIGHTER     -> "lv70!! 3rd job, type 'crusader'";
                case PAGE        -> "lv70!! type 'white knight' or 'wk'";
                case SPEARMAN    -> "lv70!! type 'dragon knight' or 'dk'";
                case FP_WIZARD   -> "lv70!! type 'fp mage'";
                case IL_WIZARD   -> "lv70!! type 'il mage'";
                case CLERIC      -> "lv70!! type 'priest'";
                case HUNTER      -> "lv70!! type 'ranger'";
                case CROSSBOWMAN -> "lv70!! type 'sniper'";
                case ASSASSIN    -> "lv70!! type 'hermit'";
                case BANDIT      -> "lv70!! type 'chief bandit' or 'cb'";
                case BRAWLER     -> "lv70!! type 'marauder'";
                case GUNSLINGER  -> "lv70!! type 'outlaw'";
                default          -> null;
            };
            if (msg != null) { entry.jobPromptSent = 70; return msg; }
        }

        if (lvl >= 120 && prompted < 120) {
            String msg = switch (job) {
                case CRUSADER     -> "lv120!! type 'hero' for 4th job!!";
                case WHITEKNIGHT  -> "lv120!! type 'paladin'";
                case DRAGONKNIGHT -> "lv120!! type 'dark knight' or 'drk'";
                case FP_MAGE      -> "lv120!! type 'fp archmage' or 'fp arch'";
                case IL_MAGE      -> "lv120!! type 'il archmage' or 'il arch'";
                case PRIEST       -> "lv120!! type 'bishop'";
                case RANGER       -> "lv120!! type 'bowmaster' or 'bm'";
                case SNIPER       -> "lv120!! type 'marksman' or 'mm'";
                case HERMIT       -> "lv120!! type 'night lord' or 'nl'";
                case CHIEFBANDIT  -> "lv120!! type 'shadower'";
                case MARAUDER     -> "lv120!! type 'buccaneer' or 'bucc'";
                case OUTLAW       -> "lv120!! type 'corsair'";
                case ARAN3        -> "lv120!! type 'aran' for 4th job!!";
                default           -> null;
            };
            if (msg != null) { entry.jobPromptSent = 120; return msg; }
        }

        return null;
    }
}
