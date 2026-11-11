package com.clonehub.advanced.ui.home

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.clonehub.advanced.R
import com.clonehub.advanced.data.model.ClonedApp
import androidx.core.view.isVisible

class AppGridAdapter(
    private val context: Context,
    private val spaceId: Int,
    private val onAppClick: (ClonedApp) -> Unit,
    private val onAppLongClick: (ClonedApp) -> Unit,
    private val onAddClick: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val apps = mutableListOf<ClonedApp>()

    init {
        // Stable ids let RecyclerView keep a clone's view while other clones are added or removed,
        // instead of re-binding the whole grid.
        setHasStableIds(true)
    }

    companion object {
        private const val TYPE_APP = 1
        private const val TYPE_ADD = 2
    }

    override fun getItemId(position: Int): Long =
        if (position < apps.size) apps[position].packageName.hashCode().toLong() else Long.MIN_VALUE

    /**
     * Applies [newApps] with fine-grained change notifications.
     *
     * The previous implementation cleared the list and called `notifyDataSetChanged()`, so every
     * clone icon was re-bound (and previously re-decoded) whenever anything changed — including a
     * launch-result update. Diffing keeps the work proportional to what actually changed.
     */
    fun submitList(newApps: List<ClonedApp>) {
        if (apps == newApps) return
        val old = apps.toList()
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = newApps.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                old[oldPos].packageName == newApps[newPos].packageName

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                old[oldPos].appName == newApps[newPos].appName &&
                    old[oldPos].customIconPath == newApps[newPos].customIconPath &&
                    old[oldPos].lastLaunchSucceeded == newApps[newPos].lastLaunchSucceeded &&
                    old[oldPos].lastFailureReason == newApps[newPos].lastFailureReason
        }).dispatchUpdatesTo(this)
        apps.clear()
        apps.addAll(newApps)
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
        private val ivStatus: ImageView = itemView.findViewById(R.id.ivAppStatus)

        private var boundPackage: String? = null

        fun bind(app: ClonedApp) {
            tvTitle.text = app.appName
            boundPackage = app.packageName
            // Icons resolve in the background; a callback that arrives after this view was reused
            // for another clone is ignored instead of showing the wrong icon.
            ivIcon.setImageDrawable(
                IconLoader.load(context, app.packageName, app.customIconPath) { drawable ->
                    if (boundPackage == app.packageName) {
                        ivIcon.setImageDrawable(drawable)
                    }
                }
            )

            // Real clone health: only shown when the last launch actually failed.
            val failed = app.lastLaunchSucceeded == false
            ivStatus.isVisible = failed
            if (failed) {
                ivStatus.setImageResource(R.drawable.ic_status_failed)
                ivStatus.contentDescription = context.getString(
                    R.string.clone_status_failed,
                    app.lastFailureReason ?: ""
                )
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
