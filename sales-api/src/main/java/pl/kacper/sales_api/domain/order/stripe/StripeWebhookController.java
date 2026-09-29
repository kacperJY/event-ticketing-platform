package pl.kacper.sales_api.domain.order.stripe;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class StripeWebhookController {

    private final StripeService stripeService;

    @Autowired
    public StripeWebhookController(StripeService stripeService) {
        this.stripeService = stripeService;
    }

    @PostMapping("/webhooks/stripe")
    public ResponseEntity<Void> handleWebhook(@RequestBody String rawBody, @RequestHeader("Stripe-Signature") String stripeSignature) {

        stripeService.handleWebhookEvent(stripeSignature, rawBody);

        return ResponseEntity.ok().build();
    }
}
