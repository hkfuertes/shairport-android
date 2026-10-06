// SPDX-License-Identifier: Apache-2.0
package com.hkfuertes.shairport.kiosk;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Manages the Shairport app from Kiosk Satellite: its settings (the plugin form) and its state as
 * Home Assistant entities. It never runs the receiver; it uses the app's adb interface
 * (README, "Configure from ADB") through KS's Shizuku access, one command at a time:
 * GET_STATUS every few seconds, CONFIGURE_SETTINGS for form or switch changes.
 */
public final class ShairportPlugin implements KioskPlugin {
    static final String APP = "com.hkfuertes.shairport";
    private static final String AM = "/system/bin/am";
    private static final int POLL_SECONDS = 5;
    private static final int TIMEOUT_MS = 10000;
    /** Shizuku wants at least 250 ms between two requests. */
    private static final int SPACING_MS = 300;
    private static final Pattern RESULT = Pattern.compile("Broadcast completed: result=(-?\\d+)(?:, data=\"(.*)\")?");
    private static final Map<String, Object> VOLUME = new LinkedHashMap<>();
    /** List settings: app value -> form label (the app accepts the labels as values too). */
    private static final Map<String, Map<String, String>> LABELS = new LinkedHashMap<>();
    static {
        VOLUME.put("unit", "%");
        VOLUME.put("stateClass", "measurement");
        VOLUME.put("accuracyDecimals", 0);
        Map<String, String> models = new LinkedHashMap<>();
        models.put("ShairportSync", "Generic");
        models.put("AudioAccessory1,1", "HomePod");
        models.put("AudioAccessory5,1", "HomePod mini");
        LABELS.put("model", models);
        Map<String, String> modes = new LinkedHashMap<>();
        modes.put("stereo", "Stereo");
        modes.put("mono", "Mono");
        LABELS.put("playback_mode", modes);
    }

