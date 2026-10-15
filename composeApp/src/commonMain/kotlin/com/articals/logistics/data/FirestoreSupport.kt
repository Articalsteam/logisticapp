package com.articals.logistics.data

import dev.gitlive.firebase.firestore.FirebaseFirestore
import dev.gitlive.firebase.firestore.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.serializer

/**
 * Read helpers.
 *
 * The app reads live data straight from Firestore (the rules make every client
 * write impossible) and writes only through callables. Snapshots are decoded with
 * explicit serializers rather than reified type parameters, because the decoding
 * happens inside `Flow.map`, which is not an inline function.
 *
 * Missing documents become `null`, and a document field the app does not know
 * about is ignored by the decoder — a client released today must keep working
 * against a document written by a newer backend.
 */

/** Emits the document at [path] whenever it changes, or `null` when it does not exist. */
inline fun <reified T> FirebaseFirestore.documentFlow(path: String): Flow<T?> {
    val strategy = serializer<T>()
    return document(path).snapshots().map { snapshot ->
        if (snapshot.exists) snapshot.data(strategy) else null
    }
}

/** Emits the documents matched by this query, giving each DTO its Firestore id. */
inline fun <reified T> Query.observeDocuments(crossinline withId: (String, T) -> T): Flow<List<T>> {
    val strategy = serializer<T>()
    return snapshots().map { querySnapshot ->
        querySnapshot.documents
            .filter { it.exists }
            .map { document -> withId(document.id, document.data(strategy)) }
    }
}

/**
 * Document and collection paths.
 *
 * Membership documents use the composite id `{tenantId}_{uid}` so that the
 * security rules can look up a caller's authority without a query, and so that a
 * user can belong to several companies with a deterministic document each.
 */
object FirestorePaths {
    fun company(tenantId: String): String = "companies/$tenantId"
    fun membership(tenantId: String, userId: String): String = "memberships/${membershipId(tenantId, userId)}"
    fun membershipId(tenantId: String, userId: String): String = "${tenantId}_$userId"
    fun pricingConfig(configId: String): String = "pricingConfigs/$configId"
    fun subscription(tenantId: String): String = "subscriptions/$tenantId"
    fun order(orderId: String): String = "orders/$orderId"
    fun orderEvents(orderId: String): String = "orders/$orderId/deliveryEvents"
    fun rider(riderId: String): String = "riders/$riderId"
    fun customer(customerId: String): String = "customers/$customerId"
    fun expense(expenseId: String): String = "expenses/$expenseId"
    fun quote(quoteId: String): String = "quotes/$quoteId"
    fun reportPath(tenantId: String, fileName: String): String = "reports/$tenantId/$fileName"
    fun companyLogoPath(tenantId: String, fileName: String): String = "logo/$tenantId/$fileName"
    fun proofPath(tenantId: String, orderId: String, fileName: String): String =
        "proof/$tenantId/$orderId/$fileName"

    const val companies = "companies"
    const val memberships = "memberships"
    const val orders = "orders"
    const val riders = "riders"
    const val customers = "customers"
    const val expenses = "expenses"
    const val pricingConfigs = "pricingConfigs"
    const val subscriptions = "subscriptions"
    const val quotes = "quotes"
}
