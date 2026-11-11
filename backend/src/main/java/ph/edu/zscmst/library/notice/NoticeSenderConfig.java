package ph.edu.zscmst.library.notice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class NoticeSenderConfig {

    private static final Logger log = LoggerFactory.getLogger(NoticeSenderConfig.class);

    @Bean
    @ConditionalOnMissingBean(NoticeSender.class)
    NoticeSender loggingNoticeSender() {
        return notice -> {
            log.info("[notice:{}] to {} <{}> {}", notice.getType(), notice.getPatron().getFullName(),
                    notice.getPatron().getEmail(), notice.getMessage());
            return true;
        };
    }
}
