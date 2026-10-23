package com.clonehub.advanced.ui.picker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.clonehub.advanced.R
import com.clonehub.advanced.data.model.InstalledAppInfo

class AppPickerAdapter(
    private val onCloneClick: (InstalledAppInfo) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.AppPickerViewHolder>() {

    private val allApps = mutableListOf<InstalledAppInfo>()
    private val filteredApps = mutableListOf<InstalledAppInfo>()

    fun submitList(apps: List<InstalledAppInfo>) {
        allApps.clear()
        allApps.addAll(apps)
        filter("")
    }

    fun filter(query: String) {
        filteredApps.clear()
        if (query.isBlank()) {
            filteredApps.addAll(allApps)
        } else {
            val q = query.lowercase()
            filteredApps.addAll(allApps.filter {
                it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            })
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = filteredApps.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppPickerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_installed_app, parent, false)
        return AppPickerViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppPickerViewHolder, position: Int) {
        holder.bind(filteredApps[position])
    }

    inner class AppPickerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvPackage: TextView = itemView.findViewById(R.id.tvPackageName)
        private val btnClone: Button = itemView.findViewById(R.id.btnClone)

        fun bind(app: InstalledAppInfo) {
            tvName.text = app.appName
            tvPackage.text = app.packageName
            ivIcon.setImageDrawable(app.icon)

            btnClone.setOnClickListener {
                onCloneClick(app)
            }
        }
    }
}
