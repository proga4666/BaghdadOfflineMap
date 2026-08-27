package com.offlinemap.baghdad.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.offlinemap.baghdad.R
import com.offlinemap.baghdad.data.model.RouteInstruction
import com.offlinemap.baghdad.databinding.ItemInstructionBinding
import com.offlinemap.baghdad.utils.GeoUtils

class InstructionAdapter(
    private var instructions: List<RouteInstruction> = emptyList()
) : RecyclerView.Adapter<InstructionAdapter.InstructionViewHolder>() {

    fun updateInstructions(newInstructions: List<RouteInstruction>) {
        instructions = newInstructions
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InstructionViewHolder {
        val binding = ItemInstructionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return InstructionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: InstructionViewHolder, position: Int) {
        holder.bind(instructions[position])
    }

    override fun getItemCount(): Int = instructions.size

    class InstructionViewHolder(private val binding: ItemInstructionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(instruction: RouteInstruction) {
            binding.tvInstructionText.text = instruction.text
            binding.tvInstructionDistance.text = "In ${GeoUtils.formatDistance(instruction.distanceMeters)}"

            val iconRes = when (instruction.turnType) {
                RouteInstruction.TurnType.STRAIGHT -> R.drawable.ic_turn_straight
                RouteInstruction.TurnType.LEFT, RouteInstruction.TurnType.SHARP_LEFT, RouteInstruction.TurnType.SLIGHT_LEFT -> R.drawable.ic_turn_left
                RouteInstruction.TurnType.RIGHT, RouteInstruction.TurnType.SHARP_RIGHT, RouteInstruction.TurnType.SLIGHT_RIGHT -> R.drawable.ic_turn_right
                RouteInstruction.TurnType.FINISH -> R.drawable.ic_finish
                else -> R.drawable.ic_turn_straight
            }
            binding.ivTurnIcon.setImageResource(iconRes)
        }
    }
}
