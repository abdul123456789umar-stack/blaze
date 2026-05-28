package com.blaze.agent.utilities.mocks

import android.os.Bundle

/**
 * Mock Firebase Analytics for MVP testing
 * This allows the code to work without Firebase configuration
 */
object MockFirebaseAnalytics {
    fun logEvent(eventName: String, params: Bundle?) {
        // No-op for MVP testing
        android.util.Log.d("MockFirebase", "Event logged: $eventName")
    }
}

/**
 * Mock Firebase Auth for MVP testing
 */
object MockFirebaseAuth {
    val currentUser: MockUser? = null
    
    fun signInWithCustomToken(token: String, callback: (Boolean) -> Unit) {
        callback(true)
    }
}

data class MockUser(
    val uid: String = "mock_user_id"
)

/**
 * Mock Firebase Firestore for MVP testing
 */
object MockFirebaseFirestore {
    fun collection(name: String): MockCollectionReference {
        return MockCollectionReference()
    }
}

class MockCollectionReference {
    fun document(id: String): MockDocumentReference {
        return MockDocumentReference()
    }
}

class MockDocumentReference {
    fun exists(callback: (Boolean) -> Unit) {
        callback(false)
    }
    
    fun get(callback: (Any?) -> Unit) {
        callback(null)
    }
    
    fun set(data: Any, callback: (Boolean) -> Unit) {
        callback(true)
    }
}

/**
 * Mock Firebase Functions for MVP testing
 */
object MockFirebaseFunctions {
    fun getHttpsCallable(name: String): MockHttpsCallable {
        return MockHttpsCallable()
    }
}

class MockHttpsCallable {
    fun call(data: Any?, callback: (MockHttpsCallableResult) -> Unit) {
        callback(MockHttpsCallableResult(success = true, result = null))
    }
}

data class MockHttpsCallableResult(
    val success: Boolean = false,
    val result: Any? = null,
    val message: String? = null,
    val details: String? = null,
    val exception: Exception? = null
)

/**
 * Mock Firebase object to replace com.google.firebase.Firebase
 */
object Firebase {
    val analytics get() = MockFirebaseAnalytics
    val auth get() = MockFirebaseAuth
    val firestore get() = MockFirebaseFirestore
    val functions get() = MockFirebaseFunctions
}

// Type aliases for Timestamp
typealias Timestamp = Long
typealias FieldValue = Any

class FirebaseFunctionsException(
    val code: String,
    override val message: String?,
    val details: Any?
) : Exception(message)
