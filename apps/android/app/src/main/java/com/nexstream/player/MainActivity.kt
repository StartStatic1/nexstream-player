package com.nexstream.player

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID

/**
 * Generates and persists the device identity used for activation.
 * The Device ID is NOT the hardware MAC: it is derived from a random UUID and
 * formatted MAC-style, with the locally-administered bit set and multicast bit cleared.
 */
object DeviceIdentity {
    private const val PREFS = "nexstream_identity"
    private const val KEY_ID = "device_id"
    private const val KEY_KEY = "device_key"
    private const val KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val KEY_LENGTH = 8

    data class Identity(val deviceId: String, val deviceKey: String)

    fun get(context: Context): Identity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedId = prefs.getString(KEY_ID, null)
        val storedKey = prefs.getString(KEY_KEY, null)
        if (storedId != null && storedKey != null) {
            return Identity(storedId, storedKey)
        }
        val identity = Identity(generateDeviceId(), generateDeviceKey())
        prefs.edit()
            .putString(KEY_ID, identity.deviceId)
            .putString(KEY_KEY, identity.deviceKey)
            .apply()
        return identity
    }

    fun generateDeviceId(): String {
        val uuid = UUID.randomUUID()
        val buffer = ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        val bytes = buffer.array().copyOfRange(10, 16)
        // Set locally-administered bit (0x02), clear multicast bit (0x01).
        bytes[0] = ((bytes[0].toInt() or 0x02) and 0xFE).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }

    fun generateDeviceKey(): String {
        val random = SecureRandom()
        return buildString(KEY_LENGTH) {
            repeat(KEY_LENGTH) { append(KEY_ALPHABET[random.nextInt(KEY_ALPHABET.length)]) }
        }
    }
}

/** Minimal API client using HttpURLConnection. */
object NexApi {
    data class Result(val ok: Boolean, val message: String)

    suspend fun register(identity: DeviceIdentity.Identity, isTv: Boolean): Result =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("deviceId", identity.deviceId)
                .put("deviceKey", identity.deviceKey)
                .put("platform", if (isTv) "android-tv" else "android")
                .put("appVersion", BuildConfig.VERSION_NAME)
                .toString()
            request("POST", "/devices/register", body, identity)
        }

    suspend fun status(identity: DeviceIdentity.Identity): Result =
        withContext(Dispatchers.IO) { request("GET", "/devices/status", null, identity) }

    private fun request(
        method: String,
        path: String,
        body: String?,
        identity: DeviceIdentity.Identity
    ): Result {
        val connection = URL(BuildConfig.API_BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("x-device-id", identity.deviceId)
            connection.setRequestProperty("x-device-key", identity.deviceKey)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = if (text.isNotBlank()) JSONObject(text) else JSONObject()
            if (code in 200..299) {
                Result(true, describeLicense(json))
            } else {
                Result(false, "Error $code: ${json.optString("error", "unknown_error")}")
            }
        } catch (e: IOException) {
            Result(false, e.message ?: e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun describeLicense(json: JSONObject): String {
        val license = json.optJSONObject("device")?.optJSONObject("license")
            ?: return "Registered"
        val type = license.optString("type", "unknown").replaceFirstChar { it.uppercase() }
        val active = license.optBoolean("active", false)
        val days = if (license.isNull("daysLeft")) null else license.optInt("daysLeft")
        return buildString {
            append(if (active) "Active" else "Inactive")
            append(" · ")
            append(type)
            if (days != null) append(" · $days day(s) left")
        }
    }
}

private val NexColors = darkColorScheme(
    primary = Color(0xFF7C5CFF),
    secondary = Color(0xFF22D3EE),
    background = Color(0xFF0B0D17),
    surface = Color(0xFF151827),
    onPrimary = Color.White,
    onBackground = Color(0xFFE6E8F2),
    onSurface = Color(0xFFE6E8F2)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val identity = DeviceIdentity.get(applicationContext)
        val isTv = packageManager.hasSystemFeature("android.software.leanback") ||
            (Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature("android.software.leanback_only"))
        setContent {
            MaterialTheme(colorScheme = NexColors) {
                ActivationScreen(identity = identity, isTv = isTv)
            }
        }
    }
}

@Composable
fun ActivationScreen(identity: DeviceIdentity.Identity, isTv: Boolean) {
    val scope = rememberCoroutineScope()
    val connecting = stringResource(R.string.status_connecting)
    var status by remember { mutableStateOf(connecting) }
    var ok by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(identity) {
        val result = NexApi.register(identity, isTv)
        status = result.message
        ok = result.ok
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.app_name),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.activation_title),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.activation_subtitle),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                fontSize = 14.sp
            )
            CredentialCard(stringResource(R.string.label_device_id), identity.deviceId)
            CredentialCard(stringResource(R.string.label_device_key), identity.deviceKey)
            CredentialCard(
                label = stringResource(R.string.label_status),
                value = status,
                valueColor = when (ok) {
                    true -> Color(0xFF34D399)
                    false -> Color(0xFFF87171)
                    null -> MaterialTheme.colorScheme.onSurface
                },
                monospace = false
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    status = connecting
                    ok = null
                    scope.launch {
                        val result = NexApi.status(identity)
                        status = result.message
                        ok = result.ok
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(stringResource(R.string.action_refresh))
            }
        }
    }
}

@Composable
private fun CredentialCard(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    monospace: Boolean = true
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = label.uppercase(),
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                color = valueColor,
                fontSize = if (monospace) 24.sp else 16.sp,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                fontWeight = if (monospace) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
