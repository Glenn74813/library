package ph.edu.zscmst.library.sip2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * SIP2 (Standard Interchange Protocol 2.00) TCP server for self-check kiosks, RFID stations and
 * book drops. Each connection logs in with a staff or kiosk account (message 93) and then sends
 * requests; replies end with a carriage return.
 */
@Component
public class SipServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SipServer.class);

    private final SipHandler handler;
    private final boolean enabled;
    private final int port;
    private final int idleTimeoutSeconds;
    private ServerSocket server;
    private ExecutorService workers;
    private volatile boolean running;

    public SipServer(SipHandler handler, @Value("${app.sip2.enabled:true}") boolean enabled,
                     @Value("${app.sip2.port:6001}") int port,
                     @Value("${app.sip2.idle-timeout-seconds:600}") int idleTimeoutSeconds) {
        this.handler = handler;
        this.enabled = enabled;
        this.port = port;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
    }

    @Override
    public void start() {
        if (!enabled) return;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            log.error("SIP2 server could not listen on port {}: {}", port, e.getMessage());
            return;
        }
        workers = Executors.newVirtualThreadPerTaskExecutor();
        running = true;
        Thread.ofVirtual().name("sip2-accept").start(this::acceptLoop);
        log.info("SIP2 server listening on port {}", port);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                workers.submit(() -> serve(socket));
            } catch (IOException e) {
                if (running) log.warn("SIP2 accept failed: {}", e.getMessage());
            }
        }
    }

    private void serve(Socket socket) {
        String peer = socket.getRemoteSocketAddress().toString();
        SipHandler.Session session = new SipHandler.Session(peer);
        try (socket; BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = socket.getOutputStream()) {
            socket.setSoTimeout(idleTimeoutSeconds * 1000);
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\r' || c == '\n') {
                    if (line.isEmpty()) continue;
                    String reply = handler.handle(line.toString(), session);
                    line.setLength(0);
                    if (reply != null) {
                        out.write((reply + "\r").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    if (session.closed()) break;
                } else if (line.length() < 4096) {
                    line.append((char) c);
                }
            }
        } catch (SocketTimeoutException e) {
            log.info("SIP2 {} idle, closing", peer);
        } catch (IOException e) {
            log.debug("SIP2 {} disconnected: {}", peer, e.getMessage());
        }
    }

    @Override
    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        if (workers != null) workers.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
