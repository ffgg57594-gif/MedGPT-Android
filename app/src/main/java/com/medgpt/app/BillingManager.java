package com.drarabi.medvision;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Google Play Billing integration for MedVision AI.
 *
 * Products (must be created in Google Play Console):
 *  - premium_monthly  : subscription, 1 month base plan, with a 3-day free-trial offer (tag "trial")
 *  - premium_lifetime : one-time in-app product
 *
 * Entitlements:
 *  - Monthly subscription active while Play reports an owned subscription purchase.
 *  - Lifetime access once the lifetime product is purchased.
 */
public class BillingManager {

    private static final String TAG = "BillingManager";

    public static final String PRODUCT_MONTHLY = "premium_monthly";
    public static final String PRODUCT_LIFETIME = "premium_lifetime";

    private static final String PREFS = "medvision_billing";
    private static final String KEY_SUB_ACTIVE = "subscription_active";
    private static final String KEY_LIFETIME = "lifetime_owned";

    private final Activity activity;
    private final WebView webView;
    private final SharedPreferences prefs;
    private final Map<String, ProductDetails> products = new ConcurrentHashMap<>();
    // Play-authoritative entitlement state (set only from queryPurchases results)
    private volatile boolean subscriptionActive = false;
    private volatile boolean lifetimeOwned = false;
    private volatile boolean playStateQueried = false;

    private BillingClient billingClient;

