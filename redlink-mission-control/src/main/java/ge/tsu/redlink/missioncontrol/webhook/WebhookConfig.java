package ge.tsu.redlink.missioncontrol.webhook;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;

@Configuration(proxyBeanMethods = false)
class WebhookConfig {

    /** Real DNS; tests of the policy itself use a fake resolver. */
    @Bean
    WebhookUrlPolicy webhookUrlPolicy(WebhookProperties properties) {
        return new WebhookUrlPolicy(properties.allowHttp(), properties.allowPrivateHosts(), InetAddress::getAllByName);
    }
}
