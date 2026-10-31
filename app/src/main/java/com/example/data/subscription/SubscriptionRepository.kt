package com.example.data.subscription

import com.example.data.local.MaxNetDao
import com.example.data.local.ProfileEntity
import com.example.data.local.ProfileGroupEntity
import com.example.data.local.SubscriptionEntity
import com.example.domain.model.SubscriptionFormat
import com.example.domain.model.SubscriptionImportReport
import com.example.domain.model.SubscriptionItem
import com.example.parser.ConfigParser
import com.example.security.LogRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

data class SubscriptionDownloadPayload(
    val finalUrl: String,
    val httpStatusCode: Int,
    val bodyText: String,
    val userInfo: Map<String, Long>,
    val updateIntervalHours: Int?,
    val profileTitleHeader: String?,
    val supportUrlHeader: String,
    val webPageUrlHeader: String,
    val announceHeader: String
)

class SubscriptionDownloadException(
    val statusCode: Int? = null,
    val messageFa: String,
    val messageEn: String,
    cause: Throwable? = null
) : Exception(messageEn, cause)

/**
 * Production HTTP/HTTPS Subscription Downloader & Transactional Import Pipeline.
 *
 * Capabilities:
 * - Supports HTTPS subscription URLs, custom ports (e.g., 2096), and paths such as /sub/{token}.
 * - Follows valid HTTP redirects (301, 302, 303, 307, 308) up to 5 hops while validating destinations
 *   and preventing HTTPS -> HTTP downgrade attacks.
 * - Handles HTTP 200, 301, 302, 403, 404, 429, and 5xx responses with clear localized diagnostics.
 * - Preserves strict TLS certificate verification (never disables SSL verification).
 * - Automatically detects Plain-text, Standard Base64, URL-safe Base64, JSON, and invalid HTML responses.
 * - Atomically updates subscription profiles only after a valid update succeeds.
 */
