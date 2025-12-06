package space.ranzeplay.containeritemfinder.service;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ContainerIndexService {
    private static final Map<UUID, SearchTask> activeTasks = new ConcurrentHashMap<>();

    public record IndexedItem(String itemName, String id, int count, BlockPos containerPos, Map<String, Integer> enchantments) {
    }

    public static Map<String, Integer> extractEnchantments(ItemStack stack) {
        Map<String, Integer> enchants = new HashMap<>();

        ItemEnchantmentsComponent enchComp = stack.getComponents().get(DataComponentTypes.ENCHANTMENTS);
        if (enchComp == null) {
            return Collections.emptyMap();
        }

        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : enchComp.getEnchantmentEntries()) {
            RegistryEntry<Enchantment> reg = entry.getKey();
            if (reg == null) continue;

            Enchantment ench = reg.value();
            if (ench == null) continue;

            int level = entry.getIntValue();
            enchants.put(ench.description().getString(), level);

        }
        return enchants;
    }

    public static List<IndexedItem> indexItemsInContainer(BlockEntity container, BlockPos pos) {
        List<IndexedItem> items = new ArrayList<>();

        if (container instanceof ChestBlockEntity chest) {
            for (int i = 0; i < chest.size(); i++) {
                ItemStack stack = chest.getStack(i);
                if (!stack.isEmpty()) {

                    items.add(new IndexedItem(
                            stack.getItem().getName().getString(),
                            stack.getItem().getTranslationKey(),
                            stack.getCount(),
                            pos,
                            extractEnchantments(stack)
                    ));
                }
            }
        } else if (container instanceof ShulkerBoxBlockEntity shulker) {
            for (int i = 0; i < shulker.size(); i++) {
                ItemStack stack = shulker.getStack(i);
                if (!stack.isEmpty()) {
                    items.add(new IndexedItem(
                            stack.getItem().getName().getString(),
                            stack.getItem().getTranslationKey(),
                            stack.getCount(),
                            pos,
                            extractEnchantments(stack)
                    ));
                }
            }
        }

        return items;
    }

    private static List<IndexedItem> indexContainersInRange(SearchTask task, ServerWorld world, BlockPos center, int range) {
        List<IndexedItem> allItems = new ArrayList<>();
        int rangeSq = range * range;

        int chunkRange = range >> 4;
        int cx = center.getX() >> 4;
        int cz = center.getZ() >> 4;

        int totalContainers = 0;


        for (int dx = -chunkRange; dx <= chunkRange; dx++) {
            for (int dz = -chunkRange; dz <= chunkRange; dz++) {

                Chunk chunk = world.getChunk(cx + dx, cz + dz);

                for (BlockPos be : chunk.getBlockEntityPositions()) {

                    int y = be.getY();
                    BlockEntity beEntity = chunk.getBlockEntity(be);

                    // distance check
                    int rx = be.getX() - center.getX();
                    int ry = y - center.getY();
                    int rz = be.getZ() - center.getZ();
                    int distSq = rx * rx + ry * ry + rz * rz;
                    if (distSq > rangeSq) {
                        continue;
                    }

                    // container check
                    if (beEntity instanceof ChestBlockEntity || beEntity instanceof ShulkerBoxBlockEntity) {
                        totalContainers++;
                        allItems.addAll(indexItemsInContainer(beEntity, be));

                        if (task.source != null) {
                            task.source.sendMessage(task.createIndexedContainerMessage(be));
                        }
                    }

                    task.blocksSearched.incrementAndGet();
                }
            }
        }

        task.totalContainersSearched = totalContainers;
        return allItems;
    }

    private static Text createIndexResultMessage(List<IndexedItem> items, int totalContainersSearched) {
        if (items.isEmpty()) {
            return Text.translatable("info.cif.instant.index.not_found")
                    .formatted(Formatting.RED);
        }

        MutableText message = Text.empty();

        // First line: Summary
        Map<String, List<IndexedItem>> grouped = new HashMap<>();

        for (IndexedItem item : items) {

            // Build enchantment signature (null or sorted string)
            String enchKey;
            if (item.enchantments() == null || item.enchantments().isEmpty()) {
                enchKey = "NO_ENCH";
            } else {
                // Sort enchantments to make order irrelevant
                StringBuilder sb = new StringBuilder();
                item.enchantments().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(e -> sb.append(e.getKey()).append(":").append(e.getValue()).append(";"));
                enchKey = sb.toString();
            }

            // Final grouping key: name + ench signature
            String key = item.itemName() + "|" + enchKey;

            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }

        // Sort groups by total item count
        List<Map.Entry<String, List<IndexedItem>>> sorted = new ArrayList<>(grouped.entrySet());
        sorted.sort((a, b) -> {
            int countA = a.getValue().stream().mapToInt(IndexedItem::count).sum();
            int countB = b.getValue().stream().mapToInt(IndexedItem::count).sum();
            return Integer.compare(countB, countA);
        });

        // Build output
        for (Map.Entry<String, List<IndexedItem>> group : sorted) {

            List<IndexedItem> list = group.getValue();
            IndexedItem first = list.getFirst();

            int totalCount = list.stream().mapToInt(IndexedItem::count).sum();
            int containers = list.size();

            // Header line
            message.append(
                    Text.literal(totalCount + "x " + first.itemName() + " (" + containers + ")")
                            .formatted(Formatting.AQUA)
            ).append(Text.literal("\n"));

            // Show enchantments only once per group
            if (first.enchantments() != null && !first.enchantments().isEmpty()) {

                message.append(Text.literal("  • ").formatted(Formatting.GRAY));

                MutableText enchLine = Text.empty();

                first.enchantments().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(e -> enchLine.append(
                                Text.literal(e.getKey().replace("enchantment.minecraft.", "") + " " + e.getValue())
                                        .formatted(Formatting.LIGHT_PURPLE)
                        ).append(Text.literal(" ")));

                message.append(enchLine).append(Text.literal("\n"));
            }
        }

        return message;
    }

    public Text indexContainers(ServerCommandSource source, ServerWorld world, Vec3d center, int range) {
        if (!source.isExecutedByPlayer()) {
            return Text.translatable("info.cif.player_only").formatted(Formatting.RED);
        }

        ServerPlayerEntity player = source.getPlayer();
        assert player != null;

        UUID playerId = player.getUuid();
        if (activeTasks.containsKey(playerId)) {
            return Text.translatable("info.cif.instant.task_wip").formatted(Formatting.RED);
        }

        SearchTask task = new SearchTask(player, world, center, range);
        activeTasks.put(playerId, task);

        try {
            BlockPos blockCenter = new BlockPos((int) center.x, (int) center.y, (int) center.z);
            List<IndexedItem> items = indexContainersInRange(task, world, blockCenter, range);
            return createIndexResultMessage(items, task.totalContainersSearched);
        } finally {
            activeTasks.remove(playerId);
        }
    }

    public Text cancelSearch(ServerCommandSource source) {
        if (!source.isExecutedByPlayer()) {
            return Text.translatable("info.cif.player_only").formatted(Formatting.RED);
        }

        ServerPlayerEntity player = source.getPlayer();
        assert player != null;

        SearchTask task = activeTasks.remove(player.getUuid());
        if (task == null) {
            return Text.translatable("info.cif.instant.no_active").formatted(Formatting.RED);
        }

        return task.cancel();
    }

    public static class SearchTask {
        private static final long HEARTBEAT_INTERVAL = 10_000; // 10 seconds in milliseconds
        private final ServerPlayerEntity source;
        private final ServerWorld world;
        private final Vec3d center;
        private final int range;
        private final AtomicInteger blocksSearched = new AtomicInteger(0);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private long lastHeartbeatTime = 0;
        private int totalContainersSearched = 0;

        public SearchTask(ServerPlayerEntity source, ServerWorld world, Vec3d center, int range) {
            this.source = source;
            this.world = world;
            this.center = center;
            this.range = range;
        }

        private Text createHeartbeatMessage(int blocksSearched, double currentDistance) {
            return Text.translatable("info.cif.instant.index.heartbeat",
                            blocksSearched, currentDistance)
                    .formatted(Formatting.GRAY);
        }

        private Text createIndexedContainerMessage(BlockPos pos) {
            return Text.translatable("info.cif.instant.index.found",
                            pos.getX(), pos.getY(), pos.getZ())
                    .formatted(Formatting.GRAY);
        }

        private Text createCancelledMessage(int blocksSearched, double lastDistance) {
            return Text.translatable("info.cif.instant.index.cancel_info",
                            blocksSearched, lastDistance)
                    .formatted(Formatting.YELLOW);
        }

        private void sendHeartbeat(double currentDistance) {
            if (source != null && !cancelled.get()) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastHeartbeatTime >= HEARTBEAT_INTERVAL) {
                    source.sendMessage(createHeartbeatMessage(blocksSearched.get(), currentDistance));
                    lastHeartbeatTime = currentTime;
                }
            }
        }

        public Text cancel() {
            if (cancelled.compareAndSet(false, true) && source != null) {
                return createCancelledMessage(blocksSearched.get(),
                        Math.sqrt(
                                Math.pow(center.x, 2) +
                                        Math.pow(center.y, 2) +
                                        Math.pow(center.z, 2)
                        ));
            }
            return Text.translatable("info.cif.instant.index.cancel").formatted(Formatting.YELLOW);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }
} 