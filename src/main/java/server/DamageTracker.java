package server;

import tools.DatabaseConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DamageTracker {

    // characterid -> session_id ativo
    private static final Map<Integer, Integer> activeSessions = new ConcurrentHashMap<>();

    public static int startSession(int characterid, int accountid, int mapid, long initialExp, int initialMeso) {
        endSession(characterid);
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO damage_sessions (accountid, characterid, mapid, started_at, initial_exp, initial_meso) VALUES (?,?,?,?,?,?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, accountid);
            ps.setInt(2, characterid);
            ps.setInt(3, mapid);
            ps.setLong(4, System.currentTimeMillis());
            ps.setLong(5, initialExp);
            ps.setInt(6, initialMeso);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    int sessionId = rs.getInt(1);
                    activeSessions.put(characterid, sessionId);
                    return sessionId;
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return -1;
    }

    public static void endSession(int characterid) {
        Integer sessionId = activeSessions.remove(characterid);
        if (sessionId != null) {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(
                         "UPDATE damage_sessions SET ended_at = ? WHERE id = ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setInt(2, sessionId);
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        }
    }

    public static void logDamage(int characterid, int skillid, int mobid, long damage, boolean isCrit) {
        Integer sessionId = activeSessions.get(characterid);
        if (sessionId == null) return;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO damage_log (session_id, characterid, skillid, mobid, damage, is_crit, is_heal, is_received, timestamp) VALUES (?,?,?,?,?,?,0,0,?)")) {
            ps.setInt(1, sessionId);
            ps.setInt(2, characterid);
            ps.setInt(3, skillid);
            ps.setInt(4, mobid);
            ps.setLong(5, damage);
            ps.setInt(6, isCrit ? 1 : 0);
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public static void logHeal(int characterid, long amount) {
        Integer sessionId = activeSessions.get(characterid);
        if (sessionId == null) return;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO damage_log (session_id, characterid, skillid, damage, is_crit, is_heal, is_received, timestamp) VALUES (?,?,0,?,0,1,0,?)")) {
            ps.setInt(1, sessionId);
            ps.setInt(2, characterid);
            ps.setLong(3, amount);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public static void logDamageReceived(int characterid, long amount) {
        Integer sessionId = activeSessions.get(characterid);
        if (sessionId == null) return;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO damage_log (session_id, characterid, skillid, damage, is_crit, is_heal, is_received, timestamp) VALUES (?,?,0,?,0,0,1,?)")) {
            ps.setInt(1, sessionId);
            ps.setInt(2, characterid);
            ps.setLong(3, amount);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public static void logExpGain(int characterid, long amount) {
        Integer sessionId = activeSessions.get(characterid);
        if (sessionId == null) return;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE damage_sessions SET current_exp = current_exp + ? WHERE id = ?")) {
            ps.setLong(1, amount);
            ps.setInt(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public static void logMesoGain(int characterid, int amount) {
        Integer sessionId = activeSessions.get(characterid);
        if (sessionId == null) return;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE damage_sessions SET current_meso = current_meso + ? WHERE id = ?")) {
            ps.setInt(1, amount);
            ps.setInt(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public static Integer getSessionId(int characterid) {
        return activeSessions.get(characterid);
    }
}