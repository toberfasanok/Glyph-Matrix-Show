package com.tober.glyphmatrix.show

import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

import com.tober.glyphmatrix.show.ui.theme.GlyphMatrixShowTheme

import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

data class Glyph(
    val glyph: String
)

class MainActivity : ComponentActivity() {
    private val tag = "Main Activity"

    private var hasAccessibilityServiceAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        hasAccessibilityServiceAccess = getAccessibilityServiceAccess()

        val preferences = getSharedPreferences(Constants.PREFERENCES_NAME, MODE_PRIVATE)

        val glyphs = mutableStateListOf<Glyph>().apply { addAll(readGlyphMappings()) }
        var newGlyph by mutableStateOf("")

        val glyphsImageLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri == null) {
                return@registerForActivityResult
            }

            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {}

            try {
                val newFile = File(filesDir, "tmp_glyph_${System.currentTimeMillis()}.png")

                filesDir.listFiles()?.filter { it.name.startsWith("tmp_glyph_") && it.name.endsWith(".png") && it.absolutePath != newFile.absolutePath }
                    ?.forEach { try { it.delete() } catch (_: Throwable) {} }

                contentResolver.openInputStream(uri).use { inputStream ->
                    if (inputStream == null) {
                        toast("Failed to open selected image")
                        return@registerForActivityResult
                    }

                    FileOutputStream(newFile).use { out ->
                        val buffer = ByteArray(8 * 1024)

                        while (true) {
                            val read = inputStream.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                        }

                        out.flush()
                    }
                }

                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(newFile.absolutePath, options)
                val width = options.outWidth
                val height = options.outHeight

                if (width != height) {
                    toast("Image must be 1:1 (square)")
                }
                else {
                    newGlyph = newFile.absolutePath
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to save tmp glyph: $e")
                toast("Failed to save tmp glyph")
            }
        }

        setContent {
            GlyphMatrixShowTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Top
                    ) {
                        if (!hasAccessibilityServiceAccess) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(text = "Accessibility service access is required for the app to detect when the phone is opened and show glyphs automatically.")

                                Spacer(modifier = Modifier.height(25.dp))

                                Text(text = "1. Allow Restricted Settings:", fontWeight = FontWeight.Bold)
                                Text(text = "App info -> ⋮ (top right) -> Allow Restricted Settings")

                                Button(
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", packageName, null)
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        startActivity(intent)
                                    },
                                    modifier = Modifier.padding(top = 12.dp)
                                ) {
                                    Text(text = "Open App Info")
                                }

                                Spacer(modifier = Modifier.height(25.dp))

                                Text(text = "2. Allow Accessibility Service Access:", fontWeight = FontWeight.Bold)
                                Text(text = "Glyph Matrix Show -> Use Glyph Matrix Show -> Allow")

                                Button(
                                    onClick = {
                                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    },
                                    modifier = Modifier.padding(top = 12.dp)
                                ) {
                                    Text(text = "Open Accessibility Service Access Settings")
                                }
                            }
                        } else {
                            Column(modifier = Modifier.padding(8.dp)) {
                                var active by rememberSaveable { mutableStateOf(preferences.getBoolean(Constants.PREFERENCES_ACTIVE, true)) }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "App Active",
                                        style = MaterialTheme.typography.bodyLarge
                                    )

                                    Switch(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        checked = active,
                                        onCheckedChange = { checked ->
                                            active = checked
                                            preferences.edit { putBoolean(Constants.PREFERENCES_ACTIVE, checked) }
                                            broadcastPreferencesUpdate()
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(25.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(10.dp))

                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(text = "Glyph Timeout", modifier = Modifier.padding(bottom = 8.dp))

                                var savedGlyphTimeout by rememberSaveable { mutableStateOf(preferences.getLong(Constants.PREFERENCES_GLYPH_TIMEOUT, 5L).toString()) }

                                OutlinedTextField(
                                    value = savedGlyphTimeout,
                                    onValueChange = { value ->
                                        val filtered = value.filter { it.isDigit() }
                                        savedGlyphTimeout = filtered
                                    },
                                    label = { Text("Timeout (seconds)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.padding(top = 12.dp)
                                )

                                Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        val timeout = savedGlyphTimeout.toLongOrNull() ?: 5L
                                        preferences.edit { putLong(Constants.PREFERENCES_GLYPH_TIMEOUT, timeout) }
                                        broadcastPreferencesUpdate()
                                        toast("Timeout saved")
                                    }) {
                                        Text(text = "Save")
                                    }

                                    Button(onClick = {
                                        savedGlyphTimeout = "5"
                                        preferences.edit { putLong(Constants.PREFERENCES_GLYPH_TIMEOUT, 5L) }
                                        broadcastPreferencesUpdate()
                                        toast("Timeout reset")
                                    }) {
                                        Text(text = "Reset")
                                    }
                                }

                                Spacer(modifier = Modifier.height(15.dp))

                                var animateGlyphs by rememberSaveable { mutableStateOf(preferences.getBoolean(Constants.PREFERENCES_ANIMATE_GLYPHS, true)) }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Animate Glyphs",
                                        style = MaterialTheme.typography.bodyLarge
                                    )

                                    Switch(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        checked = animateGlyphs,
                                        onCheckedChange = { checked ->
                                            animateGlyphs = checked
                                            preferences.edit { putBoolean(Constants.PREFERENCES_ANIMATE_GLYPHS, checked) }
                                            broadcastPreferencesUpdate()
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        )
                                    )
                                }

                                if (animateGlyphs) {
                                    Spacer(modifier = Modifier.height(15.dp))

                                    Text(text = "Animate Speed", modifier = Modifier.padding(bottom = 8.dp))

                                    var savedAnimateSpeed by rememberSaveable { mutableStateOf(preferences.getLong(Constants.PREFERENCES_ANIMATE_SPEED, 10L).toString()) }

                                    OutlinedTextField(
                                        value = savedAnimateSpeed,
                                        onValueChange = { value ->
                                            val filtered = value.filter { it.isDigit() }
                                            savedAnimateSpeed = filtered
                                        },
                                        label = { Text("Speed (milliseconds)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.padding(top = 12.dp)
                                    )

                                    Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = {
                                            val animateSpeed = savedAnimateSpeed.toLongOrNull() ?: 10L
                                            preferences.edit { putLong(Constants.PREFERENCES_ANIMATE_SPEED, animateSpeed) }
                                            broadcastPreferencesUpdate()
                                            toast("Animate speed saved")
                                        }) {
                                            Text(text = "Save")
                                        }

                                        Button(onClick = {
                                            savedAnimateSpeed = "10"
                                            preferences.edit { putLong(Constants.PREFERENCES_ANIMATE_SPEED, 10L) }
                                            broadcastPreferencesUpdate()
                                            toast("Animate speed reset")
                                        }) {
                                            Text(text = "Reset")
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(25.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(10.dp))

                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(text = "Glyphs", modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))

                                Card(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        val tmpGlyph = remember(newGlyph) {
                                            newGlyph.takeIf { it.isNotBlank() }?.let { BitmapFactory.decodeFile(it) }
                                        }

                                        if (tmpGlyph != null) {
                                            Image(
                                                painter = BitmapPainter(tmpGlyph.asImageBitmap(), filterQuality = FilterQuality.None),
                                                contentDescription = "Glyph Preview",
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { glyphsImageLauncher.launch(arrayOf("image/*")) }
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                                    .clickable { glyphsImageLauncher.launch(arrayOf("image/*")) },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(text = "+", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }

                                        Button(onClick = {
                                            if (newGlyph.isBlank()) {
                                                toast("Choose a glyph")
                                                return@Button
                                            }

                                            val dest = File(filesDir, "glyph_${System.currentTimeMillis()}.png")

                                            try {
                                                File(newGlyph).copyTo(dest, overwrite = true)
                                            } catch (e: Exception) {
                                                Log.e(tag, "Failed to save glyph: $e")
                                                toast("Failed to save glyph")
                                                return@Button
                                            }

                                            glyphs.add(Glyph(dest.absolutePath))
                                            writeGlyphMappings(glyphs)

                                            newGlyph = ""
                                            toast("Glyph saved")
                                        }) {
                                            Text(text = "+")
                                        }
                                    }
                                }

                                for (item in glyphs) {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            val savedGlyph = remember(item.glyph) {
                                                try { BitmapFactory.decodeFile(item.glyph) } catch (_: Throwable) { null }
                                            }

                                            if (savedGlyph != null) {
                                                Image(
                                                    painter = BitmapPainter(savedGlyph.asImageBitmap(), filterQuality = FilterQuality.None),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(56.dp)
                                                )
                                            } else {
                                                Spacer(modifier = Modifier.size(56.dp))
                                            }

                                            Button(onClick = {
                                                glyphs.remove(item)
                                                writeGlyphMappings(glyphs)
                                                toast("Glyph removed")
                                            }) {
                                                Text(text = "-")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityServiceAccessState()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun getAccessibilityServiceAccess(): Boolean {
        val expectedComponentName = ComponentName(this, UnlockAccessibilityService::class.java)

        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')

        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val component = colonSplitter.next()
            if (component.equals(expectedComponentName.flattenToString(), ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun updateAccessibilityServiceAccessState() {
        hasAccessibilityServiceAccess = getAccessibilityServiceAccess()
    }

    private fun broadcastPreferencesUpdate() {
        val preferences = getSharedPreferences(Constants.PREFERENCES_NAME, MODE_PRIVATE)

        val active = preferences.getBoolean(Constants.PREFERENCES_ACTIVE, true)

        val glyphTimeout = preferences.getLong(Constants.PREFERENCES_GLYPH_TIMEOUT, 5L)
        val animateGlyphs = preferences.getBoolean(Constants.PREFERENCES_ANIMATE_GLYPHS, true)
        val animateSpeed = preferences.getLong(Constants.PREFERENCES_ANIMATE_SPEED, 10L)

        val glyphs = preferences.getString(Constants.PREFERENCES_GLYPHS, null)

        val intent = Intent(Constants.ACTION_ON_PREFERENCES_UPDATE).apply {
            putExtra(Constants.PREFERENCES_ACTIVE, active)

            putExtra(Constants.PREFERENCES_GLYPH_TIMEOUT, glyphTimeout)
            putExtra(Constants.PREFERENCES_ANIMATE_GLYPHS, animateGlyphs)
            putExtra(Constants.PREFERENCES_ANIMATE_SPEED, animateSpeed)

            putExtra(Constants.PREFERENCES_GLYPHS, glyphs)
        }

        sendBroadcast(intent)
    }

    private fun readGlyphMappings(): MutableList<Glyph> {
        val preferences = getSharedPreferences(Constants.PREFERENCES_NAME, MODE_PRIVATE)
        val raw = preferences.getString(Constants.PREFERENCES_GLYPHS, null) ?: return mutableListOf()
        val list = mutableListOf<Glyph>()

        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val glyph = obj.optString(Constants.GLYPH_GLYPH)

            list.add(Glyph(glyph))
        }

        return list
    }

    private fun writeGlyphMappings(list: List<Glyph>) {
        val arr = JSONArray()

        for ((glyph) in list) {
            val obj = JSONObject()
            obj.put(Constants.GLYPH_GLYPH, glyph)
            arr.put(obj)
        }

        val preferences = getSharedPreferences(Constants.PREFERENCES_NAME, MODE_PRIVATE)
        preferences.edit { putString(Constants.PREFERENCES_GLYPHS, arr.toString()) }

        broadcastPreferencesUpdate()
    }

    private fun toast(message: String) {
        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
    }
}
