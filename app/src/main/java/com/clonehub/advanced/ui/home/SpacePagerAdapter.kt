package com.clonehub.advanced.ui.home

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
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

    fun submitSpaces(newSpaces: List<Space>) {
        spaces.clear()
        spaces.addAll(newSpaces)
        notifyDataSetChanged()
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

        fun bind(space: Space) {
            tvBadge.text = "Space ${space.spaceId}"

            btnMenu.setOnClickListener { onSpaceMenuClick(space) }

            // 4 Column Grid matching Clone Hub
            recyclerApps.layoutManager = GridLayoutManager(context, 4)
            val adapter = AppGridAdapter(
                context = context,
                spaceId = space.spaceId,
                onAppClick = { app -> onAppClick(app, space.spaceId) },
                onAppLongClick = { app -> onAppLongClick(app, space.spaceId) },
                onAddClick = { onAddClick(space.spaceId) }
            )
            recyclerApps.adapter = adapter
            adapter.submitList(space.apps)
        }
    }
}
