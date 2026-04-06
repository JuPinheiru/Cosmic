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

public class CollectionCommand extends Command {
    {
        setDescription("Collection de equips. !cc | !cc withdraw <itemid>");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        int accountId = player.getAccountID();

        if (params.length == 0) {
            depositAll(c, player, accountId);
            return;
        }

        switch (params[0].toLowerCase()) {
            case "withdraw" -> {
                if (params.length < 2) {
                    player.dropMessage(5, "Uso: !cc withdraw <itemid>");
                    return;
                }
                int itemId = Integer.parseInt(params[1]);
                withdraw(c, player, accountId, itemId);
            }
            case "list" -> list(player, accountId);
            default -> player.dropMessage(5, "Uso: !cc | !cc withdraw <itemid> | !cc list");
        }
    }

    private void depositAll(Client c, Character player, int accountId) {
        Inventory equipInv = player.getInventory(InventoryType.EQUIP);
        List<Item> toDeposit = new ArrayList<>();

        // Coleta itens que nao estao na collection
        try (Connection con = DatabaseConnection.getConnection()) {
            for (short i = 1; i <= equipInv.getSlotLimit(); i++) {
                Item item = equipInv.getItem(i);
                if (item == null) continue;
                int itemId = item.getItemId();
                // Verifica se já está na collection
                try (PreparedStatement ps = con.prepareStatement(
                    "SELECT itemid FROM account_collection WHERE accountid = ? AND itemid = ?")) {
                    ps.setInt(1, accountId);
                    ps.setInt(2, itemId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            toDeposit.add(item);
                        }
                    }
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }

        if (toDeposit.isEmpty()) {
            player.dropMessage(5, "[Collection] Nenhum item novo para depositar.");
            return;
        }

        int count = 0;
        try (Connection con = DatabaseConnection.getConnection()) {
            for (Item item : toDeposit) {
                int itemId = item.getItemId();
                short slot = item.getPosition();

                // Verifica de novo (seguranca)
                try (PreparedStatement ps = con.prepareStatement(
                    "SELECT itemid FROM account_collection WHERE accountid = ? AND itemid = ?")) {
                    ps.setInt(1, accountId);
                    ps.setInt(2, itemId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) continue; // ja existe
                    }
                }

                // Insere na collection
                try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO account_collection (accountid, itemid) VALUES (?, ?)")) {
                    ps.setInt(1, accountId);
                    ps.setInt(2, itemId);
                    ps.executeUpdate();
                }

                // Calcula novo bonus
                int totalItems;
                try (PreparedStatement ps = con.prepareStatement(
                    "SELECT COUNT(*) as total FROM account_collection WHERE accountid = ?")) {
                    ps.setInt(1, accountId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        totalItems = rs.getInt("total");
                    }
                }

                int newBonus = (totalItems / 10) * 3;
                player.setCollectionBonus(newBonus);
                try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE accounts SET collectionBonus = ? WHERE id = ?")) {
                    ps.setInt(1, newBonus);
                    ps.setInt(2, accountId);
                    ps.executeUpdate();
                }

                // Remove do inventario
                InventoryManipulator.removeFromSlot(c, InventoryType.EQUIP, slot, (short) 1, false);

                String itemName = ItemInformationProvider.getInstance().getName(itemId);
                String bonusMsg = totalItems % 10 == 0
                    ? " MILESTONE! +" + newBonus + " STR/DEX/INT/LUK total!"
                    : " (" + (10 - totalItems % 10) + " para o proximo bonus)";
                player.dropMessage(5, "[Collection] '" + (itemName != null ? itemName : String.valueOf(itemId)) + "' adicionado!" + bonusMsg + " | Total: " + totalItems + " itens, +" + newBonus + " STR/DEX/INT/LUK");
                count++;
            }
            player.equipChanged();
        } catch (SQLException e) { e.printStackTrace(); }

        if (count > 1) {
            player.dropMessage(5, "[Collection] " + count + " item(ns) depositados no total.");
        }
    }

    private void withdraw(Client c, Character player, int accountId, int itemId) {
        try (Connection con = DatabaseConnection.getConnection()) {
            // Verifica se está na collection (sem remover — é uma cópia)
            try (PreparedStatement ps = con.prepareStatement(
                "SELECT itemid FROM account_collection WHERE accountid = ? AND itemid = ?")) {
                ps.setInt(1, accountId);
                ps.setInt(2, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        player.dropMessage(5, "[Collection] Item nao encontrado na collection.");
                        return;
                    }
                }
            }
            if (!InventoryManipulator.checkSpace(c, itemId, (short) 1, "")) {
                player.dropMessage(5, "[Collection] Inventario cheio.");
                return;
            }
            // Apenas cria uma cópia no inventário, sem remover da collection
            InventoryManipulator.addById(c, itemId, (short) 1);
            player.equipChanged();
            String name = ItemInformationProvider.getInstance().getName(itemId);
            player.dropMessage(5, "[Collection] Copia de '" + (name != null ? name : String.valueOf(itemId)) + "' adicionada ao inventario. O item permanece na sua colecao!");
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void list(Character player, int accountId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                "SELECT ac.itemid, COALESCE(n.name, CONCAT(\'Item \', ac.itemid)) as name " +
                "FROM account_collection ac " +
                "LEFT JOIN item_names n ON n.itemid = ac.itemid " +
                "WHERE ac.accountid = ? ORDER BY ac.itemid LIMIT 20")) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder sb = new StringBuilder("[Collection] ");
                int count = 0;
                while (rs.next()) {
                    sb.append(rs.getString("name")).append(" (").append(rs.getInt("itemid")).append(") | ");
                    count++;
                }
                player.dropMessage(5, count == 0 ? "[Collection] Vazia." : sb.toString());
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }
}
