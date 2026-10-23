package com.clonehub.advanced.ui.home

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.clonehub.advanced.R
import com.clonehub.advanced.data.model.ClonedApp
import java.io.File

class AppGridAdapter(
    private val context: Context,
    private val spaceId: Int,
    private val onAppClick: (ClonedApp) -> Unit,
    private val onAppLongClick: (ClonedApp) -> Unit,
    private val onAddClick: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val apps = mutableListOf<ClonedApp>()

    companion object {
        private const val TYPE_APP = 1
        private const val TYPE_ADD = 2
    }

    fun submitList(newApps: List<ClonedApp>) {
        apps.clear()
        apps.addAll(newApps)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = apps.size + 1 // Apps + 1 for Add Button

    override fun getItemViewType(position: Int): Int {
        return if (position < apps.size) TYPE_APP else TYPE_ADD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_APP) {
            val view = inflater.inflate(R.layout.item_app_grid, parent, false)
            AppViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_add_app, parent, false)
            AddViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is AppViewHolder) {
            val app = apps[position]
            holder.bind(app)
        } else if (holder is AddViewHolder) {
            holder.bind()
        }
    }

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvAppTitle)

        fun bind(app: ClonedApp) {
            tvTitle.text = app.appName

            // Load Custom Icon if set, else Host App Icon
            if (!app.customIconPath.isNullOrEmpty() && File(app.customIconPath!!).exists()) {
                val bitmap = BitmapFactory.decodeFile(app.customIconPath)
                ivIcon.setImageDrawable(BitmapDrawable(context.resources, bitmap))
            } else {
                val pm = context.packageManager
                val icon = runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull()
                ivIcon.setImageDrawable(icon ?: context.getDrawable(R.drawable.ic_action_install))
            }

            itemView.setOnClickListener { onAppClick(app) }
            itemView.setOnLongClickListener {
                onAppLongClick(app)
                true
            }
        }
    }

    inner class AddViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind() {
            itemView.setOnClickListener { onAddClick(spaceId) }
        }
    }
}