class SubscriptionRepository(
    private val dao: MaxNetDao,
    baseHttpClient: OkHttpClient? = null,
    private val onLog: (level: String, tag: String, rawMessage: String, secrets: Collection<String>) -> Unit = { _, _, _, _ -> }
) {
    companion object {
        const val MAX_SUBSCRIPTION_BYTES = 5L * 1024L * 1024L // 5 MB limit
        const val MAX_REDIRECT_HOPS = 5
        const val COMPATIBLE_USER_AGENT =
            "v2rayNG/1.8.23 MAXNET5G-VPN/2.5 (Android; Xray-core/26.10.29; sing-box/1.10)"
    }

    // Configure redirect handling manually so every redirect hop and scheme is strictly validated
    private val httpClient: OkHttpClient = (baseHttpClient ?: OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build())
        .newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Downloads raw subscription content from [rawUrl] with redirect validation,
     * status code handling, UTF-8 decoding, and HTML rejection.
     */
    suspend fun downloadSubscription(rawUrl: String): Result<SubscriptionDownloadPayload> = withContext(Dispatchers.IO) {
        val cleanUrl = rawUrl.trim().substringBefore("#").trim()
        val validatedUriResult = ConfigParser.validateSubscriptionUrl(cleanUrl)
        if (validatedUriResult.isFailure) {
            val reason = validatedUriResult.exceptionOrNull()?.message ?: "Invalid URL"
            return@withContext Result.failure(
                SubscriptionDownloadException(
                    statusCode = null,
                    messageFa = "فرمت آدرس لینک اشتراک نامعتبر است ($reason)",
                    messageEn = "Invalid subscription URL: $reason"
                )
            )
        }

        val initialHttpUrl = cleanUrl.toHttpUrlOrNull()
            ?: return@withContext Result.failure(
                SubscriptionDownloadException(
                    statusCode = null,
                    messageFa = "ساختار آدرس اشتراک نامعتبر است",
                    messageEn = "Malformed subscription URL"
                )
            )

        var currentUrl = initialHttpUrl
        val visitedUrls = mutableSetOf<String>()

        try {
            for (hop in 0..MAX_REDIRECT_HOPS) {
                visitedUrls.add(currentUrl.toString())

                val request = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", COMPATIBLE_USER_AGENT)
                    .header("Accept", "text/plain, application/json, */*")
                    .header("Accept-Charset", "UTF-8")
                    .get()
                    .build()

                val response: Response = httpClient.newCall(request).execute()
                response.use { resp ->
                    val code = resp.code

                    // Handle HTTP Redirects (301, 302, 303, 307, 308)
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        if (hop >= MAX_REDIRECT_HOPS) {
                            throw SubscriptionDownloadException(
                                statusCode = code,
                                messageFa = "تعداد تغییر مسیرهای (Redirect) لینک اشتراک بیش از حد مجاز است",
                                messageEn = "Too many HTTP redirects (exceeded $MAX_REDIRECT_HOPS hops)"
                            )
                        }
                        val location = resp.header("Location")?.trim()
                        if (location.isNullOrBlank()) {
                            throw SubscriptionDownloadException(
                                statusCode = code,
                                messageFa = "پاسخ تغییر مسیر (HTTP $code) فاقد آدرس مقصد معتبر است",
                                messageEn = "HTTP $code redirect is missing Location header"
                            )
                        }
                        val nextUrl = currentUrl.resolve(location)
                            ?: throw SubscriptionDownloadException(
                                statusCode = code,
                                messageFa = "آدرس مقصد تغییر مسیر (Redirect) نامعتبر است",
                                messageEn = "Invalid redirect destination URL"
                            )
                        if (nextUrl.scheme != "https" && nextUrl.scheme != "http") {
                            throw SubscriptionDownloadException(
                                statusCode = code,
                                messageFa = "پروتکل مقصد تغییر مسیر غیرمجاز است (${nextUrl.scheme})",
                                messageEn = "Unsupported redirect scheme: ${nextUrl.scheme}"
                            )
                        }
                        if (currentUrl.isHttps && !nextUrl.isHttps) {
                            throw SubscriptionDownloadException(
                                statusCode = code,
                                messageFa = "تغییر مسیر ناامن از HTTPS به HTTP مسدود شد",
                                messageEn = "Blocked insecure redirect downgrade from HTTPS to HTTP"
                            )
                        }
                        if (visitedUrls.contains(nextUrl.toString())) {
                            throw SubscriptionDownloadException(
                                statusCode = code,
                                messageFa = "حلقه تکرار در تغییر مسیر (Redirect Loop) شناسایی شد",
                                messageEn = "Redirect loop detected"
                            )
                        }
                        currentUrl = nextUrl
                        return@use
                    }

                    // Handle explicit HTTP error codes
                    when (code) {
                        200 -> Unit
                        401, 403 -> throw SubscriptionDownloadException(
                            statusCode = code,
                            messageFa = "اشتراک منقضی شده یا دسترسی غیرمجاز است (HTTP $code)",
                            messageEn = "Subscription expired or access forbidden (HTTP $code)"
                        )
                        404 -> throw SubscriptionDownloadException(
                            statusCode = code,
                            messageFa = "لینک اشتراک یافت نشد یا منقضی شده است (HTTP 404)",
                            messageEn = "Subscription endpoint not found or expired (HTTP 404)"
                        )
                        429 -> throw SubscriptionDownloadException(
                            statusCode = code,
                            messageFa = "تعداد درخواست‌ها بیش از حد مجاز است. لطفاً کمی صبر کنید (HTTP 429)",
                            messageEn = "Too many requests to subscription server (HTTP 429)"
                        )
                        in 500..599 -> throw SubscriptionDownloadException(
                            statusCode = code,
                            messageFa = "خطای داخلی سرور اشتراک (HTTP $code)",
                            messageEn = "Subscription server internal error (HTTP $code)"
                        )
                        else -> {
                            if (!resp.isSuccessful) {
                                throw SubscriptionDownloadException(
                                    statusCode = code,
                                    messageFa = "خطا در دریافت اطلاعات از سرور اشتراک (HTTP $code)",
                                    messageEn = "HTTP $code: ${resp.message}"
                                )
                            }
                        }
                    }

                    val contentType = resp.header("Content-Type").orEmpty()
                    if (contentType.contains("text/html", ignoreCase = true)) {
                        throw SubscriptionDownloadException(
                            statusCode = code,
                            messageFa = "به جای کانفیگ اشتراک، صفحه وب (HTML) دریافت شد",
                            messageEn = "Rejected HTML response from subscription endpoint"
                        )
                    }

                    val bodySource = resp.body?.source()
                        ?: throw SubscriptionDownloadException(
                            statusCode = code,
                            messageFa = "پاسخ دریافتی از سرور اشتراک خالی است",
                            messageEn = "Empty subscription response body"
                        )

                    bodySource.request(MAX_SUBSCRIPTION_BYTES + 1L)
                    if (bodySource.buffer.size > MAX_SUBSCRIPTION_BYTES) {
                        throw SubscriptionDownloadException(
                            statusCode = code,
                            messageFa = "حجم پاسخ اشتراک بیش از حد مجاز (۵ مگابایت) است",
                            messageEn = "Subscription response exceeds maximum 5 MB limit"
                        )
                    }

                    val bodyBytes = bodySource.readByteArray()
                    val bodyText = String(bodyBytes, Charsets.UTF_8).trim()
                    if (bodyText.isEmpty()) {
                        throw SubscriptionDownloadException(
                            statusCode = code,
                            messageFa = "پاسخ دریافتی از سرور اشتراک خالی است (Empty Response)",
                            messageEn = "Empty HTTP response body from subscription server"
                        )
                    }

                    if (ConfigParser.isHtmlPayload(bodyText)) {
                        throw SubscriptionDownloadException(
                            statusCode = code,
                            messageFa = "به جای کانفیگ، صفحه ورود یا خطای HTML دریافت شد",
                            messageEn = "Received HTML error/login page instead of subscription configs"
                        )
                    }

                    val userInfoHeader = resp.header("Subscription-Userinfo")
                        ?: resp.header("subscription-userinfo")
                    val parsedUserInfo = parseSubscriptionUserInfo(userInfoHeader)
                    val intervalHeader = (resp.header("Profile-Update-Interval")
                        ?: resp.header("profile-update-interval"))?.toIntOrNull()
                    val titleHeader = decodeProfileTitleHeader(
                        resp.header("Profile-Title") ?: resp.header("profile-title")
                    )
                    val supportUrlHeader = resp.header("Support-Url").orEmpty()
                    val webPageUrlHeader = resp.header("Profile-Web-Page-Url").orEmpty()
                    val announceHeader = resp.header("Announce").orEmpty()

                    return@withContext Result.success(
                        SubscriptionDownloadPayload(
                            finalUrl = currentUrl.toString(),
                            httpStatusCode = code,
                            bodyText = bodyText,
                            userInfo = parsedUserInfo,
                            updateIntervalHours = intervalHeader,
                            profileTitleHeader = titleHeader,
                            supportUrlHeader = supportUrlHeader,
                            webPageUrlHeader = webPageUrlHeader,
                            announceHeader = announceHeader
                        )
                    )
                }
            }

            Result.failure(
                SubscriptionDownloadException(
                    statusCode = null,
                    messageFa = "تعداد تغییر مسیرهای (Redirect) لینک اشتراک بیش از حد مجاز است",
                    messageEn = "Too many HTTP redirects"
                )
            )
        } catch (e: SubscriptionDownloadException) {
            Result.failure(e)
        } catch (e: SSLHandshakeException) {
            Result.failure(
                SubscriptionDownloadException(
                    statusCode = null,
                    messageFa = "خطای اعتبارسنجی گواهی امنیتی SSL/TLS سرور اشتراک",
                    messageEn = "SSL/TLS certificate validation failed: ${e.message}",
                    cause = e
                )
            )
        } catch (e: SSLPeerUnverifiedException) {
            Result.failure(
                SubscriptionDownloadException(
                    statusCode = null,
                    messageFa = "هویت گواهی SSL سرور اشتراک قابل تأیید نیست",
                    messageEn = "SSL peer certificate unverified: ${e.message}",
                    cause = e
                )
            )
        } catch (e: SSLException) {
            Result.failure(
                SubscriptionDownloadException(
                    statusCode = null,
                    messageFa = "خطای ارتباط امن SSL/TLS با سرور اشتراک",
                    messageEn = "SSL/TLS error: ${e.message}",
                    cause = e
                )
            )
        } catch (e: CertificateException) {
            Result.failure(
                SubscriptionDownloadException(
                    statusCode = null,
                    messageFa = "گواهی امنیتی سرور اشتراک نامعتبر است",
                    messageEn = "Invalid server certificate: ${e.message}",
                    cause = e
                )
            )
        } catch (e: SocketTimeoutException) {
            Result.failure(
                SubscriptionDownloadException(
                    statusCode = null,
                    messageFa = "مهلت زمانی اتصال به سرور اشتراک به پایان رسید (Timeout)",
                    messageEn = "Connection or read timed out while fetching subscription",
                    cause = e
                )
            )
        } catch (e: InterruptedIOException) {
            Result.failure(
                SubscriptionDownloadException(
                    statusCode = null,
                    messageFa = "مهلت زمانی دریافت اطلاعات از سرور به پایان رسید (Timeout)",
                    messageEn = "Network operation timed out",
                    cause = e
                )
            )
        } catch (e: UnknownHostException) {
            Result.failure(
                SubscriptionDownloadException(
                    statusCode = null,
                    messageFa = "آدرس سرور اشتراک یافت نشد (خطای DNS یا عدم اتصال به اینترنت)",
                    messageEn = "Unable to resolve subscription host",
                    cause = e
                )
            )
        } catch (e: ConnectException) {
            Result.failure(
                SubscriptionDownloadException(
                    statusCode = null,
                    messageFa = "امکان برقراری اتصال با سرور اشتراک وجود ندارد",
                    messageEn = "Connection refused by subscription server",
                    cause = e
                )
            )
        } catch (e: Exception) {
            val safeMessage = LogRedactor.redact(e.message ?: "Network error", listOf(cleanUrl))
            Result.failure(
                SubscriptionDownloadException(
                    statusCode = null,
                    messageFa = "خطا در دریافت اشتراک: $safeMessage",
                    messageEn = "Subscription download failed: $safeMessage",
                    cause = e
                )
            )
        }
    }

    /**
     * Adds a new subscription or updates an existing subscription with the same URL,
     * downloads and parses its configurations, saves them transactionally, and returns
     * a detailed [SubscriptionImportReport].
     */
    suspend fun addOrUpdateSubscription(
        title: String,
        url: String,
        autoUpdate: Boolean
    ): Pair<Result<SubscriptionItem>, SubscriptionImportReport> = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim().substringBefore("#").trim()
        val urlValidation = ConfigParser.validateSubscriptionUrl(cleanUrl)
        if (urlValidation.isFailure) {
            val err = urlValidation.exceptionOrNull()?.message ?: "Invalid subscription URL"
            val report = SubscriptionImportReport(
                title = title.ifBlank { "اشتراک" },
                sourceType = "SUBSCRIPTION",
                detectedFormat = SubscriptionFormat.INVALID,
                totalReceivedCount = 0,
                newImportedCount = 0,
                duplicateCount = 0,
                invalidCount = 0,
                statusMessageFa = "آدرس لینک اشتراک نامعتبر است: $err",
                statusMessageEn = "Invalid subscription URL: $err",
                isSuccess = false
            )
            return@withContext Result.failure<SubscriptionItem>(IllegalArgumentException(err)) to report
        }

        val uri = urlValidation.getOrThrow()
        val fragmentName = url.substringAfter("#", "").let {
            if (it.isNotBlank()) ConfigParser.percentDecode(it).trim() else ""
        }
        val resolvedTitle = title.trim().ifBlank {
            fragmentName.ifBlank {
                val host = uri.host?.lowercase().orEmpty()
                if (host.isNotBlank()) "Sub ($host)" else "Subscription"
            }
        }

        // Reuse existing subscription record if the same URL already exists
        val existingSub = dao.getAllSubscriptionsSnapshot()
            .map { it.toDomain() }
            .find { it.url.equals(cleanUrl, ignoreCase = true) }

        val targetSub = if (existingSub != null) {
            existingSub.copy(
                title = if (title.isNotBlank() && title != "Subscription") title.trim() else existingSub.title,
                enabledAutoUpdate = autoUpdate
            )
        } else {
            val newItem = SubscriptionItem(
                title = resolvedTitle,
                url = cleanUrl,
                enabledAutoUpdate = autoUpdate
            )
            val newId = dao.insertSubscription(SubscriptionEntity.fromDomain(newItem))
            newItem.copy(id = newId)
        }

        val (result, report) = refreshSubscriptionWithReport(targetSub, isNewCreation = existingSub == null)
        // If this was a newly created subscription row and initial download failed, keep or clean up?
        // Keeping the subscription item with error status allows the user to retry with the manual refresh button,
        // or if the URL itself was unreachable, they can see the status in Subscriptions tab.
        result to report
    }

    /**
     * Refreshes an existing [SubscriptionItem] and returns both the Result and a detailed [SubscriptionImportReport].
     * Preserves existing profiles belonging to this subscription until the new update succeeds.
     */
    suspend fun refreshSubscriptionWithReport(
        sub: SubscriptionItem,
        isNewCreation: Boolean = false
    ): Pair<Result<SubscriptionItem>, SubscriptionImportReport> = withContext(Dispatchers.IO) {
        val downloadResult = downloadSubscription(sub.url)
        if (downloadResult.isFailure) {
            val err = downloadResult.exceptionOrNull()
            val dlEx = err as? SubscriptionDownloadException
            val msgFa = dlEx?.messageFa ?: "خطا در دریافت اشتراک: ${err?.message ?: "نامشخص"}"
            val msgEn = dlEx?.messageEn ?: (err?.message ?: "Subscription update failed")
            val safeMsgEn = LogRedactor.redact(msgEn, listOf(sub.url))

            val failedSub = sub.copy(
                lastUpdatedAt = System.currentTimeMillis(),
                lastStatus = "Error: $safeMsgEn"
            )
            dao.updateSubscription(SubscriptionEntity.fromDomain(failedSub))
            onLog("ERROR", "Subscription", "Subscription '${sub.title}' failed: $safeMsgEn", listOf(sub.url))

            val report = SubscriptionImportReport(
                title = sub.title,
                sourceType = "SUBSCRIPTION",
                detectedFormat = SubscriptionFormat.INVALID,
                totalReceivedCount = 0,
                newImportedCount = 0,
                duplicateCount = 0,
                invalidCount = 0,
                statusMessageFa = msgFa,
                statusMessageEn = safeMsgEn,
                isSuccess = false,
                httpStatusCode = dlEx?.statusCode
            )
            return@withContext Result.failure<SubscriptionItem>(err ?: IllegalStateException(safeMsgEn)) to report
        }

        val payload = downloadResult.getOrThrow()
        val (detectedFormat, _) = ConfigParser.detectSubscriptionFormat(payload.bodyText)

        // Snapshot existing profiles in database before modifying anything
        val allBefore = dao.getAllProfilesSnapshot().map { it.toDomain() }
        val otherProfilesFingerprints = allBefore
            .filter { it.subscriptionId != sub.id }
            .map { it.uniqueFingerprintKey() }
            .toSet()
        val previousThisSubFingerprints = allBefore
            .filter { it.subscriptionId == sub.id }
            .map { it.uniqueFingerprintKey() }
            .toSet()

        val effectiveTitle = if (!payload.profileTitleHeader.isNullOrBlank() &&
            (sub.title == "Subscription" || sub.title.startsWith("Sub ("))
        ) {
            payload.profileTitleHeader
        } else {
            sub.title
        }

        // Parse batch against other profiles (manual + other subscriptions) so this subscription's profiles
        // can be cleanly replaced while skipping duplicates that already exist in manual/other subscriptions
        // or duplicates within the downloaded batch itself.
        val batch = ConfigParser.parseBatch(
            rawInput = payload.bodyText,
            defaultGroup = effectiveTitle,
            subscriptionId = sub.id,
            existingFingerprintKeys = otherProfilesFingerprints
        )

        if (batch.profiles.isEmpty() && batch.duplicatesSkipped == 0) {
            val firstReason = batch.failures.firstOrNull()?.reason ?: "No valid server configurations found"
            val msgFa = if (detectedFormat == SubscriptionFormat.INVALID) {
                "فرمت پاسخ دریافتی نامعتبر است یا هیچ کانفیگ معتبری در لینک اشتراک یافت نشد"
            } else {
                "هیچ کانفیگ معتبری در اشتراک یافت نشد ($firstReason)"
            }
            val failedSub = sub.copy(
                lastUpdatedAt = System.currentTimeMillis(),
                lastStatus = "Error: $firstReason"
            )
            dao.updateSubscription(SubscriptionEntity.fromDomain(failedSub))
            onLog("ERROR", "Subscription", "Subscription '${sub.title}' parse failed: $firstReason", listOf(sub.url))

            val report = SubscriptionImportReport(
                title = effectiveTitle,
                sourceType = "SUBSCRIPTION",
                detectedFormat = detectedFormat,
                totalReceivedCount = batch.totalReceivedCount,
                newImportedCount = 0,
                duplicateCount = batch.duplicatesSkipped,
                invalidCount = batch.failures.size,
                statusMessageFa = msgFa,
                statusMessageEn = firstReason,
                isSuccess = false,
                httpStatusCode = payload.httpStatusCode
            )
            return@withContext Result.failure<SubscriptionItem>(IllegalStateException(firstReason)) to report
        }

        // Count how many of batch.profiles were already present in previousThisSubFingerprints
        val retainedFromPreviousCount = if (isNewCreation) {
            0
        } else {
            batch.profiles.count { previousThisSubFingerprints.contains(it.uniqueFingerprintKey()) }
        }
        val genuinelyNewCount = (batch.profiles.size - retainedFromPreviousCount).coerceAtLeast(0)
        val totalDuplicatesCount = batch.duplicatesSkipped + retainedFromPreviousCount

        if (effectiveTitle.isNotBlank() && effectiveTitle != "Default") {
            dao.insertGroup(ProfileGroupEntity(name = effectiveTitle))
        }

        val newEntities = batch.profiles.mapIndexed { idx, p ->
            ProfileEntity.fromDomain(
                p.copy(
                    subscriptionId = sub.id,
                    groupName = effectiveTitle,
                    sortOrder = idx
                )
            )
        }

        val updatedSub = sub.copy(
            title = effectiveTitle,
            updateIntervalHours = payload.updateIntervalHours ?: sub.updateIntervalHours,
            lastUpdatedAt = System.currentTimeMillis(),
            lastStatus = "OK (${batch.profiles.size} servers)",
            uploadBytes = payload.userInfo["upload"],
            downloadBytes = payload.userInfo["download"],
            totalBytes = payload.userInfo["total"],
            expireTimestampSec = payload.userInfo["expire"],
            supportUrl = payload.supportUrlHeader.ifBlank { sub.supportUrl },
            webPageUrl = payload.webPageUrlHeader.ifBlank { sub.webPageUrl },
            announcement = payload.announceHeader.ifBlank { sub.announcement },
            profileCount = batch.profiles.size
        )

        // Atomic database transaction: only replaces this subscription's profiles after everything succeeded
        if (batch.profiles.isNotEmpty()) {
            dao.replaceSubscriptionProfilesTransaction(
                subscriptionId = sub.id,
                newProfiles = newEntities,
                updatedSubscription = SubscriptionEntity.fromDomain(updatedSub)
            )
        } else {
            // All profiles in the subscription already existed in manual/other profiles
            dao.updateSubscription(SubscriptionEntity.fromDomain(updatedSub))
        }

        onLog(
            "INFO",
            "Subscription",
            "Updated '${updatedSub.title}': total=${batch.totalReceivedCount}, new=$genuinelyNewCount, duplicate=$totalDuplicatesCount, invalid=${batch.failures.size}",
            listOf(sub.url)
        )

        val statusFa = "دریافت و بروزرسانی اشتراک با موفقیت انجام شد (${detectedFormat.labelFa})"
        val statusEn = "Subscription imported successfully (${detectedFormat.labelEn})"

        val report = SubscriptionImportReport(
            title = updatedSub.title,
            sourceType = "SUBSCRIPTION",
            detectedFormat = detectedFormat,
            totalReceivedCount = batch.totalReceivedCount,
            newImportedCount = genuinelyNewCount,
            duplicateCount = totalDuplicatesCount,
            invalidCount = batch.failures.size,
            statusMessageFa = statusFa,
            statusMessageEn = statusEn,
            isSuccess = true,
            httpStatusCode = payload.httpStatusCode,
            subscriptionItem = updatedSub
        )

        Result.success(updatedSub) to report
    }

    private fun decodeProfileTitleHeader(rawHeader: String?): String? {
        if (rawHeader.isNullOrBlank()) return null
        val trimmed = rawHeader.trim()
        if (trimmed.startsWith("base64:", ignoreCase = true)) {
            val b64 = trimmed.substringAfter(":").trim()
            return ConfigParser.decodeBase64Flexible(b64)?.trim()?.takeIf { it.isNotBlank() }
        }
        return trimmed
    }

    private fun parseSubscriptionUserInfo(header: String?): Map<String, Long> {
        if (header.isNullOrBlank()) return emptyMap()
        val map = mutableMapOf<String, Long>()
        header.split(";").forEach { part ->
            val kv = part.trim().split("=")
            if (kv.size == 2) {
                val k = kv[0].trim().lowercase()
                val v = kv[1].trim().toLongOrNull()
                if (v != null) map[k] = v
            }
        }
        return map
    }
}
