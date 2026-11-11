package com.clonehub.advanced.ui.home

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clonehub.advanced.R
import com.clonehub.advanced.data.model.ClonedApp
import com.clonehub.advanced.data.model.Space

class SpacePagerAdapter(
    private val context: Context,
    private val onAppClick: (ClonedApp, Int) -> Unit,
    private val onAppLongClick: (ClonedApp, Int) -> Unit,
    private val onAddClick: (Int) -> Unit,
    private val onSpaceMenuClick: (Space) -> Unit
) : RecyclerView.Adapter<SpacePagerAdapter.SpaceViewHolder>() {

    private val spaces = mutableListOf<Space>()

    /**
     * Applies [newSpaces] with change notifications. A full `notifyDataSetChanged()` here rebuilt
     * every space card, which in turn re-bound every grid of every visible space.
     */
    fun submitSpaces(newSpaces: List<Space>) {
        if (spaces == newSpaces) return
        val old = spaces.toList()
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = newSpaces.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                old[oldPos].spaceId == newSpaces[newPos].spaceId

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                old[oldPos].spaceName == newSpaces[newPos].spaceName &&
                    old[oldPos].apps == newSpaces[newPos].apps
        }).dispatchUpdatesTo(this)
        spaces.clear()
        spaces.addAll(newSpaces)
    }

    override fun getItemCount(): Int = spaces.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SpaceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_space_card, parent, false)
        return SpaceViewHolder(view)
    }

    override fun onBindViewHolder(holder: SpaceViewHolder, position: Int) {
        holder.bind(spaces[position])
    }

    inner class SpaceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvBadge: TextView = itemView.findViewById(R.id.tvSpaceBadge)
        private val btnMenu: ImageButton = itemView.findViewById(R.id.btnSpaceMenu)
        private val recyclerApps: RecyclerView = itemView.findViewById(R.id.recyclerAppsGrid)

        private var boundSpaceId: Int = -1
        private var appAdapter: AppGridAdapter? = null

        fun bind(space: Space) {
            tvBadge.text = if (space.spaceName == "Space ${space.spaceId}") {
                "Space ${space.spaceId}"
            } else {
                space.spaceName
            }

            btnMenu.setOnClickListener { onSpaceMenuClick(space) }

            // The grid is reused across binds; recreating it on every update caused a full
            // re-inflate of every icon whenever any space changed.
            if (boundSpaceId != space.spaceId || appAdapter == null) {
                boundSpaceId = space.spaceId
                recyclerApps.layoutManager = GridLayoutManager(context, 4)
                appAdapter = AppGridAdapter(
                    context = context,
                    spaceId = space.spaceId,
                    onAppClick = { app -> onAppClick(app, space.spaceId) },
                    onAppLongClick = { app -> onAppLongClick(app, space.spaceId) },
                    onAddClick = { onAddClick(space.spaceId) }
                ).also { recyclerApps.adapter = it }
            }
            appAdapter?.submitList(space.apps)
        }
    }
}
