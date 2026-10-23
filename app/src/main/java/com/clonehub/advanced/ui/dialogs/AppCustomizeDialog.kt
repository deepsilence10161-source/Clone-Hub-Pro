package com.clonehub.advanced.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import com.clonehub.advanced.R
import com.clonehub.advanced.core.container.SpaceManager
import com.clonehub.advanced.data.model.ClonedApp
import java.io.File

class AppCustomizeDialog(
    context: Context,
    private val app: ClonedApp,
    private val spaceId: Int,
    private val onSaved: () -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_custom_icon)

        val ivPreview: ImageView = findViewById(R.id.ivCustomPreview)
        val etName: EditText = findViewById(R.id.etAppName)
        val btnReset: TextView = findViewById(R.id.btnReset)
        val btnSave: Button = findViewById(R.id.btnSave)
        val btnCancel: Button = findViewById(R.id.btnCancel)

        etName.setText(app.appName)

        // Load Icon
        val pm = context.packageManager
        val hostIcon = runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull()
        if (!app.customIconPath.isNullOrEmpty() && File(app.customIconPath!!).exists()) {
            val bitmap = BitmapFactory.decodeFile(app.customIconPath)
            ivPreview.setImageDrawable(BitmapDrawable(context.resources, bitmap))
        } else {
            ivPreview.setImageDrawable(hostIcon ?: context.getDrawable(R.drawable.ic_action_install))
        }

        btnReset.setOnClickListener {
            etName.setText(app.originalName)
            ivPreview.setImageDrawable(hostIcon)
        }

        btnCancel.setOnClickListener { dismiss() }

        btnSave.setOnClickListener {
            val newName = etName.text.toString().trim().ifEmpty { app.originalName }
            SpaceManager.getInstance(context).updateAppCustomization(
                spaceId = spaceId,
                packageName = app.packageName,
                newName = newName,
                customIconPath = app.customIconPath
            )
            onSaved()
            dismiss()
        }
    }
}
