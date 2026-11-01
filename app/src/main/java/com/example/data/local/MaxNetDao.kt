package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MaxNetDao {
    // Profiles
    @Query("SELECT * FROM profiles ORDER BY isPinned DESC, isFavorite DESC, sortOrder ASC, id DESC")
    fun observeAllProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY isPinned DESC, isFavorite DESC, sortOrder ASC, id DESC")
    suspend fun getAllProfilesSnapshot(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun getProfileById(id: Long): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: ProfileEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfiles(profiles: List<ProfileEntity>): List<Long>

    @Update
    suspend fun updateProfile(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteProfileById(id: Long)

    @Query("DELETE FROM profiles WHERE id IN (:ids)")
    suspend fun deleteProfilesByIds(ids: List<Long>)

    @Query("DELETE FROM profiles WHERE subscriptionId = :subscriptionId")
    suspend fun deleteProfilesBySubscriptionId(subscriptionId: Long)

    @Query("UPDATE profiles SET lastLatencyMs = :latencyMs, latencyType = :measureType WHERE id = :id")
    suspend fun updateProfileLatency(id: Long, latencyMs: Long?, measureType: String)

    @Query(
        "UPDATE profiles SET lastLatencyMs = :latencyMs, tcpConnectMs = COALESCE(:tcpConnectMs, tcpConnectMs), latencyType = :measureType, lastTestedAtMs = :testedAtMs WHERE id = :id"
    )
    suspend fun updateProfileLatencyAndDiagnostics(
        id: Long,
        latencyMs: Long?,
        tcpConnectMs: Long?,
        measureType: String,
        testedAtMs: Long = System.currentTimeMillis()
    )

    @Update
    suspend fun updateProfiles(profiles: List<ProfileEntity>)

    // Subscriptions
    @Query("SELECT * FROM subscriptions ORDER BY id DESC")
    fun observeAllSubscriptions(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY id DESC")
    suspend fun getAllSubscriptionsSnapshot(): List<SubscriptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(subscription: SubscriptionEntity): Long

    @Update
    suspend fun updateSubscription(subscription: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteSubscriptionById(id: Long)

    @Transaction
    suspend fun replaceSubscriptionProfilesTransaction(
        subscriptionId: Long,
        newProfiles: List<ProfileEntity>,
        updatedSubscription: SubscriptionEntity
    ) {
        deleteProfilesBySubscriptionId(subscriptionId)
        insertProfiles(newProfiles)
        updateSubscription(updatedSubscription)
    }

    /**
     * In-place transactional synchronization for a personal subscription:
     * 1. Updates existing subscription profiles in-place (preserving database ID, favorite/pinned status,
     *    user-customized remarks, and measured latency diagnostics).
     * 2. Inserts newly discovered subscription profiles.
     * 3. Removes only obsolete profiles belonging to this subscription that were removed upstream.
     * 4. Never touches manually imported profiles (subscriptionId == null) or other subscriptions.
     */
    @Transaction
    suspend fun syncSubscriptionProfilesInPlaceTransaction(
        profilesToUpdateInPlace: List<ProfileEntity>,
        profilesToInsertNew: List<ProfileEntity>,
        obsoleteProfileIdsToDelete: List<Long>,
        updatedSubscription: SubscriptionEntity
    ) {
        if (obsoleteProfileIdsToDelete.isNotEmpty()) {
            deleteProfilesByIds(obsoleteProfileIdsToDelete)
        }
        if (profilesToUpdateInPlace.isNotEmpty()) {
            updateProfiles(profilesToUpdateInPlace)
        }
        if (profilesToInsertNew.isNotEmpty()) {
            insertProfiles(profilesToInsertNew)
        }
        updateSubscription(updatedSubscription)
    }

    // Profile Groups
    @Query("SELECT * FROM profile_groups ORDER BY name ASC")
    fun observeAllGroups(): Flow<List<ProfileGroupEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroup(group: ProfileGroupEntity): Long

    // Routing Rules
    @Query("SELECT * FROM routing_rules ORDER BY id ASC")
    fun observeRoutingRules(): Flow<List<RoutingRuleEntity>>

    @Query("SELECT * FROM routing_rules ORDER BY id ASC")
    suspend fun getRoutingRulesSnapshot(): List<RoutingRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutingRule(rule: RoutingRuleEntity): Long

    @Update
    suspend fun updateRoutingRule(rule: RoutingRuleEntity)

    @Query("DELETE FROM routing_rules WHERE id = :id")
    suspend fun deleteRoutingRuleById(id: Long)

    // Application Settings
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApplicationSettings(settings: ApplicationSettingsEntity)

    @Query("SELECT * FROM application_settings WHERE id = 1 LIMIT 1")
    suspend fun getApplicationSettings(): ApplicationSettingsEntity?

    // Connection History
    @Query("SELECT * FROM connection_history ORDER BY connectedAt DESC LIMIT 100")
    fun observeConnectionHistory(): Flow<List<ConnectionHistoryEntity>>

    @Query("SELECT * FROM connection_history ORDER BY connectedAt DESC LIMIT :limit")
    suspend fun getRecentConnectionHistory(limit: Int = 50): List<ConnectionHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConnectionHistory(entry: ConnectionHistoryEntity): Long

    @Query("DELETE FROM connection_history")
    suspend fun clearConnectionHistory()

    @Query(
        "UPDATE profiles SET lastLatencyMs = :latencyMs, tcpConnectMs = COALESCE(:tcpConnectMs, tcpConnectMs), jitterMs = :jitterMs, packetLossRate = :packetLossRate, successRate = :successRate, healthScore = :healthScore, consecutiveFailures = :consecutiveFailures, lastFailureTimestampMs = :lastFailureMs, latencyType = :measureType, lastTestedAtMs = :testedAtMs WHERE id = :id"
    )
    suspend fun updateProfileSmartMetrics(
        id: Long,
        latencyMs: Long?,
        tcpConnectMs: Long?,
        jitterMs: Long?,
        packetLossRate: Float?,
        successRate: Float?,
        healthScore: Int?,
        consecutiveFailures: Int,
        lastFailureMs: Long?,
        measureType: String,
        testedAtMs: Long = System.currentTimeMillis()
    )
}
