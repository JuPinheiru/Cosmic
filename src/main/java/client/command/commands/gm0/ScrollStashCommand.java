package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import server.ItemInformationProvider;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class ScrollStashCommand extends Command {
    {
        setDescription("Stash de scrolls. !scrolls | !scrolls withdraw <itemid> <qty>");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        int accountId = player.getAccountID();

        // Sem parametros = deposita tudo
        if (params.length == 0) {
            depositAll(c, player, accountId);
            return;
        }

        switch (params[0].toLowerCase()) {
            case "withdraw" -> {
                if (params.length < 3) {
                    player.dropMessage(5, "Uso: !scrolls withdraw <itemid> <quantidade>");
                    return;
                }
                int itemId = Integer.parseInt(params[1]);
                int qty = Integer.parseInt(params[2]);
                withdraw(c, player, accountId, itemId, qty);
            }
            case "list" -> list(player, accountId);
            default -> player.dropMessage(5, "Uso: !scrolls | !scrolls withdraw <itemid> <qty> | !scrolls list");
        }
    }

    private void depositAll(Client c, Character player, int accountId) {
        Inventory useInv = player.getInventory(InventoryType.USE);
        List<Item> toDeposit = new ArrayList<>();

        for (short i = 1; i <= useInv.getSlotLimit(); i++) {
            Item item = useInv.getItem(i);
            if (item == null) continue;
            int prefix = item.getItemId() / 10000;
// Scrolls: 204xxxx (equip scrolls) e 2030xxx (return scrolls)
            if (prefix == 204 || item.getItemId() / 1000 == 2030) {
                toDeposit.add(item);
            }
        }

        if (toDeposit.isEmpty()) {
            player.dropMessage(5, "[Scroll Stash] Nenhum scroll encontrado no inventario.");
            return;
        }

        int count = 0;
        try (Connection con = DatabaseConnection.getConnection()) {
            for (Item item : toDeposit) {
                int itemId = item.getItemId();
                int qty = item.getQuantity();
                short slot = item.getPosition();

                try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO scroll_stash (accountid, itemid, quantity) VALUES (?,?,?) " +
                    "ON DUPLICATE KEY UPDATE quantity = quantity + ?")) {
                    ps.setInt(1, accountId);
                    ps.setInt(2, itemId);
                    ps.setInt(3, qty);
                    ps.setInt(4, qty);
                    ps.executeUpdate();
                }
                InventoryManipulator.removeFromSlot(c, InventoryType.USE, slot, (short) qty, false);
                count++;
            }
        } catch (SQLException e) { e.printStackTrace(); }

        player.dropMessage(5, "[Scroll Stash] " + count + " tipo(s) de scroll depositado(s)!");
    }

    private void withdraw(Client c, Character player, int accountId, int itemId, int qty) {
        if (itemId / 1000000 != 2) {
            player.dropMessage(5, "[Scroll Stash] Item invalido.");
            return;
        }
        try (Connection con = DatabaseConnection.getConnection()) {
            int stashQty = 0;
            try (PreparedStatement ps = con.prepareStatement(
                "SELECT quantity FROM scroll_stash WHERE accountid = ? AND itemid = ?")) {
                ps.setInt(1, accountId);
                ps.setInt(2, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) stashQty = rs.getInt("quantity");
                }
            }
            if (stashQty == 0) {
                player.dropMessage(5, "[Scroll Stash] Item nao encontrado no stash.");
                return;
            }
            if (stashQty < qty) {
                player.dropMessage(5, "[Scroll Stash] Disponivel: " + stashQty);
                return;
            }
            if (!InventoryManipulator.checkSpace(c, itemId, (short) qty, "")) {
                player.dropMessage(5, "[Scroll Stash] Inventario cheio.");
                return;
            }
            if (stashQty == qty) {
                try (PreparedStatement ps = con.prepareStatement(
                    "DELETE FROM scroll_stash WHERE accountid = ? AND itemid = ?")) {
                    ps.setInt(1, accountId);
                    ps.setInt(2, itemId);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE scroll_stash SET quantity = quantity - ? WHERE accountid = ? AND itemid = ?")) {
                    ps.setInt(1, qty);
                    ps.setInt(2, accountId);
                    ps.setInt(3, itemId);
                    ps.executeUpdate();
                }
            }
            InventoryManipulator.addById(c, itemId, (short) qty);
            String name = ItemInformationProvider.getInstance().getName(itemId);
            player.dropMessage(5, "[Scroll Stash] " + (name != null ? name : itemId) + " x" + qty + " retirado!");
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void list(Character player, int accountId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                "SELECT itemid, quantity FROM scroll_stash WHERE accountid = ? ORDER BY itemid")) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                int count = 0;
                StringBuilder sb = new StringBuilder("[Scroll Stash] ");
                while (rs.next()) {
                    String name = ItemInformationProvider.getInstance().getName(rs.getInt("itemid"));
                    sb.append(name != null ? name : rs.getInt("itemid"))
                      .append(" x").append(rs.getInt("quantity")).append(" | ");
                    count++;
                }
                player.dropMessage(5, count == 0 ? "[Scroll Stash] Vazio." : sb.toString());
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }
}