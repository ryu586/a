package com.example.structphysics;

import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class StructPhysicsPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private StructureEngine engine;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        engine = new StructureEngine(new Settings(getConfig()));
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("structphysics") != null) getCommand("structphysics").setExecutor(this);
        // 毎tick: 変更箇所の解析 → 損傷蓄積 → 破断 → 崩落
        getServer().getScheduler().runTaskTimer(this, () -> engine.tick(), 1L, 1L);
        getLogger().info("StructPhysics enabled");
    }

    // ===== ブロック変化イベント (処理は次tickのエンジンで行う) =====

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        engine.markChanged(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        engine.markChanged(e.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        for (Block b : e.blockList()) engine.markChanged(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        for (Block b : e.blockList()) engine.markChanged(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        engine.markChanged(e.getBlock());
    }

    /** 落下ブロックの着地・砂の落下開始など */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent e) {
        engine.markChanged(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        engine.markChanged(e.getBlock());
        for (Block b : e.getBlocks()) {
            engine.markChanged(b);
            engine.markChanged(b.getRelative(e.getDirection()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        engine.markChanged(e.getBlock());
        for (Block b : e.getBlocks()) {
            engine.markChanged(b);
            engine.markChanged(b.getRelative(e.getDirection()));
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent e) {
        engine.unload(e.getWorld());
    }

    // ===== コマンド =====

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "help";
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                engine = new StructureEngine(new Settings(getConfig()));
                sender.sendMessage("§aStructPhysics: 設定を再読み込みしました (損傷データはリセット)");
            }
            case "inspect", "analyze" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("プレイヤーのみ実行できます");
                    return true;
                }
                Block b = p.getTargetBlockExact(30);
                if (b == null) {
                    sender.sendMessage("§c見ているブロックがありません");
                    return true;
                }
                if (sub.equals("analyze")) {
                    engine.markDirty(b);
                    sender.sendMessage("§a次tickに構造解析を実行します");
                } else {
                    for (String line : engine.inspect(b)) sender.sendMessage(line);
                }
            }
            default -> sender.sendMessage("§e/sp inspect §7見ているブロックの荷重・応力・安全率を表示\n"
                    + "§e/sp analyze §7見ている構造を強制的に再解析\n"
                    + "§e/sp reload §7config.yml を再読み込み");
        }
        return true;
    }
}