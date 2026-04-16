package com.scrapw.chatbox

import android.util.Log
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path

interface GitHubReleaseService {
    @GET("/repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<GitHubRelease>

    @Keep
    data class GitHubRelease(
        @SerializedName("tag_name")
        val tagName: String,
        val assets: List<GitHubAsset>
    )

    @Keep
    data class GitHubAsset(
        @SerializedName("browser_download_url")
        val browserDownloadUrl: String
    )
}

enum class UpdateStatus {
    NOT_CHECKED,
    FAILED,
    UP_TO_DATE,
    AVAILABLE
}

data class UpdateInfo(
    val status: UpdateStatus,
    val version: String? = null,
    val downloadUrl: String? = null
)

suspend fun checkUpdate(owner: String, repo: String): UpdateInfo {
    // NOTE: The active update system for the public build is checkFirestoreRelease() in InAppUpdate.kt.
    // This function is used for the admin build's self-update check via the GitHub Releases API.
    //
    // Tag name format published by ReleasesTab: "v{versionCode}" (e.g. "v279").
    // BuildConfig.VERSION_NAME is "v1.2.79" — these never match, so we parse the versionCode
    // integer from the tag instead of doing a string equality check.

    val retrofit = Retrofit.Builder()
        .baseUrl("https://api.github.com")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val releaseService = retrofit.create(GitHubReleaseService::class.java)

    try {
        lateinit var response: Response<GitHubReleaseService.GitHubRelease>

        withContext(Dispatchers.IO) {
            response = releaseService.getLatestRelease(owner, repo)
        }

        if (response.isSuccessful) {
            val data = response.body()

            if (data != null && data.assets.isNotEmpty()) {
                // Tag is "v{versionCode}" — strip the leading "v" and parse as int.
                val remoteCode = data.tagName.trimStart('v', 'V').toIntOrNull() ?: -1
                val localCode  = BuildConfig.VERSION_CODE

                return if (remoteCode <= localCode) {
                    Log.d("Update", "Up to date. local=$localCode remote=$remoteCode")
                    UpdateInfo(UpdateStatus.UP_TO_DATE, data.tagName, data.assets[0].browserDownloadUrl)
                } else {
                    Log.d("Update", "New version available: ${data.tagName} (remote=$remoteCode > local=$localCode)")
                    UpdateInfo(UpdateStatus.AVAILABLE, data.tagName, data.assets[0].browserDownloadUrl)
                }
            }
        } else {
            Log.d("Update", "Response failed: ${response.code()}")
        }
    } catch (e: Exception) {
        Log.d("Update", "Failed with exception.")
        e.printStackTrace()
    }

    return UpdateInfo(UpdateStatus.FAILED)
}
