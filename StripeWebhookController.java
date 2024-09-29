package com.example.nagoyameshi.controller;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.nagoyameshi.service.MemberinfoService;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;

@RestController
public class StripeWebhookController {

    private static final Logger logger = Logger.getLogger(StripeWebhookController.class.getName());

    @Value("${stripe.api.secret}")
    private String stripeApiKey;

    @Value("${stripe.webhook.secret}")
    private String webhookSecret;

    private final MemberinfoService memberinfoService;

    public StripeWebhookController(MemberinfoService memberinfoService) {
        this.memberinfoService = memberinfoService;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        Stripe.apiKey = stripeApiKey;
    }

    @PostMapping("/stripe/webhook")
    public ResponseEntity<String> handleWebhook(@RequestBody String payload, @RequestHeader("Stripe-Signature") String sigHeader) {
        Event event;

        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Webhook error while validating signature.", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Webhook verification failed");
        }

        logger.info("Received Stripe WebHook: " + event.getType());
        logger.info("Received payload: " + payload);
        logger.info("Received Stripe-Signature header: " + sigHeader);

        switch (event.getType()) {
            case "checkout.session.completed":
                handleCheckoutSession(event);
                break;
            case "customer.subscription.deleted":
                handleSubscriptionDeleted(event);
                break;
            default:
                logger.warning("Unhandled event type: " + event.getType());
                break;
        }

        return ResponseEntity.ok("");
    }

    @GetMapping("/stripe/webhook")
    public ResponseEntity<String> handleGetWebhook() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body("This endpoint only supports POST requests.");
    }

    @PostMapping("/cancel_subscription")
    public ResponseEntity<String> cancelSubscription(@RequestParam String email) {
        logger.info("Received cancel subscription request for email: " + email);
        
        if (email == null || email.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Email is required");
        }

        try {
            // 顧客のリストを取得
            List<Customer> customers = Customer.list(Map.of("email", email)).getData();
            if (customers.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Customer not found");
            }

            Customer customer = customers.get(0);

            // サブスクリプションの取得
            List<Subscription> subscriptions = Subscription.list(Map.of("customer", customer.getId(), "status", "active")).getData();
            if (subscriptions.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Active subscription not found");
            }

            Subscription subscription = subscriptions.get(0);
            subscription.cancel();

            // ロール変更（MemberinfoServiceを利用）
            boolean userUpdated = memberinfoService.changeUserRole(email, "ROLE_GENERAL");
            if (!userUpdated) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to update user role");
            }

            return ResponseEntity.ok("Subscription cancelled and user role updated");
        } catch (StripeException e) {
            logger.log(Level.SEVERE, "Stripe API error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("An error occurred while cancelling subscription");
        }
    }


    private void handleCheckoutSession(Event event) {
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (dataObjectDeserializer.getObject().isPresent()) {
            Session session = (Session) dataObjectDeserializer.getObject().get();
            String customerId = session.getCustomer();
            
            // メタデータからゲストチェックアウトフラグを取得
            boolean isGuestCheckout = session.getMetadata().getOrDefault("guest_checkout", "false").equals("true");

            if (isGuestCheckout) {
                logger.info("Guest checkout completed. Session ID: " + session.getId());
                // 必要であれば他のゲスト専用の処理を追加
            } else {
                try {
                    // 顧客オブジェクトの取得
                    Customer customer = Customer.retrieve(customerId);
                    String email = customer.getEmail();
                    logger.info("Customer email from Customer object: " + email);

                    if (email != null) {
                        try {
                            memberinfoService.upgradeUserRole(email, "ROLE_PAID");
                            logger.info("User role upgraded to ROLE_PAID for user: " + email);
                        } catch (RuntimeException e) {
                            logger.severe("Error upgrading user role: " + e.getMessage());
                        }
                    } else {
                        logger.warning("User email not found in Customer object.");
                    }
                } catch (Exception e) {
                    logger.severe("Error retrieving Customer object: " + e.getMessage());
                }
            }
        } else {
            logger.warning("Unable to deserialize object.");
        }
    }

    private void handleSubscriptionDeleted(Event event) {
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (dataObjectDeserializer.getObject().isPresent()) {
            Subscription subscription = (Subscription) dataObjectDeserializer.getObject().get();
            String customerId = subscription.getCustomer();
            logger.info("Customer ID from subscription: " + customerId);

            try {
                if (customerId != null) {
                    // 顧客オブジェクトの取得
                    Customer customer = Customer.retrieve(customerId);
                    String email = customer.getEmail();
                    logger.info("Customer email from Customer object: " + email);

                    if (email != null) {
                        try {
                            memberinfoService.upgradeUserRole(email, "ROLE_GENERAL");
                            logger.info("User role downgraded to ROLE_GENERAL for user: " + email);
                        } catch (RuntimeException e) {
                            logger.severe("Error downgrading user role: " + e.getMessage());
                        }
                    } else {
                        logger.warning("User email not found in Customer object.");
                    }
                } else {
                    logger.warning("Customer ID not found in subscription.");
                }
            } catch (Exception e) {
                logger.severe("Error retrieving Customer object: " + e.getMessage());
            }
        } else {
            logger.warning("Unable to deserialize object.");
        }
    }

    @GetMapping("/api/get-user-role")
    public ResponseEntity<Map<String, String>> getUserRole(@RequestParam String email) {
        try {
            String role = memberinfoService.getUserRoleByEmail(email);
            return ResponseEntity.ok(Map.of("role", role));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error while fetching user role", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Could not fetch the user role"));
        }
    }
}
