package com.tober.glyphmatrix.show

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.activity.result.ActivityResultLauncher
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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

    private lateinit var preferences: SharedPreferences

    private var active by mutableStateOf(true)

    private var glyphTimeout by mutableStateOf("5")
    private var animateGlyphs by mutableStateOf(true)
    private var animateSpeed by mutableStateOf("10")

    private val glyphs = mutableStateListOf<Glyph>()
    private var newGlyph by mutableStateOf("")

    private var loadImageLauncherCallback: ((String) -> Unit)? = null
    private lateinit var loadImageLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        hasAccessibilityServiceAccess = getAccessibilityServiceAccess()

        preferences = getSharedPreferences(Constants.PREFERENCES_NAME, MODE_PRIVATE)

        active = preferences.getBoolean(Constants.PREFERENCES_ACTIVE, true)

        glyphTimeout = preferences.getLong(Constants.PREFERENCES_GLYPH_TIMEOUT, 5L).toString()
        animateGlyphs = preferences.getBoolean(Constants.PREFERENCES_ANIMATE_GLYPHS, true)
        animateSpeed = preferences.getLong(Constants.PREFERENCES_ANIMATE_SPEED, 10L).toString()

        glyphs.clear(); glyphs.addAll(readGlyphMappings())

        loadImageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) {
                return@registerForActivityResult
            }

            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {}

            try {
                val newFile = File(filesDir, "tmp_image_${System.currentTimeMillis()}.png")

                filesDir.listFiles()?.filter { it.name.startsWith("tmp_image_") && it.name.endsWith(".png") && it.absolutePath != newFile.absolutePath }
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
                } else {
                    try {
                        loadImageLauncherCallback?.invoke(newFile.absolutePath)
                    } finally {
                        loadImageLauncherCallback = null
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to load image: $e")
                toast("Failed to load image")
            }
        }

        setContent {
            GlyphMatrixShowTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
                    val focusManager = LocalFocusManager.current
                    val keyboardController = LocalSoftwareKeyboardController.current

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

                                Text(text = "Allow Restricted Settings:", fontWeight = FontWeight.Bold)
                                Text(text = "App Info -> ⋮ (top right) -> Allow Restricted Settings")

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

                                if (!hasAccessibilityServiceAccess) {
                                    Spacer(modifier = Modifier.height(25.dp))

                                    Text(text = "Allow Accessibility Service Access:", fontWeight = FontWeight.Bold)
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
                            }
                        } else {
                            Column(modifier = Modifier.padding(8.dp)) {
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

                                OutlinedTextField(
                                    value = glyphTimeout,
                                    onValueChange = { value ->
                                        val filtered = value.filter { it.isDigit() }
                                        glyphTimeout = filtered
                                    },
                                    label = { Text("(seconds)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.padding(top = 12.dp)
                                )

                                Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    IconButton(onClick = {
                                        val timeout = glyphTimeout.toLongOrNull() ?: 5L
                                        preferences.edit { putLong(Constants.PREFERENCES_GLYPH_TIMEOUT, timeout) }
                                        toast("Timeout saved")
                                    }) {
                                        Icon(imageVector = Icons.Filled.Save, contentDescription = "Save")
                                    }

                                    IconButton(onClick = {
                                        glyphTimeout = "5"
                                        preferences.edit { putLong(Constants.PREFERENCES_GLYPH_TIMEOUT, 5L) }
                                        toast("Timeout reset")
                                    }) {
                                        Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Reset")
                                    }
                                }

                                Spacer(modifier = Modifier.height(15.dp))

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

                                    Text(text = "Animation Speed", modifier = Modifier.padding(bottom = 8.dp))

                                    OutlinedTextField(
                                        value = animateSpeed,
                                        onValueChange = { value ->
                                            val filtered = value.filter { it.isDigit() }
                                            animateSpeed = filtered
                                        },
                                        label = { Text("(milliseconds)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.padding(top = 12.dp)
                                    )

                                    Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        IconButton(onClick = {
                                            val animateSpeed = animateSpeed.toLongOrNull() ?: 10L
                                            preferences.edit { putLong(Constants.PREFERENCES_ANIMATE_SPEED, animateSpeed) }
                                            toast("Animation speed saved")
                                        }) {
                                            Icon(imageVector = Icons.Filled.Save, contentDescription = "Save")
                                        }

                                        IconButton(onClick = {
                                            animateSpeed = "10"
                                            preferences.edit { putLong(Constants.PREFERENCES_ANIMATE_SPEED, 10L) }
                                            toast("Animation speed reset")
                                        }) {
                                            Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Reset")
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(25.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(10.dp))

                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(text = "Glyphs", modifier = Modifier.padding(vertical = 8.dp))

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
                                        val tmp = remember(newGlyph) {
                                            newGlyph.takeIf { it.isNotBlank() }?.let { BitmapFactory.decodeFile(it) }
                                        }

                                        if (tmp != null) {
                                            Image(
                                                painter = BitmapPainter(tmp.asImageBitmap(), filterQuality = FilterQuality.None),
                                                contentDescription = "Glyph Preview",
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { loadGlyph() }
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                                    .clickable { loadGlyph() },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(text = "+", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }

                                        IconButton(onClick = { createGlyph() }) {
                                            Icon(imageVector = Icons.Filled.Save, contentDescription = "Save")
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
                                            val tmp = remember(item.glyph) {
                                                item.glyph.takeIf { it.isNotBlank() }?.let { BitmapFactory.decodeFile(it) }
                                            }

                                            if (tmp != null) {
                                                Image(
                                                    painter = BitmapPainter(tmp.asImageBitmap(), filterQuality = FilterQuality.None),
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .size(56.dp)
                                                        .clickable { updateGlyph(item) }
                                                )
                                            } else {
                                                Spacer(modifier = Modifier.size(56.dp))
                                            }

                                            var expanded by remember { mutableStateOf(false) }

                                            Box {
                                                IconButton(onClick = {
                                                    focusManager.clearFocus(force = true)
                                                    keyboardController?.hide()

                                                    expanded = true
                                                }) {
                                                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                                                }

                                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                                    DropdownMenuItem(text = { Text("Move Up") }, onClick = { changeOrder(item, -1); expanded = false })
                                                    DropdownMenuItem(text = { Text("Move Down") }, onClick = { changeOrder(item, 1); expanded = false })
                                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { deleteGlyph(item); expanded = false })
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
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityServiceAccess()
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

    private fun updateAccessibilityServiceAccess() {
        hasAccessibilityServiceAccess = getAccessibilityServiceAccess()
    }

    private fun readGlyphMappings(): MutableList<Glyph> {
        val preferences = getSharedPreferences(Constants.PREFERENCES_NAME, MODE_PRIVATE)
        val raw = preferences.getString(Constants.PREFERENCES_GLYPHS, null) ?: return mutableListOf()
        val list = mutableListOf<Glyph>()

        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val glyph = obj.optString("glyph")

            list.add(Glyph(glyph))
        }

        return list
    }

    private fun writeGlyphMappings(list: List<Glyph>) {
        val arr = JSONArray()

        for ((glyph) in list) {
            val obj = JSONObject()
            obj.put("glyph", glyph)
            arr.put(obj)
        }

        val preferences = getSharedPreferences(Constants.PREFERENCES_NAME, MODE_PRIVATE)
        preferences.edit { putString(Constants.PREFERENCES_GLYPHS, arr.toString()) }
    }

    private fun toast(message: String) {
        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
    }

    private fun loadGlyph() {
        loadImageLauncherCallback = fun(loaded: String) {
            val newFile = File(filesDir, "tmp_glyph_${System.currentTimeMillis()}.png")
            try {
                File(loaded).copyTo(newFile, overwrite = true)
            } catch (e: Exception) {
                Log.e(tag, "Failed to update glyph: $e")
                toast("Failed to update glyph")
                return
            }

            filesDir.listFiles()?.filter { it.name.startsWith("tmp_glyph_") && it.name.endsWith(".png") && it.absolutePath != newFile.absolutePath }
                ?.forEach { try { it.delete() } catch (_: Throwable) {} }

            newGlyph = newFile.absolutePath
        }

        loadImageLauncher.launch(arrayOf("image/*"))
    }

    private fun createGlyph() {
        if (newGlyph.isBlank()) {
            toast("Choose a glyph")
            return
        }

        val newFile = File(filesDir, "glyph_${System.currentTimeMillis()}.png")
        try {
            File(newGlyph).copyTo(newFile, overwrite = true)
        } catch (e: Exception) {
            newGlyph = ""

            Log.e(tag, "Failed to save glyph: $e")
            toast("Failed to save glyph")
            return
        }

        glyphs.add(Glyph(newFile.absolutePath))
        writeGlyphMappings(glyphs)

        newGlyph = ""
        toast("Glyph saved")
    }

    private fun updateGlyph(item: Glyph) {
        loadImageLauncherCallback = fun(loaded: String) {
            val newFile = File(filesDir, "glyph_${System.currentTimeMillis()}.png")
            try {
                File(loaded).copyTo(newFile, overwrite = true)
            } catch (e: Exception) {
                Log.e(tag, "Failed to update glyph: $e")
                toast("Failed to update glyph")
                return
            }

            val i = glyphs.indexOfFirst { it.glyph == item.glyph }
            if (i != -1) {
                glyphs[i] = Glyph(newFile.absolutePath)
                writeGlyphMappings(glyphs)
            }

            try { File(item.glyph).delete() } catch (_: Throwable) {}

            toast("Glyph updated")
        }

        loadImageLauncher.launch(arrayOf("image/*"))
    }

    private fun deleteGlyph(item: Glyph) {
        glyphs.remove(item)
        writeGlyphMappings(glyphs)
        toast("Glyph removed")
    }

    private fun changeOrder(item: Glyph, n: Int) {
        val i = glyphs.indexOf(item)
        val p = i + n

        if (i !in glyphs.indices || p !in glyphs.indices) return

        val current = glyphs[i]
        val next = glyphs[p]

        glyphs[i] = next
        glyphs[p] = current

        writeGlyphMappings(glyphs)
    }
}
