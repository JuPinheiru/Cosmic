package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import server.Storage;

public class BankCommand extends Command {
    {
        setDescription("Abre o storage remotamente");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (player.getLevel() < 15) {
            player.dropMessage(1, "You may only use the storage once you have reached level 15.");
            return;
        }
        Storage storage = player.getStorage();
        storage.sendStorage(c, 1052009); // NPC ID do storage keeper
    }
}