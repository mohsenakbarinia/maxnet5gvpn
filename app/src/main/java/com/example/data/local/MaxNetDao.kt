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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConnectionHistory(entry: ConnectionHistoryEntity): Long
}
