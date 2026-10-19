package pl.zamirski.jarvis.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import pl.zamirski.jarvis.R
import pl.zamirski.jarvis.core.ChatMessage
import pl.zamirski.jarvis.core.Kind

class ChatAdapter(private val items: List<ChatMessage>) : RecyclerView.Adapter<ChatAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.message_label)
        val text: TextView = view.findViewById(R.id.message_text)
        val bubble: View = view.findViewById(R.id.message_bubble)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val message = items[position]
        val ctx = holder.itemView.context
        holder.text.text = message.text
        val params = holder.bubble.layoutParams as FrameLayout.LayoutParams
        when (message.kind) {
            Kind.USER -> {
                holder.label.text = ctx.getString(R.string.label_you)
                holder.bubble.setBackgroundResource(R.drawable.bubble_user)
                params.gravity = Gravity.END
                holder.label.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }
            Kind.ASSISTANT -> {
                holder.label.text = ctx.getString(R.string.label_jarvis)
                holder.bubble.setBackgroundResource(R.drawable.bubble_jarvis)
                params.gravity = Gravity.START
                holder.label.setTextColor(ContextCompat.getColor(ctx, R.color.accent))
            }
            Kind.NOTICE -> {
                holder.label.text = ctx.getString(R.string.label_system)
                holder.bubble.setBackgroundResource(R.drawable.bubble_notice)
                params.gravity = Gravity.CENTER_HORIZONTAL
                holder.label.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
            }
        }
        holder.bubble.layoutParams = params
    }
}
