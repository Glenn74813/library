import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ZSCMST Smart Library RFID bridge: connects any RFID reader or security gate to the library system.
 *
 * Runs on the PC next to the reader (needs only Java 21 or later, no other software):
 *     java RfidBridge.java [rfid-bridge.properties] [--key=value ...]
 *
 * READING tags works with any reader, chosen with reader= :
 *   simulator   type "place EPC" / "remove EPC" in this window (testing without hardware)
 *   serial      a serial / USB-COM reader that sends tag numbers as text (serial.device=COM3 or /dev/ttyUSB0)
 *   tcp         a network reader that sends tag numbers as text (tcp.host=, tcp.port=)
 *   exec        any vendor command-line tool that prints tag numbers (exec.inventory=)
 *   push        any reader software that sends reads to this bridge: POST /push {"epcs":["..."]}
 *   auto        all of the above that are available, at once: every serial/USB-COM port found (or serial.device),
 *               tcp.host if set, exec.inventory if set, and push. Whichever sends tags is used. Several can also be
 *               listed, e.g. reader=serial,tcp
 * Tag numbers are taken from each line with epc.pattern (hex EPCs by default). Keyboard-wedge readers need no
 * bridge at all: they type into the library pages directly.
 *
 * WRITING tags and switching their security (EAS / AFI) on and off, chosen with writer= :
 *   simulator   kept in memory
 *   exec        the reader vendor's command-line tool or SDK sample, with {epc} {barcode} {on} {secure} filled in
 *   none        reading only
 *
 * mode=station (circulation desk, self-check kiosk): the library pages call this bridge on http://127.0.0.1:8765
 *   GET /status, GET /tags, POST /write {"barcode"}, POST /security {"epc","barcode","secure":true|false}, POST /push
 * mode=gate (security gate PC): every tag read is checked with the library system (lms.url, and in lms.deviceKey
 *   the gate's device token from Users & devices). An unborrowed item runs gate.alarm; visitors, tamper and power events can be pushed to
 *   POST /event {"type":"VISITOR","visitorCount":1}; a heartbeat every minute shows the gate online.
 */
public final class RfidBridge {

    static final Properties cfg = new Properties();
    /** Tags seen recently: EPC -> last time seen (ms). */
    static final Map<String, Long> field = new ConcurrentHashMap<>();
    /** Simulator tag memory: EPC -> barcode and security state. */
    static final Map<String, String[]> simTags = new ConcurrentHashMap<>();
    static Pattern epcPattern;

    public static void main(String[] args) throws Exception {
        String file = "rfid-bridge.properties";
        for (String a : args) {
            if (a.startsWith("--") && a.contains("=")) cfg.setProperty(a.substring(2, a.indexOf('=')), a.substring(a.indexOf('=') + 1));
            else file = a;
        }
        if (Files.exists(Path.of(file))) {
            Properties fromFile = new Properties();
            try (var in = Files.newBufferedReader(Path.of(file))) {
                fromFile.load(in);
            }
            fromFile.forEach((k, v) -> cfg.putIfAbsent(k, v));
            log("Settings from " + Path.of(file).toAbsolutePath());
        }
        epcPattern = Pattern.compile(get("epc.pattern", "\\b([0-9A-Fa-f]{16,32})\\b"));
        String reader = get("reader", "simulator");
        String mode = get("mode", "station");
        log("Mode " + mode + ", reader " + reader + ", writer " + get("writer", reader.equals("simulator") ? "simulator" : "none"));

        startReader(reader);
        startHttp();
        if (mode.equals("gate")) gateLoop();
        else Thread.currentThread().join();
    }

    // ------------------------------------------------------------------ reading

    /** Reader connections that are delivering data now (for /status and the log). */
    static final Set<String> connected = ConcurrentHashMap.newKeySet();

    static void startReader(String reader) {
        if (reader.contains(",")) {
            for (String r : reader.split(",")) startReader(r.trim());
            return;
        }
        switch (reader) {
            case "simulator" -> daemon(RfidBridge::simulatorConsole);
            case "serial" -> serial(get("serial.device", "COM3"), false);
            case "tcp" -> daemon(() -> reconnecting("tcp " + get("tcp.host", "") + ":" + get("tcp.port", "6000"), false, RfidBridge::openTcp));
            case "exec" -> daemon(() -> reconnecting("command " + get("exec.inventory", ""), false, RfidBridge::openExec));
            case "push" -> log("Waiting for reads on POST /push");
            case "auto" -> {
                // Try every kind of reader at once and keep whichever answers; missing ones are retried quietly.
                String dev = get("serial.device", "");
                List<String> ports = dev.isEmpty() ? serialPorts() : List.of(dev);
                for (String port : ports) serial(port, dev.isEmpty());
                if (!get("tcp.host", "").isEmpty()) daemon(() -> reconnecting("tcp " + get("tcp.host", "") + ":" + get("tcp.port", "6000"), true, RfidBridge::openTcp));
                if (!get("exec.inventory", "").isEmpty()) daemon(() -> reconnecting("command " + get("exec.inventory", ""), true, RfidBridge::openExec));
                log("Auto reader: watching " + (dev.isEmpty() ? "serial ports " + String.join(" ", ports) : "serial " + dev)
                        + (get("tcp.host", "").isEmpty() ? "" : ", tcp " + get("tcp.host", ""))
                        + (get("exec.inventory", "").isEmpty() ? "" : ", command") + ", and POST /push");
            }
            default -> throw new IllegalArgumentException("Unknown reader=" + reader);
        }
    }

    static void serial(String dev, boolean quiet) {
        daemon(() -> reconnecting("serial " + dev, quiet, () -> new FileInputStream(serialPath(dev))));
    }

    /** Serial / USB-COM ports a reader may be on: COM1-COM32 on Windows, USB serial devices elsewhere. */
    static List<String> serialPorts() {
        List<String> ports = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            for (int i = 1; i <= 32; i++) ports.add("COM" + i);
        } else {
            for (String prefix : List.of("/dev/ttyUSB", "/dev/ttyACM")) for (int i = 0; i < 8; i++) ports.add(prefix + i);
        }
        return ports;
    }

    static InputStream openTcp() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(get("tcp.host", ""), Integer.parseInt(get("tcp.port", "6000"))), 5000);
        String hello = get("tcp.start", "");
        if (!hello.isEmpty()) s.getOutputStream().write((unescape(hello)).getBytes(StandardCharsets.UTF_8));
        return s.getInputStream();
    }

    static InputStream openExec() throws IOException {
        return new ProcessBuilder(split(get("exec.inventory", ""))).redirectErrorStream(true).start().getInputStream();
    }

    interface Opener {
        InputStream open() throws IOException;
    }

    /**
     * Reads lines from a reader connection, extracting tag numbers; reconnects when the connection drops.
     * Quiet (auto reader): a source that isn't there is retried without filling the log.
     */
    static void reconnecting(String name, boolean quiet, Opener opener) {
        while (true) {
            try (InputStream in = opener.open(); var r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
                log("Connected to " + name);
                connected.add(name);
                for (String line; (line = r.readLine()) != null; ) seen(line);
                log("Disconnected from " + name);
            } catch (Exception e) {
                if (!quiet || connected.contains(name)) log("Cannot read " + name + ": " + e.getMessage());
            }
            connected.remove(name);
            sleep(quiet ? 10000 : 3000);
        }
    }

    /** Takes every tag number found in a line of reader output. */
    static void seen(String line) {
        Matcher m = epcPattern.matcher(line);
        while (m.find()) {
            String epc = (m.groupCount() > 0 ? m.group(1) : m.group()).toUpperCase();
            if (field.put(epc, System.currentTimeMillis()) == null && debug()) log("Read " + epc);
        }
    }

    /** Tags read within the last field.seconds (default 2). */
    static List<String> inField() {
        long since = System.currentTimeMillis() - (long) (Double.parseDouble(get("field.seconds", "2")) * 1000);
        List<String> out = new ArrayList<>();
        field.forEach((epc, t) -> {
            if (t >= since) out.add(epc);
        });
        field.entrySet().removeIf(e -> e.getValue() < since - 60_000);
        return out;
    }

    static void simulatorConsole() {
        log("Simulator: type  place EPC  |  remove EPC  |  list  (tags placed stay on the pad until removed)");
        try (var r = new BufferedReader(new InputStreamReader(System.in))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] w = line.trim().split("\\s+");
                if (w.length == 2 && w[0].equals("place")) {
                    String epc = w[1].toUpperCase();
                    simTags.putIfAbsent(epc, new String[] {"", "true"});
                    log("Placed " + epc);
                } else if (w.length == 2 && w[0].equals("remove")) {
                    simTags.remove(w[1].toUpperCase());
                    field.remove(w[1].toUpperCase());
                } else if (w[0].equals("list")) {
                    simTags.forEach((k, v) -> log(k + "  barcode=" + v[0] + "  security=" + (v[1].equals("true") ? "ON" : "OFF")));
                } else if (!line.isBlank()) {
                    seen(line); // a gate test: any EPC typed in is one read
                }
            }
        } catch (IOException ignored) {
            // console closed: keep running as a service
        }
    }

    // ------------------------------------------------------------------ writing

    /** Writes a copy's tag: the EPC is the barcode in hex (ASCII), padded to 96 or 128 bits. Returns the EPC. */
    static String write(String barcode) throws Exception {
        String epc = epcFor(barcode);
        switch (writer()) {
            case "simulator" -> {
                List<String> tags = simulatorPad();
                if (tags.size() != 1) throw new IllegalStateException("Put exactly one tag on the pad (found " + tags.size() + ")");
                simTags.remove(tags.getFirst());
                simTags.put(epc, new String[] {barcode, "true"});
                field.remove(tags.getFirst());
            }
            case "exec" -> run(get("exec.write", ""), Map.of("epc", epc, "barcode", barcode));
            default -> throw new IllegalStateException("This reader is set up for reading only (writer=none)");
        }
        log("Wrote tag " + epc + " for " + barcode);
        return epc;
    }

    /** Switches a tag's security (EAS / AFI): on when the copy is on the shelf, off when it is borrowed. */
    static void security(String epc, boolean secure) throws Exception {
        switch (writer()) {
            case "simulator" -> {
                String[] t = simTags.get(epc);
                if (t == null) throw new IllegalStateException("Tag " + epc + " is not on the pad");
                t[1] = String.valueOf(secure);
            }
            case "exec" -> run(get(secure ? "exec.securityOn" : "exec.securityOff", get("exec.security", "")),
                    Map.of("epc", epc, "secure", String.valueOf(secure), "on", secure ? "1" : "0"));
            default -> throw new IllegalStateException("This reader is set up for reading only (writer=none)");
        }
        log("Security " + (secure ? "ON" : "OFF") + " for " + epc);
    }

    static List<String> simulatorPad() {
        return new ArrayList<>(simTags.keySet());
    }

    static String writer() {
        return get("writer", get("reader", "simulator").equals("simulator") ? "simulator" : "none");
    }

    static String epcFor(String barcode) {
        StringBuilder hex = new StringBuilder();
        for (byte b : barcode.getBytes(StandardCharsets.US_ASCII)) hex.append(String.format("%02X", b));
        if (hex.length() > 32) throw new IllegalArgumentException("Barcode " + barcode + " is too long for a 128-bit tag");
        int bits = hex.length() > 24 ? 32 : 24;
        while (hex.length() < bits) hex.append('0');
        return hex.toString();
    }

    /** Runs a configured command with {placeholders} filled in; fails when it exits with an error. */
    static void run(String template, Map<String, String> values) throws Exception {
        if (template.isBlank()) throw new IllegalStateException("No command set up for this action");
        String cmd = template;
        for (var e : values.entrySet()) cmd = cmd.replace("{" + e.getKey() + "}", e.getValue());
        Process p = new ProcessBuilder(split(cmd)).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!p.waitFor(15, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("The reader command failed" + (out.isEmpty() ? "" : ": " + out));
        }
    }

    // ------------------------------------------------------------------ HTTP (for the library pages)

    static void startHttp() throws IOException {
        int port = Integer.parseInt(get("port", "8765"));
        HttpServer http = HttpServer.create(new InetSocketAddress(get("bind", "127.0.0.1"), port), 0);
        http.createContext("/", RfidBridge::handle);
        http.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        log("Listening on http://" + get("bind", "127.0.0.1") + ":" + port);
    }

    static void handle(HttpExchange x) throws IOException {
        // The library pages are on another origin: allow them, including Chrome's private-network check.
        var h = x.getResponseHeaders();
        String origin = x.getRequestHeaders().getFirst("Origin");
        String allowed = get("origins", "*");
        if (origin != null && (allowed.equals("*") || List.of(allowed.split(",")).contains(origin))) {
            h.set("Access-Control-Allow-Origin", origin);
            h.set("Vary", "Origin");
        }
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type");
        h.set("Access-Control-Allow-Private-Network", "true");
        if (x.getRequestMethod().equals("OPTIONS")) {
            x.sendResponseHeaders(204, -1);
            return;
        }
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            String path = x.getRequestURI().getPath();
            switch (path) {
                case "/status" -> reply(x, 200, "{\"ok\":true,\"mode\":" + q(get("mode", "station")) + ",\"reader\":" + q(get("reader", "simulator"))
                        + ",\"writer\":" + q(writer()) + ",\"connected\":" + arr(new ArrayList<>(connected)) + ",\"tags\":" + inField().size() + "}");
                case "/tags" -> {
                    List<String> tags = new ArrayList<>(inField());
                    if (get("reader", "simulator").equals("simulator")) tags = simulatorPad();
                    reply(x, 200, "{\"tags\":" + arr(tags) + "}");
                }
                case "/write" -> reply(x, 200, "{\"epc\":" + q(write(str(body, "barcode"))) + "}");
                case "/security" -> {
                    String epc = str(body, "epc");
                    if (epc == null || epc.isBlank()) throw new IllegalArgumentException("This copy has no RFID tag number");
                    security(epc.toUpperCase(), body.contains("\"secure\":true") || body.contains("\"secure\": true"));
                    reply(x, 200, "{\"ok\":true}");
                }
                case "/push" -> {
                    for (String epc : strs(body, "epcs")) seen(epc);
                    reply(x, 200, "{\"ok\":true}");
                }
                case "/event" -> {
                    lmsEvent(str(body, "type"), num(body, "visitorCount"), str(body, "message"));
                    reply(x, 200, "{\"ok\":true}");
                }
                default -> reply(x, 404, "{\"error\":\"Not found\"}");
            }
        } catch (Exception e) {
            reply(x, 409, "{\"error\":" + q(e.getMessage() == null ? e.toString() : e.getMessage()) + "}");
        }
    }

    static void reply(HttpExchange x, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length);
        try (OutputStream o = x.getResponseBody()) {
            o.write(b);
        }
    }

    // ------------------------------------------------------------------ gate

    static final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Checks tags passing the gate with the library system and sounds the alarm for unborrowed items. */
    static void gateLoop() {
        String gateId = get("gate.id", "GATE-1");
        long dedup = (long) (Double.parseDouble(get("gate.dedupSeconds", "5")) * 1000);
        Map<String, Long> checked = new ConcurrentHashMap<>();
        long nextBeat = 0;
        log("Gate " + gateId + " checking tags with " + get("lms.url", "(lms.url not set!)"));
        while (true) {
            long now = System.currentTimeMillis();
            if (now >= nextBeat) {
                lmsEvent("HEARTBEAT", null, null);
                nextBeat = now + 60_000;
            }
            List<String> fresh = new ArrayList<>();
            for (String epc : inField()) {
                Long last = checked.get(epc);
                if (last == null || now - last > dedup) {
                    fresh.add(epc);
                    checked.put(epc, now);
                }
            }
            if (!fresh.isEmpty()) {
                try {
                    String res = lms("/api/gate/device/check", "{\"gateId\":" + q(gateId) + ",\"epcs\":" + arr(fresh) + "}");
                    boolean alarm = res.contains("\"alarm\":true");
                    log((alarm ? "ALARM " : "ok    ") + res);
                    if (alarm && !get("gate.alarm", "").isBlank()) run(get("gate.alarm", ""), Map.of("epcs", String.join(",", fresh)));
                } catch (Exception e) {
                    log("Library system not reachable: " + e.getMessage() + " (tags " + fresh + ")");
                    if (get("gate.offlineAlarm", "false").equals("true") && !get("gate.alarm", "").isBlank()) {
                        try {
                            run(get("gate.alarm", ""), Map.of("epcs", String.join(",", fresh)));
                        } catch (Exception ignored) {
                            // the alarm command failing too is logged by run()
                        }
                    }
                }
            }
            sleep(300);
        }
    }

    static void lmsEvent(String type, Integer visitors, String message) {
        if (type == null || get("lms.url", "").isBlank()) return;
        try {
            lms("/api/gate/device/events", "{\"gateId\":" + q(get("gate.id", "GATE-1")) + ",\"type\":" + q(type.toUpperCase())
                    + (visitors == null ? "" : ",\"visitorCount\":" + visitors) + (message == null ? "" : ",\"message\":" + q(message)) + "}");
        } catch (Exception e) {
            log("Could not send " + type + " event: " + e.getMessage());
        }
    }

    static String lms(String path, String json) throws Exception {
        String base = get("lms.url", "").replaceAll("/+$", "");
        // A gate's device token (Users & devices) goes in Authorization; a device key (kd_...) in X-Device-Key.
        String cred = get("lms.deviceKey", "");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json");
        b = cred.startsWith("kd_") ? b.header("X-Device-Key", cred) : b.header("Authorization", "Bearer " + cred);
        HttpRequest req = b.POST(HttpRequest.BodyPublishers.ofString(json)).build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 300) throw new IOException("HTTP " + res.statusCode() + " " + res.body());
        return res.body();
    }

    // ------------------------------------------------------------------ small helpers

    static String get(String key, String def) {
        String v = cfg.getProperty(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    static boolean debug() {
        return get("debug", "false").equals("true");
    }

    static void daemon(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void log(String s) {
        System.out.println(LocalTime.now().withNano(0) + "  " + s);
    }

    /** On Windows, COM10 and up must be opened as \\.\COM10. */
    static String serialPath(String dev) {
        return dev.matches("(?i)COM\\d+") ? "\\\\.\\" + dev.toUpperCase() : dev;
    }

    static String unescape(String s) {
        return s.replace("\\r", "\r").replace("\\n", "\n");
    }

    /** Splits a command line on spaces, keeping "quoted parts" together. */
    static List<String> split(String cmd) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(cmd);
        while (m.find()) out.add(m.group(1) != null ? m.group(1) : m.group(2));
        return out;
    }

    static String q(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }

    static String arr(List<String> items) {
        return "[" + String.join(",", items.stream().map(RfidBridge::q).toList()) + "]";
    }

    static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    static Integer num(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    static List<String> strs(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)]").matcher(json);
        List<String> out = new ArrayList<>();
        if (m.find()) {
            Matcher v = Pattern.compile("\"([^\"]*)\"").matcher(m.group(1));
            while (v.find()) out.add(v.group(1));
        }
        return out;
    }
}
