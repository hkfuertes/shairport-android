// SPDX-License-Identifier: Apache-2.0
package com.hkfuertes.shairport.kiosk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.PluginHost;

/** Runs the plugin against a fake Kiosk Satellite: java -ea (see the Dockerfile's plugin stage). */
public final class ShairportPluginTest {
    static final String STATUS_OK = "Broadcasting: Intent { act=com.hkfuertes.shairport.GET_STATUS }\n"
        + "Broadcast completed: result=-1, data=\"state=playing&volume=40&source=iPhone%20de%20Ana&title=Song&artist=A%26B"
        + "&address=192.168.1.5&mode=airplay2&receiver_enabled=true&airplay_2=true&server_name=Kitchen"
        + "&model=AudioAccessory5%2C1&start_at_boot=false&playback_mode=stereo&link_stream_volume=true\"\n";

    static final class Host implements PluginHost {
        boolean granted = true;
        final List<String[]> commands = new ArrayList<>();
        final ArrayDeque<CommandCallback> pending = new ArrayDeque<>();
        final Map<String, Object> entities = new LinkedHashMap<>();
        Map<String, Object> saved;
        String status;
        boolean statusError;

        @Override public Map<String, Object> shizukuState() {
            return Collections.<String, Object>singletonMap("granted", granted);
        }
        @Override public void executeShizuku(String[] command, int timeoutMs, CommandCallback callback) {
            assert pending.isEmpty() : "one Shizuku command at a time";
            commands.add(command);
            pending.add(callback);
        }
        /** Completes the pending command with this am output. */
        void answer(String stdout) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exitCode", 0);
            result.put("timedOut", false);
            result.put("stdout", stdout);
            result.put("stderr", "");
            pending.remove().onResult(true, result, null);
        }
        String last() { return String.join(" ", commands.get(commands.size() - 1)); }
        @Override public void publishSwitch(String key, String name, boolean state) { entities.put(key, state); }
        @Override public void publishTextSensor(String key, String name, String state) { entities.put(key, state); }
        @Override public void publishSensor(String key, String name, Map<String, Object> metadata, Double state) { entities.put(key, state); }
        @Override public void saveSettings(Map<String, Object> values) { saved = new LinkedHashMap<>(values); }
        @Override public void status(String message, boolean error) { status = message; statusError = error; }
        @Override public void showWindow(String title, String message, String buttonLabel) {}
        @Override public void hideWindow() {}
        @Override public void log(String message) {}
    }

    static Map<String, Object> form(boolean enabled, String name, String model) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("receiver_enabled", enabled);
        settings.put("airplay_2", true);
        settings.put("server_name", name);
        settings.put("model", model);
        settings.put("playback_mode", "Stereo");
        settings.put("start_at_boot", false);
        settings.put("link_stream_volume", true);
        settings.put("satellites", false);
        return settings;
    }

    public static void main(String[] args) {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions) throw new AssertionError("run with java -ea");
        statusBecomesEntitiesAndForm();
        automaticWifiAndRootRefusal();
        satellitesToggleAndCount();
        formEditsReachTheApp();
        queuedEditsWinOverAnOlderStatus();
        switchStartsAnEnabledReceiverThatIsDown();
        switchAndErrors();
        System.out.println("ShairportPluginTest: OK");
    }

    static void statusBecomesEntitiesAndForm() {
        Host host = new Host();
        ShairportPlugin plugin = new ShairportPlugin();
        plugin.attach(host, form(true, "", "HomePod"));
        plugin.poll();
        assert host.last().equals("/system/bin/am broadcast --include-stopped-packages -n com.hkfuertes.shairport/.SettingsReceiver"
            + " -a com.hkfuertes.shairport.GET_STATUS") : host.last();
        host.answer(STATUS_OK);
        assert Boolean.TRUE.equals(host.entities.get("receiver"));
        assert "playing".equals(host.entities.get("state"));
        assert "iPhone de Ana".equals(host.entities.get("source"));
        assert "A&B".equals(host.entities.get("artist"));
        assert host.entities.containsKey("album") && host.entities.get("album") == null;
        assert Double.valueOf(40).equals(host.entities.get("volume"));
        assert "Playing from iPhone de Ana: A&B - Song".equals(host.status) : host.status;
        // The app's values (model value -> label) replace the stale form.
        assert form(true, "Kitchen", "HomePod mini").equals(host.saved) : host.saved;
        host.saved = null;
        plugin.poll();
        host.answer(STATUS_OK);
        assert host.saved == null : "unchanged settings are not saved again";
    }

    static void automaticWifiAndRootRefusal() {
        Host host = new Host();
        ShairportPlugin plugin = new ShairportPlugin();
        Map<String, Object> settings = form(true, "Kitchen", "HomePod mini");
        settings.put("satellites", true);
        settings.put("wifi_low_latency", true); // saved by the previous plugin
        plugin.attach(host, settings);
        String status = STATUS_OK.replace("link_stream_volume=true", "link_stream_volume=true&satellites=true");
        plugin.poll();
        host.answer(status);
        assert host.saved == null : "discarding the old toggle needs no app configuration";
        settings.put("wifi_low_latency", false);
        plugin.configure(settings);
        assert host.pending.isEmpty() : "the removed toggle must never reach the app";

        plugin.poll();
        host.answer(status.replace("airplay_2=true", "airplay_2=false")); // root refused
        assert Boolean.FALSE.equals(host.saved.get("airplay_2")) : host.saved;
        assert !host.saved.containsKey("wifi_low_latency") : host.saved;
        assert Boolean.TRUE.equals(host.saved.get("satellites")) : host.saved;
        assert Boolean.TRUE.equals(host.saved.get("link_stream_volume")) : host.saved;
        assert host.pending.isEmpty() : "root refusal must not retry or disable unrelated settings";
    }

    static void satellitesToggleAndCount() {
        Host host = new Host();
        ShairportPlugin plugin = new ShairportPlugin();
        plugin.attach(host, form(true, "Kitchen", "HomePod mini"));
        String status = STATUS_OK.replace("link_stream_volume=true",
            "link_stream_volume=true&satellites=true&satellites_count=2");
        plugin.poll();
        host.answer(status);
        assert Boolean.TRUE.equals(host.saved.get("satellites"));
        assert host.status.endsWith("2 satellites connected") : host.status;
        host.saved = null;
        for (String count : Arrays.asList("1", "0")) {
            plugin.poll();
            host.answer(status.replace("satellites_count=2", "satellites_count=" + count));
            assert host.status.endsWith(count + ("1".equals(count) ? " satellite connected" : " satellites connected")) : host.status;
            assert host.saved == null : "the count is status, not a form setting";
        }
        Map<String, Object> next = form(true, "Kitchen", "HomePod mini");
        plugin.configure(next);
        assert host.last().contains("--es key satellites --ez value false") : host.last();
        host.answer("Broadcast completed: result=-1, data=\"satellites\"");
        assert host.last().endsWith("GET_STATUS");
        host.answer(status.replace("satellites=true", "satellites=false").replace("satellites_count=2", "satellites_count=0"));
        assert host.status.endsWith("Satellites off") : host.status;
        next.put("satellites", true);
        plugin.configure(next);
        assert host.last().contains("--es key satellites --ez value true") : host.last();
        host.answer("Broadcast completed: result=-1, data=\"satellites\"");
        host.answer(status.replace("satellites_count=2", "satellites_count=0"));
        assert host.status.endsWith("0 satellites connected") : host.status;
        for (String state : Arrays.asList("idle", "off")) {
            assert ShairportPlugin.summary(ShairportPlugin.query("state=" + state + "&satellites=true&satellites_count=2"))
                .endsWith("2 satellites connected") : state;
        }
    }

    static void formEditsReachTheApp() {
        Host host = new Host();
        ShairportPlugin plugin = new ShairportPlugin();
        plugin.attach(host, form(false, "Kitchen", "HomePod"));
        Map<String, Object> next = form(true, "Salón de \"casa\"", "HomePod");
        next.put("link_stream_volume", false);
        plugin.configure(next);
        assert host.commands.size() == 1 && host.last().equals("/system/bin/am broadcast --include-stopped-packages"
            + " -n com.hkfuertes.shairport/.SettingsReceiver --es key receiver_enabled --ez value true"
            + " -a com.hkfuertes.shairport.CONFIGURE_SETTINGS") : host.last();
        List<String> sent = new ArrayList<>();
        while (!host.pending.isEmpty()) {
            sent.add(host.last());
            host.answer(host.last().contains("GET_STATUS") ? STATUS_OK.replace("link_stream_volume=true", "link_stream_volume=false")
                : host.last().contains("start-foreground-service") ? "Starting service: Intent { }"
                : "Broadcast completed: result=-1, data=\"server_name\"");
        }
        assert sent.size() == 5 : sent;
        assert sent.get(0).contains("--es key receiver_enabled --ez value true") : sent;
        assert sent.get(1).contains("--es key server_name --es value Salón de \"casa\"") : sent;
        assert sent.get(2).contains("--es key link_stream_volume --ez value false") : sent;
        assert sent.get(3).equals("/system/bin/am start-foreground-service -n com.hkfuertes.shairport/.ReceiverService") : sent;
        assert sent.get(4).endsWith("GET_STATUS") : sent;
        // Same form again (KS re-submits on every save): nothing to send.
        int before = host.commands.size();
        plugin.configure(host.saved);
        assert host.commands.size() == before;
    }

    static void queuedEditsWinOverAnOlderStatus() {
        Host host = new Host();
        ShairportPlugin plugin = new ShairportPlugin();
        plugin.attach(host, form(true, "Kitchen", "HomePod mini"));
        plugin.poll(); // GET_STATUS in flight...
        plugin.configure(form(true, "Salón", "HomePod mini")); // ...when the user renames
        host.answer(STATUS_OK); // still "Kitchen": must not overwrite the edit
        assert host.saved == null : host.saved;
        assert host.last().contains("--es key server_name --es value Salón");
    }

    static void switchStartsAnEnabledReceiverThatIsDown() {
        Host host = new Host();
        ShairportPlugin plugin = new ShairportPlugin();
        plugin.attach(host, form(true, "Kitchen", "HomePod mini"));
        plugin.poll();
        host.answer(STATUS_OK.replace("state=playing", "state=off"));
        assert Boolean.FALSE.equals(host.entities.get("receiver")) : "enabled but not running";
        assert "Receiver not running".equals(host.status) : host.status;
        plugin.onEvent("switch.receiver", Collections.<String, Object>singletonMap("on", true));
        assert host.last().equals("/system/bin/am start-foreground-service -n com.hkfuertes.shairport/.ReceiverService")
            : "no setting changed, but the service must start: " + host.last();
        host.answer("Starting service: Intent { cmp=com.hkfuertes.shairport/.ReceiverService }");
        assert host.last().endsWith("GET_STATUS");
        host.answer(STATUS_OK);
        assert Boolean.TRUE.equals(host.entities.get("receiver"));
    }

    static void switchAndErrors() {
        Host host = new Host();
        ShairportPlugin plugin = new ShairportPlugin();
        plugin.attach(host, form(true, "Kitchen", "HomePod mini"));
        plugin.onEvent("switch.receiver", Collections.<String, Object>singletonMap("on", false));
        assert host.last().contains("--es key receiver_enabled --ez value false");
        assert Boolean.FALSE.equals(host.saved.get("receiver_enabled"));
        assert !host.entities.containsKey("receiver") : "confirmed by the app's status only";
        host.answer("Broadcast completed: result=0, data=\"Settings rejected: unknown setting: x\"");
        assert host.statusError && host.status.equals("Settings rejected: unknown setting: x") : host.status;
        assert host.pending.isEmpty() : "the rest of the queue is dropped";

        plugin.poll();
        host.answer("Broadcast completed: result=0");
        assert host.statusError && host.status.contains("not installed") : host.status;

        host.granted = false;
        int before = host.commands.size();
        plugin.poll();
        assert host.commands.size() == before && host.statusError && host.status.contains("Shizuku");

        assert ShairportPlugin.query("a=1&b=x%20y%2Bz&c=").equals(new LinkedHashMap<String, String>() {{
            put("a", "1"); put("b", "x y+z"); put("c", "");
        }});
        assert Arrays.asList(ShairportPlugin.set("start_at_boot", true)).contains("--ez");
        Map<String, String> idle = ShairportPlugin.query("state=idle&server_name=Kitchen&address=10.0.0.2&mode=airplay2");
        assert ShairportPlugin.summary(idle).equals("Waiting for AirPlay as \"Kitchen\" on 10.0.0.2 (AirPlay 2)")
            : ShairportPlugin.summary(idle);
    }
}
