package com.familycontrol.lab

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Singleton managing Google Play Billing Client, subscription flows,
 * purchase queries, and Restore Purchases entitlement reconciliation.
 */
object BillingManager : PurchasesUpdatedListener {

    private const val TAG = "BillingManager"

    // Subscription product IDs configured in Google Play Console
    const val SUB_MONTHLY = "famorbit_monthly_sub"
    const val SUB_ANNUAL = "famorbit_annual_sub"

    private var billingClient: BillingClient? = null
    private var isConnected = false
    private var appContext: Context? = null
    private val productDetailsMap = mutableMapOf<String, ProductDetails>()

    fun init(context: Context) {
        if (billingClient != null) return
        appContext = context.applicationContext

        billingClient = BillingClient.newBuilder(context.applicationContext)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()

        startConnection { success ->
            if (success) {
                querySubscriptionProducts()
                // Automatically verify and sync purchases on startup
                queryActivePurchases { isPremium, _ ->
                    if (isPremium) {
                        Log.i(TAG, "Active Google Play subscription restored on app launch.")
                    }
                }
            }
        }
    }

    private fun startConnection(onComplete: ((Boolean) -> Unit)? = null) {
        val client = billingClient ?: run {
            onComplete?.invoke(false)
            return
        }

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    isConnected = true
                    Log.i(TAG, "Google Play Billing connected successfully.")
                    onComplete?.invoke(true)
                } else {
                    isConnected = false
                    Log.w(TAG, "Billing setup failed: code=${billingResult.responseCode} ${billingResult.debugMessage}")
                    onComplete?.invoke(false)
                }
            }

            override fun onBillingServiceDisconnected() {
                isConnected = false
                Log.w(TAG, "Billing service disconnected. Will reconnect on next request.")
            }
        })
    }

    private fun ensureConnected(action: (Boolean) -> Unit) {
        val client = billingClient
        if (client == null) {
            action(false)
            return
        }
        if (isConnected && client.isReady) {
            action(true)
        } else {
            startConnection(action)
        }
    }

    /**
     * Query product metadata and pricing from Google Play.
     */
    fun querySubscriptionProducts(onDone: (() -> Unit)? = null) {
        ensureConnected { connected ->
            if (!connected) {
                onDone?.invoke()
                return@ensureConnected
            }

            val productList = listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(SUB_MONTHLY)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build(),
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(SUB_ANNUAL)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            )

            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build()

            billingClient?.queryProductDetailsAsync(params) { billingResult, queryProductDetailsResult ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    productDetailsMap.clear()
                    val productDetailsList = queryProductDetailsResult.productDetailsList
                    for (details in productDetailsList) {
                        productDetailsMap[details.productId] = details
                        Log.i(TAG, "Product loaded: ${details.productId} title=${details.title}")
                    }
                } else {
                    Log.w(TAG, "queryProductDetailsAsync error: ${billingResult.debugMessage}")
                }
                onDone?.invoke()
            }
        }
    }

    /**
     * Launch official Google Play subscription bottom sheet.
     * Returns true if Google Play UI was launched, false if fallback local activation should be used.
     */
    fun launchPurchaseFlow(
        activity: Activity,
        planType: String,
        onFallbackRequired: () -> Unit
    ) {
        val targetProductId = if (planType == PremiumManager.PLAN_ANNUAL) SUB_ANNUAL else SUB_MONTHLY

        ensureConnected { connected ->
            if (!connected) {
                Log.w(TAG, "Billing not connected, triggering fallback")
                activity.runOnUiThread { onFallbackRequired() }
                return@ensureConnected
            }

            val productDetails = productDetailsMap[targetProductId]
            if (productDetails == null) {
                // If not cached, re-query once
                querySubscriptionProducts {
                    val freshDetails = productDetailsMap[targetProductId]
                    if (freshDetails == null) {
                        Log.w(TAG, "Product details not available in Play Console yet. Using graceful local trial/mock fallback.")
                        activity.runOnUiThread { onFallbackRequired() }
                    } else {
                        startFlow(activity, freshDetails, planType, onFallbackRequired)
                    }
                }
            } else {
                startFlow(activity, productDetails, planType, onFallbackRequired)
            }
        }
    }

    private fun startFlow(
        activity: Activity,
        productDetails: ProductDetails,
        planType: String,
        onFallbackRequired: () -> Unit
    ) {
        val subscriptionOfferDetails = productDetails.subscriptionOfferDetails
        val offerToken = subscriptionOfferDetails?.firstOrNull()?.offerToken

        if (offerToken.isNullOrBlank()) {
            Log.w(TAG, "No offerToken found for product ${productDetails.productId}")
            activity.runOnUiThread { onFallbackRequired() }
            return
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .setOfferToken(offerToken)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        val responseCode = billingClient?.launchBillingFlow(activity, billingFlowParams)?.responseCode
        if (responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "launchBillingFlow failed with responseCode=$responseCode")
            activity.runOnUiThread { onFallbackRequired() }
        }
    }

    /**
     * Restore Purchases functionality:
     * Queries Google Play's servers for existing active purchases and validates entitlement.
     */
    fun restorePurchases(onResult: (Boolean, String) -> Unit) {
        val context = appContext ?: run {
            onResult(false, "App context not initialized.")
            return
        }

        ensureConnected { connected ->
            if (!connected) {
                // If Play Billing cannot connect (offline or no Google Play Services), check existing local state
                val isLocalPremium = PremiumManager.isPremium(context)
                if (isLocalPremium) {
                    onResult(true, "Restored active local subscription (${PremiumManager.getPlanType(context)}).")
                } else {
                    onResult(false, "Unable to connect to Google Play. Please check your internet connection.")
                }
                return@ensureConnected
            }

            queryActivePurchases { isPurchased, message ->
                onResult(isPurchased, message)
            }
        }
    }

    /**
     * Internal query for active subscriptions from Google Play.
     */
    private fun queryActivePurchases(onResult: (Boolean, String) -> Unit) {
        val context = appContext ?: return

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient?.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val activePurchases = purchasesList.filter {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED
                }

                if (activePurchases.isNotEmpty()) {
                    var restoredPlan = PremiumManager.PLAN_ANNUAL
                    for (purchase in activePurchases) {
                        // Acknowledge if pending
                        if (!purchase.isAcknowledged) {
                            acknowledgePurchase(purchase)
                        }
                        if (purchase.products.contains(SUB_MONTHLY)) {
                            restoredPlan = PremiumManager.PLAN_MONTHLY
                        }
                    }

                    // Set entitlement active
                    PremiumManager.setPremium(context, true, restoredPlan, 365)
                    Log.i(TAG, "Active purchase found: restored $restoredPlan")
                    onResult(true, "Your $restoredPlan subscription has been successfully restored!")
                } else {
                    // Check if local trial is still ongoing
                    if (PremiumManager.isPremium(context)) {
                        onResult(true, "Active entitlement found on device (${PremiumManager.getPlanType(context)}).")
                    } else {
                        onResult(false, "No active subscription found for this Google Play account.")
                    }
                }
            } else {
                Log.w(TAG, "queryPurchasesAsync error: ${billingResult.debugMessage}")
                if (PremiumManager.isPremium(context)) {
                    onResult(true, "Active entitlement found on device (${PremiumManager.getPlanType(context)}).")
                } else {
                    onResult(false, "Could not verify purchases: ${billingResult.debugMessage}")
                }
            }
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient?.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.i(TAG, "Purchase acknowledged successfully: ${purchase.orderId}")
            } else {
                Log.w(TAG, "Failed to acknowledge purchase: ${billingResult.debugMessage}")
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        val context = appContext ?: return
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    if (!purchase.isAcknowledged) {
                        acknowledgePurchase(purchase)
                    }
                    val plan = if (purchase.products.contains(SUB_MONTHLY)) PremiumManager.PLAN_MONTHLY else PremiumManager.PLAN_ANNUAL
                    PremiumManager.setPremium(context, true, plan, 365)
                    Log.i(TAG, "Purchase successful: plan=$plan")
                }
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.i(TAG, "User canceled billing purchase flow.")
        } else {
            Log.w(TAG, "onPurchasesUpdated error code=${billingResult.responseCode}: ${billingResult.debugMessage}")
        }
    }
}
