package com.blaze.agent.utilities

import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryPurchasesAsync
import com.blaze.agent.MyApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

class FreemiumManager {

    private val billingClient: BillingClient = MyApplication.billingClient

    companion object {
        const val DAILY_TASK_LIMIT = 15 // Set your daily task limit here
        private const val PRO_SKU = "pro" // The SKU for the pro subscription
    }

    suspend fun getDeveloperMessage(): String {
        return try {
            val document = db.collection("settings").document("freemium").get().await()
            document.getString("developerMessage") ?: ""
        } catch (e: Exception) {
            Log.e("FreemiumManager", "Error fetching developer message from Firestore.", e)
            ""
        }
    }
    suspend fun isUserSubscribed(): Boolean {
        return try {
            if (document.exists()) {
                val plan = document.getString("plan")
                plan == "pro"
            } else {
                false // Document doesn't exist, so user can't be pro
            }
        } catch (e: Exception) {
            Logger.e("FreemiumManager", "Error checking user plan from Firestore", e)
            false // In case of error, default to not pro
        }
    }



    suspend fun provisionUserIfNeeded() {

        try {
            val document = userDocRef.get().await()
            if (!document.exists()) {
                val newUser = hashMapOf(
                    "plan" to "free",
                    "createdAt" to FieldValue.serverTimestamp()
                )
                userDocRef.set(newUser).await()
            }
        } catch (e: Exception) {
            Logger.e("FreemiumManager", "Error provisioning user", e)
        }
    }

    suspend fun getTasksRemaining(): Long? {
        if (isUserSubscribed()) return Long.MAX_VALUE
        return try {
            document.getLong("tasksRemaining")
        } catch (e: Exception) {
            Logger.e("FreemiumManager", "Error fetching tasks remaining", e)
            null
        }
    }

    suspend fun canPerformTask(): Boolean {
        if (isUserSubscribed()) return true

        return try {
            val tasksRemaining = document.getLong("tasksRemaining") ?: 0
            Logger.d("FreemiumManager", "User has $tasksRemaining tasks remaining today.")
            tasksRemaining > 0
        } catch (e: Exception) {
            Logger.e("FreemiumManager", "Error fetching user task count", e)
            false
        }
    }

    suspend fun decrementTaskCount() {
        if (isUserSubscribed()) return

        
        try {
            userDocRef.update("tasksRemaining", FieldValue.increment(-1)).await()
        } catch (e: Exception) {
            Logger.e("FreemiumManager", "Failed to decrement task count.", e)
        }
    }
}