package com.aistra.hail.ui.main

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.MenuCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.aistra.hail.R
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.ActivityMainBinding
import com.aistra.hail.extensions.*
import com.aistra.hail.utils.HPolicy
import com.aistra.hail.utils.HShizuku
import com.aistra.hail.utils.HUI
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * S25 Freeze v2: script editor is accessible through the main overflow menu.
 * It runs only on explicit user action; it does not start a background service.
 */
class MainActivity : AppCompatActivity(), NavController.OnDestinationChangedListener {
    lateinit var fab: ExtendedFloatingActionButton
    lateinit var appbar: AppBarLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val binding = initView()
        if (!HailData.biometricLogin || BiometricManager.from(this)
                .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) != BiometricManager.BIOMETRIC_SUCCESS
        ) return
        binding.root.isVisible = false
        val biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    HUI.showToast(errString)
                    finishAndRemoveTask()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    binding.root.isVisible = true
                }
            })
        val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle(getString(R.string.action_biometric))
            .setSubtitle(getString(R.string.msg_biometric)).setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        biometricPrompt.authenticate(promptInfo)
    }

    private fun initView() = ActivityMainBinding.inflate(layoutInflater).apply {
        setContentView(root)
        setSupportActionBar(appBarMain.toolbar)
        fab = appBarMain.fab
        appbar = appBarMain.appBarLayout

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        navController.addOnDestinationChangedListener(this@MainActivity)
        val appBarConfiguration = AppBarConfiguration.Builder(
            R.id.nav_home, R.id.nav_apps, R.id.nav_settings, R.id.nav_about
        ).build()
        setupActionBarWithNavController(navController, appBarConfiguration)
        bottomNav?.setupWithNavController(navController)
        navRail?.setupWithNavController(navController)

        val isRtl = isRtl
        val isLandscape = isLandscape
        appBarMain.appBarLayout.applyDefaultInsetter {
            paddingRelative(isRtl, start = !isLandscape, end = true, top = true)
        }
        bottomNav?.applyDefaultInsetter { paddingRelative(isRtl, start = true, end = true, bottom = true) }
        navRail?.applyDefaultInsetter { paddingRelative(isRtl, start = true, top = true) }
        fab.applyDefaultInsetter { marginRelative(isRtl, end = true, bottom = isLandscape) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val result = super.onCreateOptionsMenu(menu)
        MenuCompat.setGroupDividerEnabled(menu, true)
        menu.add(Menu.NONE, SCRIPT_MENU_ID, Menu.NONE, "Scripts · Shizuku")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return result
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        SCRIPT_MENU_ID -> {
            S25ScriptEditor(this).show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    fun ownerRemoveDialog() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.title_remove_owner).setMessage(R.string.msg_remove_owner)
            .setPositiveButton(R.string.action_continue) { _, _ ->
                HPolicy.setOrganizationName()
                HPolicy.removeDeviceOwner()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    /* override fun onStop() {
        super.onStop()
        if (HailData.biometricLogin) finishAndRemoveTask()
    } */

    override fun onDestinationChanged(
        controller: NavController, destination: NavDestination, arguments: Bundle?
    ) {
        fab.tag = destination.id == R.id.nav_home
        if (fab.tag == true) fab.show() else fab.hide()
    }

    private companion object {
        const val SCRIPT_MENU_ID = 0x5325
    }
}

/** Local-only script collection. There is deliberately no auto-run or boot receiver. */
private class S25ScriptEditor(private val activity: MainActivity) {
    private val prefs = activity.getSharedPreferences("s25_script_editor_v2", Context.MODE_PRIVATE)
    private var currentName: String? = prefs.getString("draft_name", null)
    private val scripts: JSONObject = runCatching {
        JSONObject(prefs.getString("saved_scripts", "{}") ?: "{}")
    }.getOrElse { JSONObject() }

    private fun Int.dp(): Int = (this * activity.resources.displayMetrics.density + 0.5f).toInt()

    fun show() {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp(), 8.dp(), 14.dp(), 4.dp())
        }
        val status = TextView(activity).apply {
            text = currentName?.let { "Script : $it" } ?: "Nouveau script (non enregistré)"
            textSize = 13f
        }
        root.addView(status)

        val editor = EditText(activity).apply {
            setText(prefs.getString("draft", "id\ngetprop ro.build.version.release") ?: "")
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(false)
            minLines = 8
            maxLines = 16
            hint = "Commandes shell Android, une par ligne"
        }
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 230.dp()))
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("draft", s?.toString().orEmpty()).apply()
            }
        })

        fun row(): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun addButton(bar: LinearLayout, label: String, action: () -> Unit): Button {
            val button = Button(activity).apply { text = label; textSize = 12f; setOnClickListener { action() } }
            bar.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return button
        }
        fun setName(name: String?) {
            currentName = name
            prefs.edit().putString("draft_name", name).apply()
            status.text = name?.let { "Script : $it" } ?: "Nouveau script (non enregistré)"
        }
        fun persistScripts() {
            prefs.edit().putString("saved_scripts", scripts.toString()).apply()
        }

        val top = row()
        addButton(top, "Nouveau") {
            setName(null)
            editor.setText("")
        }
        addButton(top, "Ouvrir") {
            val names = scripts.keys().asSequence().toList().sorted()
            if (names.isEmpty()) {
                status.text = "Aucun script enregistré"
            } else {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("Scripts enregistrés")
                    .setItems(names.toTypedArray()) { _, which ->
                        val name = names[which]
                        setName(name)
                        editor.setText(scripts.optString(name))
                        editor.setSelection(editor.text.length)
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        }
        addButton(top, "Enregistrer") {
            val nameField = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSingleLine(true)
                setText(currentName.orEmpty())
                setSelection(text.length)
                hint = "Nom du script"
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle("Enregistrer le script")
                .setView(nameField)
                .setPositiveButton("Enregistrer") { _, _ ->
                    val name = nameField.text.toString().trim().take(70)
                    if (name.isBlank()) {
                        status.text = "Nom vide : enregistrement annulé"
                    } else {
                        scripts.put(name, editor.text.toString())
                        persistScripts()
                        setName(name)
                        status.text = "Enregistré : $name"
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
        root.addView(top)

        val output = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            text = "Résultat de l'exécution affiché ici."
        }
        val outputScroll = ScrollView(activity).apply {
            setPadding(6.dp(), 6.dp(), 6.dp(), 6.dp())
            addView(output)
        }
        val bottom = row()
        addButton(bottom, "Supprimer") {
            val name = currentName
            if (name == null || !scripts.has(name)) {
                status.text = "Aucun script enregistré sélectionné"
            } else {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("Supprimer « $name » ?")
                    .setMessage("Cette action supprime uniquement le script sauvegardé, pas les applications.")
                    .setNegativeButton("Annuler", null)
                    .setPositiveButton("Supprimer") { _, _ ->
                        scripts.remove(name)
                        persistScripts()
                        setName(null)
                        status.text = "Script supprimé : $name"
                    }
                    .show()
            }
        }
        lateinit var runButton: Button
        runButton = addButton(bottom, "Exécuter") {
            val command = editor.text.toString()
            if (command.isBlank()) {
                output.text = "Le script est vide."
            } else if (!Shizuku.pingBinder()) {
                output.text = "Shizuku est arrêté. Démarre Shizuku et réessaie."
            } else if (runCatching { Shizuku.checkSelfPermission() }.getOrDefault(-1)
                != PackageManager.PERMISSION_GRANTED) {
                output.text = "Autorise S25 Freeze dans l'application Shizuku, puis réessaie."
            } else {
                // Execution is explicit and stays off the UI thread.
                runButton.isEnabled = false
                status.text = "Exécution en cours…"
                output.text = "Exécution via Shizuku…"
                val started = SystemClock.elapsedRealtime()
                activity.lifecycleScope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            // Combine stderr and stdout, keeping multi-line shell syntax.
                            HShizuku.execute("exec 2>&1\n$command\n")
                        }
                        val elapsed = SystemClock.elapsedRealtime() - started
                        output.text = "Code retour : ${result.first} · ${elapsed} ms\n\n" +
                            (result.second ?: "(aucune sortie)").takeLast(32000)
                        status.text = if (result.first == 0) "Exécution terminée" else "Commande terminée avec erreur"
                    } catch (e: Exception) {
                        status.text = "Échec de l'exécution"
                        output.text = e.message ?: e.javaClass.simpleName
                    } finally {
                        runButton.isEnabled = true
                    }
                }
            }
        }
        root.addView(bottom)
        root.addView(TextView(activity).apply {
            text = "Sortie Shizuku (les commandes s'exécutent avec les privilèges disponibles, sans root garanti)."
            textSize = 11f
        })
        root.addView(outputScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 145.dp()))

        val scroll = ScrollView(activity).apply { addView(root) }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Scripts · Shizuku")
            .setView(scroll)
            .setNegativeButton("Fermer", null)
            .create()
        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * 0.88f).toInt())
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
    }
}
