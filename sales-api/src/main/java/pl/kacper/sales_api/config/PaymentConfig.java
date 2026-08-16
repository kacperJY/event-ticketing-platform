package pl.kacper.sales_api.config;

import com.stripe.StripeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentConfig {
    @Value("${stripe.secret-key}")
    private String secretKey;

    @Bean
    public StripeClient stripeClient(){
        return new StripeClient(secretKey);
    }

}