    public BillingManager(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ==================== Lifecycle ====================

    public void connect() {
        if (billingClient == null) {
            billingClient = BillingClient.newBuilder(activity)
                    .setListener(purchasesUpdatedListener)
                    .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                            .enableOneTimeProducts()
                            .build())
                    .build();
        }
        if (billingClient.isReady()) return;

        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult billingResult) {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Billing connected");
                    queryProductDetails();
                    queryPurchases();
                } else {
                    Log.w(TAG, "Billing setup failed: " + billingResult.getDebugMessage());
                    pushStatus("setup_failed",
                            setupFailureMessage(billingResult.getResponseCode(), billingResult.getDebugMessage()));
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected");
            }
        });
    }

    public void destroy() {
        if (billingClient != null) {
            billingClient.endConnection();
            billingClient = null;
        }
    }

    /** Re-queries Google Play for owned purchases (used when the app resumes). */
    public void refresh() {
        activity.runOnUiThread(this::queryPurchases);
    }

    /** Re-attempts connecting to Google Play Billing (used by the lock screen retry). */
    @JavascriptInterface
    public String retry() {
        connect();
        return simpleResult(true, "Connecting to Google Play...");
    }

    // ==================== JS Bridge ====================

    /**
     * Returns the current billing state as a JSON string:
     * { connected, pending, isPremium, entitlement, subscriptionActive, lifetimeOwned, monthly, lifetime }
     */
    @JavascriptInterface
    public String getStatus() {
        return buildStatusJson();
    }

    /**
     * Launches the Play billing flow for a product id.
     * Returns { ok, message }. The final result is delivered to window.onBillingUpdate.
     */
    @JavascriptInterface
    public String buy(String productId) {
        try {
            return doBuy(productId);
        } catch (Exception e) {
            Log.e(TAG, "buy() failed", e);
            return simpleResult(false, "Purchase error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String doBuy(String productId) {
        if (!isReady()) {
            if (!playStoreInstalled()) {
                return simpleResult(false, "Google Play Store is not installed on this device, so billing is unavailable.");
            }
            return simpleResult(false, "Google Play billing is not connected yet. Sign in to the Play Store and make sure this build is installed from Google Play (Internal Testing), then try again.");
        }
        ProductDetails details = products.get(productId);
        if (details == null) {
            // Product details may not have loaded yet — refresh them once and guide the user.
            queryProductDetails();
            return simpleResult(false, "Preparing the Play Store checkout — please try again in a moment.");
        }

        BillingFlowParams.ProductDetailsParams.Builder paramsBuilder =
                BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details);

        if (BillingClient.ProductType.SUBS.equals(details.getProductType())) {
            ProductDetails.SubscriptionOfferDetails offer = pickOffer(details);
            if (offer == null) {
                return simpleResult(false, "No subscription plan was found.");
            }
            String offerToken = offer.getOfferToken();
            if (offerToken == null || offerToken.isEmpty()) {
                return simpleResult(false, "The subscription has no valid offer token. Check the base plan offer setup in Play Console.");
            }
            paramsBuilder.setOfferToken(offerToken);
        }

        final BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(paramsBuilder.build()))
                .build();

        activity.runOnUiThread(() -> {
            BillingResult result = billingClient.launchBillingFlow(activity, flowParams);
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                pushStatus("purchase_error",
                        billingErrorMessage(result.getResponseCode(), result.getDebugMessage()));
            }
        });
        return simpleResult(true, "Opening the Play Store checkout...");
    }

    /** Re-queries Play for owned purchases and refreshes entitlements. */
    @JavascriptInterface
    public String restore() {
        if (!isReady()) {
            return simpleResult(false, "Google Play is not connected. Check your connection and try again.");
        }
        activity.runOnUiThread(this::queryPurchases);
        return simpleResult(true, "Restoring your purchases...");
    }

    // ==================== Billing queries ====================

    private boolean isReady() {
        return billingClient != null && billingClient.isReady();
    }

    private void queryProductDetails() {
        if (!isReady()) return;

        // Billing 9 requires every query to contain products of a single type:
        // subscriptions (SUBS) and one-time products (INAPP) must be queried separately.
        queryProductDetailsFor(PRODUCT_MONTHLY, BillingClient.ProductType.SUBS);
        queryProductDetailsFor(PRODUCT_LIFETIME, BillingClient.ProductType.INAPP);
    }

    private void queryProductDetailsFor(String productId, String productType) {
        if (!isReady()) return;

        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(
                        QueryProductDetailsParams.Product.newBuilder()
                                .setProductId(productId)
                                .setProductType(productType)
                                .build()))
                .build();

        billingClient.queryProductDetailsAsync(params, (billingResult, result) -> {
            // Billing 9.x returns a QueryProductDetailsResult wrapper instead of a raw list.
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK
                    && result != null) {
                List<ProductDetails> productDetailsList = result.getProductDetailsList();
                if (productDetailsList != null) {
                    for (ProductDetails details : productDetailsList) {
                        products.put(details.getProductId(), details);
                    }
                }
                pushStatus("products_loaded", null);
            } else {
                Log.w(TAG, "Product details query failed: " + billingResult.getDebugMessage());
            }
        });
    }

    private void queryPurchases() {
        if (!isReady()) return;

        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                (result, subs) -> billingClient.queryPurchasesAsync(
                        QueryPurchasesParams.newBuilder()
                                .setProductType(BillingClient.ProductType.INAPP)
                                .build(),
                        (result2, inapps) -> {
                            List<Purchase> all = new ArrayList<>();
                            if (subs != null) all.addAll(subs);
                            if (inapps != null) all.addAll(inapps);
                            applyPurchases(all);
                            pushStatus("purchases_restored", null);
                        }));
    }

    private final PurchasesUpdatedListener purchasesUpdatedListener =
            (billingResult, purchases) -> {
                int code = billingResult.getResponseCode();
                if (code == BillingClient.BillingResponseCode.OK && purchases != null) {
                    applyPurchases(purchases);
                    pushStatus("purchase_success", "Your subscription has been activated.");
                } else if (code == BillingClient.BillingResponseCode.USER_CANCELED) {
                    pushStatus("purchase_cancelled", "Purchase cancelled.");
                } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                    queryPurchases();
                } else {
                    pushStatus("purchase_error",
                            billingErrorMessage(code, billingResult.getDebugMessage()));
                }
            };

    private void applyPurchases(List<Purchase> purchases) {
        boolean subActive = false;
        boolean lifetime = false;
        if (purchases != null) {
            for (Purchase purchase : purchases) {
                if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) continue;

                if (purchase.getProducts().contains(PRODUCT_MONTHLY)) subActive = true;
                if (purchase.getProducts().contains(PRODUCT_LIFETIME)) lifetime = true;

                if (!purchase.isAcknowledged()) {
                    acknowledge(purchase.getPurchaseToken());
                }
            }
        }
        // Memory state is authoritative and always overwritten by the fresh Play answer:
        // if Play reports the subscription is no longer active, it is treated as expired
        // even when the local cache still holds an old "active" value.
        subscriptionActive = subActive;
        lifetimeOwned = lifetime;
        playStateQueried = true;

        // Keep the cache updated for reference, but it is never used to grant access.
        prefs.edit()
                .putBoolean(KEY_SUB_ACTIVE, subActive)
                .putBoolean(KEY_LIFETIME, lifetime)
                .apply();
    }

    private void acknowledge(String purchaseToken) {
        if (!isReady()) return;
        billingClient.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build(),
                result -> Log.i(TAG, "Acknowledge result: " + result.getResponseCode()));
    }

    // ==================== Offers & pricing helpers ====================

    /** Picks the subscription offer that includes a free trial when one exists, else the first offer. */
    private ProductDetails.SubscriptionOfferDetails pickOffer(ProductDetails details) {
        List<ProductDetails.SubscriptionOfferDetails> offers = details.getSubscriptionOfferDetails();
        if (offers == null || offers.isEmpty()) return null;
        for (ProductDetails.SubscriptionOfferDetails offer : offers) {
            if (hasFreeTrial(offer)) return offer;
        }
        return offers.get(0);
    }

    private boolean hasFreeTrial(ProductDetails.SubscriptionOfferDetails offer) {
        if (offer.getOfferId() != null && offer.getOfferId().toLowerCase().contains("trial")) return true;
        if (offer.getOfferTags() != null) {
            for (String tag : offer.getOfferTags()) {
                if (tag != null && tag.toLowerCase().contains("trial")) return true;
            }
        }
        ProductDetails.PricingPhases phases = offer.getPricingPhases();
        if (phases == null) return false;
        for (ProductDetails.PricingPhase phase : phases.getPricingPhaseList()) {
            if (phase.getPriceAmountMicros() == 0) return true;
        }
        return false;
    }

    private int trialDaysFromOffer(ProductDetails.SubscriptionOfferDetails offer) {
        ProductDetails.PricingPhases phases = offer.getPricingPhases();
        if (phases == null) return 3;
        for (ProductDetails.PricingPhase phase : phases.getPricingPhaseList()) {
            if (phase.getPriceAmountMicros() == 0) {
                int days = parsePeriodDays(phase.getBillingPeriod());
                if (days > 0) return days;
            }
        }
        return 3;
    }

    private ProductDetails.PricingPhase firstPaidPhase(ProductDetails.SubscriptionOfferDetails offer) {
        ProductDetails.PricingPhases phases = offer.getPricingPhases();
        if (phases == null) return null;
        for (ProductDetails.PricingPhase phase : phases.getPricingPhaseList()) {
            if (phase.getPriceAmountMicros() > 0) return phase;
        }
        List<ProductDetails.PricingPhase> phaseList = phases.getPricingPhaseList();
        return phaseList.isEmpty() ? null : phaseList.get(phaseList.size() - 1);
    }

    /** Converts ISO-8601 durations like P3D / P1W / P1M into days (approximate for months/weeks). */
    private int parsePeriodDays(String period) {
        if (period == null) return 0;
        String p = period.toUpperCase();
        try {
            if (p.startsWith("P")) p = p.substring(1);
            if (p.endsWith("D")) return Integer.parseInt(p.substring(0, p.length() - 1));
            if (p.endsWith("W")) return Integer.parseInt(p.substring(0, p.length() - 1)) * 7;
            if (p.endsWith("M")) return Integer.parseInt(p.substring(0, p.length() - 1)) * 30;
            if (p.endsWith("Y")) return Integer.parseInt(p.substring(0, p.length() - 1)) * 365;
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return 0;
    }

    // ==================== Diagnostics ====================

    private boolean playStoreInstalled() {
        try {
            return activity.getPackageManager().getPackageInfo("com.android.vending", 0) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private String setupFailureMessage(int responseCode, String debugMessage) {
        switch (responseCode) {
            case BillingClient.BillingResponseCode.BILLING_UNAVAILABLE:
                return playStoreInstalled()
                        ? "Play billing is unavailable — this build must be installed from Google Play (Internal Testing) with a tester account to buy."
                        : "Google Play Store is not installed on this device, so billing is unavailable.";
            case BillingClient.BillingResponseCode.DEVELOPER_ERROR:
                return "Billing setup error: check that the product IDs match your Play Console app and that this APK is signed and linked to it.";
            case BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE:
                return "Play billing service is unavailable right now — check your internet connection and try again.";
            case BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED:
                return "Play billing is not supported on this device.";
            default:
                return (debugMessage != null && !debugMessage.isEmpty())
                        ? debugMessage
                        : "Could not connect to Google Play billing. Try again.";
        }
    }

    private String billingErrorMessage(int responseCode, String debugMessage) {
        switch (responseCode) {
            case BillingClient.BillingResponseCode.ITEM_UNAVAILABLE:
                return "This product is not available yet — check that the product IDs match your Play Console and that the app is on an active testing track.";
            case BillingClient.BillingResponseCode.BILLING_UNAVAILABLE:
                return playStoreInstalled()
                        ? "Play billing is unavailable — install this build from Google Play (Internal Testing) with a tester account to buy."
                        : "Google Play Store is not installed on this device, so billing is unavailable.";
            case BillingClient.BillingResponseCode.DEVELOPER_ERROR:
                return "Billing error: check the product setup and that this APK is signed and linked to your Play Console app.";
            case BillingClient.BillingResponseCode.USER_CANCELED:
                return "Purchase cancelled.";
            default:
                return (debugMessage != null && !debugMessage.isEmpty())
                        ? debugMessage
                        : "Could not start the purchase. Try again.";
        }
    }

    // ==================== Status JSON ====================

    private String buildStatusJson() {
        JSONObject json = new JSONObject();
        try {
            // Google Play is the source of truth. Until the first Play query completes,
            // entitlements stay locked (pending) — the local cache is never used to grant access.
            boolean lifetime;
            boolean subActive;
            if (playStateQueried) {
                lifetime = lifetimeOwned;
                subActive = subscriptionActive;
            } else {
                lifetime = false;
                subActive = false;
            }

            boolean premium = lifetime || subActive;

            String entitlement = lifetime ? "lifetime"
                    : subActive ? "subscription"
                    : "none";

            json.put("connected", isReady());
            json.put("playStore", playStoreInstalled());
            json.put("pending", !playStateQueried);
            json.put("isPremium", premium);
            json.put("entitlement", entitlement);
            json.put("subscriptionActive", subActive);
            json.put("lifetimeOwned", lifetime);
            json.put("monthly", productJson(PRODUCT_MONTHLY, true));
            json.put("lifetime", productJson(PRODUCT_LIFETIME, false));
        } catch (Exception e) {
            Log.e(TAG, "buildStatusJson error", e);
        }
        return json.toString();
    }

    private JSONObject productJson(String productId, boolean subscription) {
        JSONObject json = new JSONObject();
        try {
            ProductDetails details = products.get(productId);
            if (details == null) {
                json.put("available", false);
                return json;
            }
            json.put("available", true);
            json.put("productId", productId);

            if (subscription) {
                ProductDetails.SubscriptionOfferDetails offer = pickOffer(details);
                if (offer != null) {
                    ProductDetails.PricingPhase paid = firstPaidPhase(offer);
                    if (paid != null) {
                        json.put("price", paid.getFormattedPrice());
                        json.put("priceAmountMicros", paid.getPriceAmountMicros());
                    }
                    json.put("trialDays", trialDaysFromOffer(offer));
                }
            } else {
                ProductDetails.OneTimePurchaseOfferDetails oneTime = details.getOneTimePurchaseOfferDetails();
                if (oneTime != null) {
                    json.put("price", oneTime.getFormattedPrice());
                    json.put("priceAmountMicros", oneTime.getPriceAmountMicros());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "productJson error", e);
        }
        return json;
    }

    private void pushStatus(String event, String message) {
        String status;
        try {
            JSONObject json = new JSONObject(buildStatusJson());
            json.put("event", event == null ? "update" : event);
            if (message != null) json.put("message", message);
            status = json.toString();
        } catch (Exception e) {
            status = "{}";
        }
        final String js = "window.onBillingUpdate && window.onBillingUpdate('"
                + escapeForJs(status) + "');";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private String escapeForJs(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private String simpleResult(boolean ok, String message) {
        try {
            return new JSONObject().put("ok", ok).put("message", message).toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"message\":\"error\"}";
        }
    }
}
