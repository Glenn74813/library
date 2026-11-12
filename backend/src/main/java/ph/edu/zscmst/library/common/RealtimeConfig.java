package ph.edu.zscmst.library.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Live updates for kiosk screens over STOMP on a WebSocket at /api/ws (under /api so the same
 * proxies that carry the API carry it too). Only the server publishes, and only public content
 * (announcements), so screens may subscribe without signing in; clients cannot send messages.
 */
@Configuration
@EnableWebSocketMessageBroker
public class RealtimeConfig implements WebSocketMessageBrokerConfigurer {

    public static final String ANNOUNCEMENTS = "/topic/announcements";

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        ThreadPoolTaskScheduler heartbeat = new ThreadPoolTaskScheduler();
        heartbeat.setPoolSize(1);
        heartbeat.setThreadNamePrefix("ws-heartbeat-");
        heartbeat.initialize();
        // Heartbeats keep proxies (nginx, Cloudflare) from closing an idle connection.
        registry.enableSimpleBroker("/topic").setHeartbeatValue(new long[] {10000, 10000}).setTaskScheduler(heartbeat);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Public, read-only announcements and no cookies: any origin may listen (kiosks reach the
        // server through nginx, the Cloudflare Worker or directly on the library network).
        registry.addEndpoint("/api/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
                if (StompCommand.SEND.equals(accessor.getCommand())) {
                    throw new MessagingException("Clients may not publish messages");
                }
                if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())
                        && !ANNOUNCEMENTS.equals(accessor.getDestination())) {
                    throw new MessagingException("Subscription not allowed");
                }
                return message;
            }
        });
    }
}
