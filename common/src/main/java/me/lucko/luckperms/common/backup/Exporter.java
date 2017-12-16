/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.common.backup;

import me.lucko.luckperms.api.Node;
import me.lucko.luckperms.common.commands.sender.Sender;
import me.lucko.luckperms.common.commands.utils.CommandUtils;
import me.lucko.luckperms.common.locale.Message;
import me.lucko.luckperms.common.logging.ProgressLogger;
import me.lucko.luckperms.common.model.Group;
import me.lucko.luckperms.common.model.Track;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.node.NodeFactory;
import me.lucko.luckperms.common.plugin.LuckPermsPlugin;
import me.lucko.luckperms.common.references.HolderType;
import me.lucko.luckperms.common.storage.Storage;
import me.lucko.luckperms.common.utils.Cycle;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Handles export operations
 */
public class Exporter implements Runnable {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");

    private static void write(BufferedWriter writer, String s) {
        try {
            writer.write(s);
            writer.newLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private final LuckPermsPlugin plugin;
    private final Sender executor;
    private final Path filePath;
    private final ProgressLogger log;

    public Exporter(LuckPermsPlugin plugin, Sender executor, Path filePath) {
        this.plugin = plugin;
        this.executor = executor;
        this.filePath = filePath;

        log = new ProgressLogger(null, Message.EXPORT_LOG, Message.EXPORT_LOG_PROGRESS);
        log.addListener(plugin.getConsoleSender());
        log.addListener(executor);
    }

    @Override
    public void run() {
        try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
            log.log("Starting.");

            write(writer, "# LuckPerms Export File");
            write(writer, "# Generated by " + executor.getNameWithLocation() + " at " + DATE_FORMAT.format(new Date(System.currentTimeMillis())));
            write(writer, "");

            // Export Groups
            log.log("Starting group export.");

            // Create the actual groups first
            write(writer, "# Create groups");

            AtomicInteger groupCount = new AtomicInteger(0);

            List<? extends Group> groups = plugin.getGroupManager().getAll().values().stream()
                    // export groups in order of weight
                    .sorted((o1, o2) -> {
                        int i = Integer.compare(o2.getWeight().orElse(0), o1.getWeight().orElse(0));
                        return i != 0 ? i : o1.getName().compareToIgnoreCase(o2.getName());
                    }).collect(Collectors.toList());

            for (Group group : groups) {
                if (!group.getName().equals("default")) {
                    write(writer, "/lp creategroup " + group.getName());
                }
            }

            for (Group group : groups) {
                if (groupCount.get() == 0) {
                    write(writer, "");
                }

                write(writer, "# Export group: " + group.getName());
                for (Node node : group.getEnduringNodes().values()) {
                    write(writer, "/lp " + NodeFactory.nodeAsCommand(node, group.getName(), HolderType.GROUP, true));
                }
                write(writer, "");
                log.logAllProgress("Exported {} groups so far.", groupCount.incrementAndGet());
            }

            log.log("Exported " + groupCount.get() + " groups.");

            write(writer, "");
            write(writer, "");

            // Export tracks
            log.log("Starting track export.");

            Collection<? extends Track> tracks = plugin.getTrackManager().getAll().values();
            if (!tracks.isEmpty()) {

                // Create the actual tracks first
                write(writer, "# Create tracks");
                for (Track track : tracks) {
                    write(writer, "/lp createtrack " + track.getName());
                }

                write(writer, "");

                AtomicInteger trackCount = new AtomicInteger(0);
                for (Track track : plugin.getTrackManager().getAll().values()) {
                    write(writer, "# Export track: " + track.getName());
                    for (String group : track.getGroups()) {
                        write(writer, "/lp track " + track.getName() + " append " + group);
                    }
                    write(writer, "");
                    log.logAllProgress("Exported {} tracks so far.", trackCount.incrementAndGet());
                }

                write(writer, "");
                write(writer, "");
            }

            log.log("Exported " + tracks.size() + " tracks.");


            // Users are migrated in separate threads.
            // This is because there are likely to be a lot of them, and because we can.
            // It's a big speed improvement, since the database/files are split up and can handle concurrent reads.

            log.log("Starting user export. Finding a list of unique users to export.");

            // Find all of the unique users we need to export
            Storage ds = plugin.getStorage();
            Set<UUID> users = ds.getUniqueUsers().join();
            log.log("Found " + users.size() + " unique users to export.");

            write(writer, "# Export users");

            // divide into 16 pools.
            Cycle<List<UUID>> userPools = new Cycle<>(CommandUtils.nInstances(32, ArrayList::new));
            for (UUID uuid : users) {
                userPools.next().add(uuid);
            }

            log.log("Split users into " + userPools.getBacking().size() + " threads for export.");

            // Setup a file writing lock. We don't want multiple threads writing at the same time.
            // The write function accepts a list of strings, as we want a user's data to be grouped together.
            // This means it can be processed and added in one go.
            ReentrantLock lock = new ReentrantLock();
            Consumer<List<String>> writeFunction = strings -> {
                lock.lock();
                try {
                    for (String s : strings) {
                        write(writer, s);
                    }
                } finally {
                    lock.unlock();
                }
            };

            // A set of futures, which are really just the threads we need to wait for.
            Set<CompletableFuture<Void>> futures = new HashSet<>();

            AtomicInteger userCount = new AtomicInteger(0);

            // iterate through each user sublist.
            for (List<UUID> subList : userPools.getBacking()) {

                // register and start a new thread to process the sublist
                futures.add(CompletableFuture.runAsync(() -> {

                    // iterate through each user in the sublist, and grab their data.
                    for (UUID uuid : subList) {
                        try {
                            // actually export the user. this output will be fed to the writing function when we have all of the user's data.
                            List<String> output = new ArrayList<>();

                            plugin.getStorage().loadUser(uuid, null).join();
                            User user = plugin.getUserManager().getIfLoaded(uuid);
                            output.add("# Export user: " + user.getUuid().toString() + " - " + user.getName().orElse("unknown username"));

                            boolean inDefault = false;
                            for (Node node : user.getEnduringNodes().values()) {
                                if (node.isGroupNode() && node.getGroupName().equalsIgnoreCase("default")) {
                                    inDefault = true;
                                    continue;
                                }

                                output.add("/lp " + NodeFactory.nodeAsCommand(node, user.getUuid().toString(), HolderType.USER, true));
                            }

                            if (!user.getPrimaryGroup().getStoredValue().orElse("default").equalsIgnoreCase("default")) {
                                output.add("/lp user " + user.getUuid().toString() + " switchprimarygroup " + user.getPrimaryGroup().getStoredValue().get());
                            }

                            if (!inDefault) {
                                output.add("/lp user " + user.getUuid().toString() + " parent remove default");
                            }

                            plugin.getUserManager().cleanup(user);
                            writeFunction.accept(output);

                            log.logProgress("Exported {} users so far.", userCount.incrementAndGet());

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }, plugin.getScheduler().async()));
            }

            // all of the threads have been scheduled now and are running. we just need to wait for them all to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()])).join();

            log.log("Exported " + userCount.get() + " users.");

            writer.flush();
            log.getListeners().forEach(l -> Message.LOG_EXPORT_SUCCESS.send(l, filePath.toFile().getAbsolutePath()));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