    private final ArrayDeque<String[]> queue = new ArrayDeque<>();
    private PluginHost host;
    private ScheduledExecutorService worker;
    private boolean busy;
    /** Last GET_STATUS: Shairport Sync was running. */
    private boolean running;
    /** The form as KS last stored it; the app's values replace it once read. */
    private Map<String, Object> settings;

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) {
        attach(host, settings);
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "shairport-plugin");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::poll, 0, POLL_SECONDS, TimeUnit.SECONDS);
    }

    /** start() without the poll timer (tests drive poll() themselves). */
    synchronized void attach(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        this.settings = new LinkedHashMap<>(settings);
        // Older saved forms still need a boolean type for app-to-plugin reconciliation.
        this.settings.putIfAbsent("wifi_low_latency", false);
    }

    /** A form edit (KS UI or Remote Admin): send each changed value to the app. */
    @Override
    public synchronized void configure(Map<String, Object> next) {
        if (host == null) return;
        for (Map.Entry<String, Object> entry : next.entrySet()) {
            if (!Objects.equals(entry.getValue(), settings.get(entry.getKey()))) {
                queue.add(set(entry.getKey(), entry.getValue()));
            }
        }
        // The app starts its service only from its own UI: do it here, as adb would, also when
        // it is enabled but not running (e.g. after a reboot without "Start at boot").
        if (Boolean.TRUE.equals(next.get("receiver_enabled"))
                && (!running || !Boolean.TRUE.equals(settings.get("receiver_enabled")))) {
            queue.add(new String[] {AM, "start-foreground-service", "-n", APP + "/.ReceiverService"});
        }
        settings = new LinkedHashMap<>(next);
        if (queue.isEmpty()) return;
        queue.add(status()); // confirms the switch and the form
        next();
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        throw new IllegalArgumentException("Unknown command: " + command);
    }

    @Override
    public synchronized void onEvent(String event, Map<String, Object> payload) {
        if (host == null) return;
        if ("switch.receiver".equals(event)) {
            Object on = payload.get("on");
            if (!(on instanceof Boolean)) throw new IllegalArgumentException("The receiver switch needs a boolean");
            Map<String, Object> next = new LinkedHashMap<>(settings);
            next.put("receiver_enabled", on);
            configure(next);
            host.saveSettings(next); // the form shows it too
        } else if ("shizuku.state".equals(event)) {
            poll();
        }
    }

    @Override
    public synchronized void stop() {
        if (worker != null) worker.shutdownNow();
        worker = null;
        host = null;
        queue.clear();
        busy = false;
    }

    synchronized void poll() {
        if (host == null || !shizukuGranted()) return;
        if (!busy && queue.isEmpty()) queue.add(status());
        next();
    }

    private void next() {
        if (host == null || busy || queue.isEmpty()) return;
        final String[] command = queue.poll();
        final PluginHost owner = host;
        busy = true;
        try {
            owner.executeShizuku(command, TIMEOUT_MS, (ok, data, error) -> {
                synchronized (ShairportPlugin.this) {
                    if (host != owner) return;
                    busy = false;
                    handle(command, ok, data, error);
                    if (worker == null) next();
                    else worker.schedule(this::nextLater, SPACING_MS, TimeUnit.MILLISECONDS);
                }
            });
        } catch (RuntimeException error) {
            busy = false;
            queue.clear();
            host.status("Shizuku command failed: " + error.getMessage(), true);
        }
    }

    private synchronized void nextLater() {
        next();
    }

    private void handle(String[] command, boolean ok, Object data, String error) {
        Map<?, ?> result = data instanceof Map ? (Map<?, ?>) data : null;
        String stdout = result == null ? "" : String.valueOf(result.get("stdout"));
        boolean ran = ok && result != null && !Boolean.TRUE.equals(result.get("timedOut"))
            && result.get("exitCode") instanceof Number && ((Number) result.get("exitCode")).intValue() == 0;
        if (!ran) {
            queue.clear();
            host.status("Could not reach the Shairport app: " + (error != null ? error : compact(result)), true);
            return;
        }
        if (!"broadcast".equals(command[1])) return; // start-foreground-service
        Matcher answer = RESULT.matcher(stdout);
        boolean answered = answer.find();
        String answerData = answered && answer.group(2) != null ? answer.group(2) : "";
        if (!answered || !"-1".equals(answer.group(1))) { // -1: RESULT_OK
            queue.clear();
            host.status(answerData.isEmpty() ? "The Shairport app (" + APP + ") is not installed or did not answer." : answerData, true);
        } else if (command[command.length - 1].equals(APP + ".GET_STATUS")) {
            apply(query(answerData));
        }
    }

    /** Entities and form from a GET_STATUS answer. */
    private void apply(Map<String, String> app) {
        String state = app.get("state");
        running = state != null && !"off".equals(state);
        // On = enabled and running, so Home Assistant can also start an enabled receiver that is down.
        host.publishSwitch("receiver", "AirPlay receiver", running && "true".equals(app.get("receiver_enabled")));
        host.publishTextSensor("state", "State", state);
        host.publishTextSensor("source", "Source", clip(app.get("source")));
        host.publishTextSensor("title", "Title", clip(app.get("title")));
        host.publishTextSensor("artist", "Artist", clip(app.get("artist")));
        host.publishTextSensor("album", "Album", clip(app.get("album")));
        host.publishSensor("volume", "Volume", VOLUME, number(app.get("volume")));
        host.status(summary(app), false);
        // Changes made in the app (or over adb) show up in the form, unless ours are still queued.
        if (!queue.isEmpty()) return;
        Map<String, Object> read = new LinkedHashMap<>(settings);
        for (String key : settings.keySet()) {
            String value = app.get(key);
            Map<String, String> labels = LABELS.get(key);
            if (value == null) continue;
            if (settings.get(key) instanceof Boolean) read.put(key, Boolean.valueOf(value));
            else if (labels == null) read.put(key, value);
            else if (labels.containsKey(value)) read.put(key, labels.get(value));
        }
        if (read.equals(settings)) return;
        settings = read;
        try {
            host.saveSettings(new LinkedHashMap<>(read));
        } catch (RuntimeException invalid) {
            host.status("Could not show the app's settings: " + invalid.getMessage(), true);
        }
    }

    static String summary(Map<String, String> app) {
        String satellites = "";
        if ("true".equals(app.get("satellites"))) {
            String count = app.getOrDefault("satellites_count", "0");
            satellites = " · " + count + ("1".equals(count) ? " satellite connected" : " satellites connected");
        } else if (app.containsKey("satellites")) {
            satellites = " · Satellites off";
        }
        String name = app.get("server_name");
        if ("playing".equals(app.get("state"))) {
            String track = join(" - ", app.get("artist"), app.get("title"));
            return "Playing from " + app.get("source") + (track.isEmpty() ? "" : ": " + track) + satellites;
        }
        if ("idle".equals(app.get("state"))) {
            return "Waiting for AirPlay as \"" + name + "\"" + (app.containsKey("address") ? " on " + app.get("address") : "")
                + ("airplay2".equals(app.get("mode")) ? " (AirPlay 2)" : "classic".equals(app.get("mode")) ? " (classic AirPlay)" : "") + satellites;
        }
        return ("true".equals(app.get("receiver_enabled")) ? "Receiver not running" : "Receiver off") + satellites;
    }

    static String[] status() {
        return broadcast("GET_STATUS");
    }

    static String[] set(String key, Object value) {
        // Arguments go to am as they are (no shell), so names need no quoting.
        return broadcast("CONFIGURE_SETTINGS", "--es", "key", key,
            value instanceof Boolean ? "--ez" : "--es", "value", String.valueOf(value));
    }

    private static String[] broadcast(String action, String... extras) {
        List<String> command = new ArrayList<>(Arrays.asList(AM, "broadcast", "--include-stopped-packages",
            "-n", APP + "/.SettingsReceiver"));
        command.addAll(Arrays.asList(extras));
        command.add("-a");
        command.add(APP + "." + action);
        return command.toArray(new String[0]);
    }

    /** GET_STATUS answers key=value pairs, URL-encoded (no JSON parser needed here or in tests). */
    static Map<String, String> query(String encoded) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : encoded.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) values.put(decode(pair.substring(0, equals)), decode(pair.substring(equals + 1)));
        }
        return values;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private boolean shizukuGranted() {
        boolean granted;
        try {
            granted = Boolean.TRUE.equals(host.shizukuState().get("granted"));
        } catch (RuntimeException unavailable) {
            granted = false;
        }
        if (!granted) host.status("Needs Shizuku: start it and allow Kiosk Satellite (Settings > Device > Shizuku).", true);
        return granted;
    }

    private static Double number(String value) {
        try {
            return value == null ? null : Double.valueOf(value);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static String clip(String value) {
        return value == null || value.length() <= 512 ? value : value.substring(0, 512);
    }

    private static String join(String separator, String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            if (joined.length() > 0) joined.append(separator);
            joined.append(part);
        }
        return joined.toString();
    }

    private static String compact(Map<?, ?> result) {
        if (result == null) return "no answer";
        Object stderr = result.get("stderr");
        Object stdout = result.get("stdout");
        String output = join(" ", stderr == null ? null : stderr.toString(), stdout == null ? null : stdout.toString())
            .replaceAll("\\s+", " ").trim();
        return output.length() <= 300 ? output : output.substring(0, 300) + "...";
    }
}
