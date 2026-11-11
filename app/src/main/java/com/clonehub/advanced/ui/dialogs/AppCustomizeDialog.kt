package com.clonehub.advanced.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import com.clonehub.advanced.R
import com.clonehub.advanced.core.container.SpaceManager
import com.clonehub.advanced.data.model.ClonedApp

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

        // Shared cache: same drawable the grid shows, without re-decoding on the UI thread.
        ivPreview.setImageDrawable(
            com.clonehub.advanced.ui.home.IconLoader.loadBlocking(context, app.packageName, app.customIconPath)
        )

        btnReset.setOnClickListener {
            etName.setText(app.originalName)
            ivPreview.setImageDrawable(
                com.clonehub.advanced.ui.home.IconLoader.loadBlocking(context, app.packageName, null)
            )
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
            com.clonehub.advanced.ui.home.IconLoader.evict(app.packageName)
            onSaved()
            dismiss()
        }
    }
}
